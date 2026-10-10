package dev.phoenium.engine;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongCollection;
import java.util.ArrayList;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongPredicate;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * S9-E12 (engine mode; {@link EngineConfig#AUTOSAVE_SPREAD}, default off): the periodic autosave
 * (MinecraftServer.autoSave, every 6000 ticks) keeps its cadence and still saves the player files, the scoreboard, the
 * level data and the saved data in its tick as vanilla does. Its two per-chunk loops only record their chunk keys:
 * ChunkMap.saveAllChunks(false), which copies every unsaved chunk on the server thread (saveChunkIfNeeded; encoding and
 * writing already run off it), and PersistentEntitySectionManager.autoSave(), which stores every chunk's entities (or
 * unloads a hidden chunk). Each later tick works through the next keys of each level, at most
 * {@link EngineConfig#AUTOSAVE_CHUNKS} saves and {@link EngineConfig#AUTOSAVE_BUDGET_US} per queue and tick (always at
 * least one key), in the places where vanilla saves outside the autosave: ChunkMap.tick(haveTime) (its eager saves) and
 * PersistentEntitySectionManager.tick() (its unloads). Each key gets vanilla's own per-chunk call at its turn: a chunk
 * saved meanwhile (eagerly or on unload) is no longer unsaved and is skipped, and an unloaded chunk was saved by its
 * unload. As in vanilla, the autosave clears the chunk save cooldowns when it starts.
 *
 * <p>S9-E14: PlayerList.saveAll() in that autosave records the online players too, and each PlayerList.tick() after it
 * saves the next one still online ({@link EngineConfig#AUTOSAVE_SPREAD_PLAYERS}; a player who left was saved by the logout).
 * A player's data and the chunks are then saved at different ticks: on a crash inside the window, an item moved between a
 * player and the world can be lost or duplicated, like the entity case below.
 *
 * <p>Only an autosave with players online spreads; an empty server, which may pause right after its autosave, saves at
 * once. /save-all, save-all flush, the shutdown save and every other full save run as vanilla and drop the level's queue,
 * since they cover it. Durability: until a level's queue is done, a crash loses what a single-tick autosave would have
 * saved, as a crash just before the autosave would. Chunks are saved at different ticks, so an entity that crosses from
 * one chunk to another inside that window can be saved twice or not at all if the server crashes before both chunks are
 * saved again; the shutdown save is complete.
 *
 * <p>Self-checks that must stay 0: autosaveOverlap (a queue still busy when the next autosave starts) and
 * autosaveOffThread (a chunk or entity save loop called off the server thread while an autosave records; it runs as
 * vanilla).
 */
public final class EngineAutosave {
    private EngineAutosave() { }

    /** Server thread: inside an autosave that spreads. */
    private static boolean recording;
    private static final LongAdder OFF_THREAD = new LongAdder();
    // server thread
    private static long spreads;
    private static long chunkQueues;
    private static long chunkKeys;
    private static long chunkSaves;
    private static long entityQueues;
    private static long entityKeys;
    private static long entityStores;
    private static long playerQueues;
    private static long playerKeys;
    private static long playerSaves;
    private static long drains;
    private static long maxBacklog;
    private static long maxDrainNanos;
    private static long dropped;
    private static long overlap;

    /** A PersistentEntitySectionManager with a spread queue (the engine's entity autosave mixin). */
    public interface EntityStores {
        /** Server thread, after the manager's tick when its level is not noSave: the next recorded entity stores. */
        void phoenium$drainEntityStores();
    }

    /** A queue of chunk keys worked through a few per tick, in recorded order. Not thread-safe (server thread). */
    public static final class Spread {
        private final LongArrayList keys = new LongArrayList();
        private int next;

        public boolean busy() { return this.next < this.keys.size(); }

        public int backlog() { return this.keys.size() - this.next; }

        /** Starts over with {@code recorded}; true if the previous queue was not done. */
        public boolean begin(LongCollection recorded) {
            boolean busy = busy();
            this.keys.clear();
            this.next = 0;
            this.keys.addAll(recorded);
            return busy;
        }

        /** Forgets the queue; true if it was not done. */
        public boolean drop() {
            boolean busy = busy();
            this.keys.clear();
            this.next = 0;
            return busy;
        }

        /**
         * Runs {@code save} on the next keys in order until it has returned true {@code maxSaves} times or
         * {@code budgetNanos} have passed by {@code clock}, at least one key; returns the number of true results.
         */
        public int drain(LongPredicate save, int maxSaves, long budgetNanos, LongSupplier clock) {
            long start = clock.getAsLong();
            int saved = 0;
            while (busy()) {
                long key = this.keys.getLong(this.next++);
                if (save.test(key)) saved++;
                if (saved >= maxSaves || clock.getAsLong() - start >= budgetNanos) break;
            }
            if (!busy()) {
                this.keys.clear();
                this.next = 0;
            }
            return saved;
        }
    }

    /** S9-E14: a queue of objects (the players' UUIDs) worked through like {@link Spread}. Not thread-safe (server thread). */
    public static final class Queue<T> {
        private final ArrayList<T> items = new ArrayList<>();
        private int next;

        public boolean busy() { return this.next < this.items.size(); }

        public int backlog() { return this.items.size() - this.next; }

        /** Starts over with {@code recorded}; true if the previous queue was not done. */
        public boolean begin(T[] recorded) {
            boolean busy = busy();
            this.items.clear();
            this.next = 0;
            for (T item : recorded) this.items.add(item);
            return busy;
        }

        /** Forgets the queue; true if it was not done. */
        public boolean drop() {
            boolean busy = busy();
            this.items.clear();
            this.next = 0;
            return busy;
        }

        /** As {@link Spread#drain}. */
        public int drain(Predicate<T> save, int maxSaves, long budgetNanos, LongSupplier clock) {
            long start = clock.getAsLong();
            int saved = 0;
            while (busy()) {
                T item = this.items.get(this.next);
                this.items.set(this.next++, null);
                if (save.test(item)) saved++;
                if (saved >= maxSaves || clock.getAsLong() - start >= budgetNanos) break;
            }
            if (!busy()) {
                this.items.clear();
                this.next = 0;
            }
            return saved;
        }
    }

    /** MinecraftServer.autoSave's saveEverything: whether this one spreads (players online, server thread). */
    public static boolean shouldSpread(int players) {
        return EngineConfig.AUTOSAVE_SPREAD && players > 0 && TileEngine.onServerThread();
    }

    public static void beginRecording() {
        recording = true;
        spreads++;
    }

    public static void endRecording() { recording = false; }

    /** A save loop asks whether to record its keys instead of saving: only inside a spreading autosave, on the server thread. */
    public static boolean recordChunks() { return recordingHere(); }

    public static boolean recordEntities() { return EngineConfig.AUTOSAVE_SPREAD_ENTITIES && recordingHere(); }

    public static boolean recordPlayers() { return EngineConfig.AUTOSAVE_SPREAD_PLAYERS && recordingHere(); }

    private static boolean recordingHere() {
        if (!recording) return false;
        if (TileEngine.onServerThread()) return true;
        OFF_THREAD.increment();
        return false;
    }

    /** ChunkMap.saveAllChunks(false) recorded its holders' keys. */
    public static void recordedChunks(Spread queue, LongCollection keys) {
        if (queue.begin(keys)) overlap++;
        chunkQueues++;
        chunkKeys += keys.size();
        maxBacklog = Math.max(maxBacklog, keys.size());
    }

    /** PersistentEntitySectionManager.autoSave() recorded its chunks' keys. */
    public static void recordedEntities(Spread queue, LongCollection keys) {
        if (queue.begin(keys)) overlap++;
        entityQueues++;
        entityKeys += keys.size();
        maxBacklog = Math.max(maxBacklog, keys.size());
    }

    /** PlayerList.saveAll() recorded the online players. */
    public static <T> void recordedPlayers(Queue<T> queue, T[] players) {
        if (queue.begin(players)) overlap++;
        playerQueues++;
        playerKeys += players.length;
    }

    /** A full save (or a non-spreading autosave) covers the queue. */
    public static void fullSave(Spread queue) {
        if (queue.drop()) dropped++;
    }

    public static void fullSave(Queue<?> queue) {
        if (queue.drop()) dropped++;
    }

    public static <T> void drainPlayers(Queue<T> queue, Predicate<T> save) {
        long start = EngineTimers.start();
        playerSaves += queue.drain(save, EngineConfig.AUTOSAVE_CHUNKS, EngineConfig.AUTOSAVE_BUDGET_US * 1000L, System::nanoTime);
        long took = System.nanoTime() - start;
        drains++;
        maxDrainNanos = Math.max(maxDrainNanos, took);
        EngineTimers.add(EngineTimers.AUTOSAVE_SPREAD, start);
    }

    public static void drainChunks(Spread queue, LongPredicate save) {
        chunkSaves += drain(queue, save);
    }

    public static void drainEntities(Spread queue, LongPredicate store) {
        entityStores += drain(queue, store);
    }

    private static int drain(Spread queue, LongPredicate save) {
        long start = EngineTimers.start();
        int done = queue.drain(save, EngineConfig.AUTOSAVE_CHUNKS, EngineConfig.AUTOSAVE_BUDGET_US * 1000L, System::nanoTime);
        long took = System.nanoTime() - start;
        drains++;
        maxDrainNanos = Math.max(maxDrainNanos, took);
        EngineTimers.add(EngineTimers.AUTOSAVE_SPREAD, start);
        return done;
    }

    static String counters() {
        return "autosaveSpreads=" + spreads + " autosaveChunkQueues=" + chunkQueues + " autosaveChunkKeys=" + chunkKeys
            + " autosaveChunkSaves=" + chunkSaves + " autosaveEntityQueues=" + entityQueues + " autosaveEntityKeys=" + entityKeys
            + " autosaveEntityStores=" + entityStores + " autosavePlayerQueues=" + playerQueues + " autosavePlayerKeys=" + playerKeys
            + " autosavePlayerSaves=" + playerSaves + " autosaveDrains=" + drains + " autosaveMaxBacklog=" + maxBacklog
            + " autosaveMaxDrainUs=" + maxDrainNanos / 1000 + " autosaveDropped=" + dropped + " autosaveOverlap=" + overlap
            + " autosaveOffThread=" + OFF_THREAD.sum();
    }
}
