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
 *
 * <p>S9-E15: {@link EngineConfig#AUTOSAVE_TICK_BUDGET_US} bounds what all queues together use in one server tick (D33's
 * budget was per queue, and up to seven queues could drain in one tick: chunks and entities of three levels, players).
 * Each tick one kind of queue (chunks, entities, players) leads, in turn among the kinds with work left; only the lead
 * kind drains, within the tick budget, and when the lead kind has nothing left the others may use what remains. The
 * first drain of a tick still runs at least one key, so a single save (a first-ever player file, a blocking write) can
 * exceed the budget; the budget is a target, not a cap. Every key's outcome is counted (saved, gone, idle, not ready,
 * clean, cooldown, not written; entity stores, unloads, pending; player saves, gone), so a spread's keys add up exactly,
 * and the live backlog, the drains per kind, the per-tick spread time and the spread length (ticks from the autosave to
 * the last queue done) are reported.
 */
public final class EngineAutosave {
    private EngineAutosave() { }

    public static final int CHUNKS = 0;
    public static final int ENTITIES = 1;
    public static final int PLAYERS = 2;
    private static final String[] KIND_NAMES = {"Chunk", "Entity", "Player"};

    /** Chunk key outcomes (ChunkMap.saveChunkIfNeeded's conditions, in its order). */
    public static final int CHUNK_SAVED = 0;
    public static final int CHUNK_GONE = 1;        // no visible holder: unloaded (its unload saved it)
    public static final int CHUNK_IDLE = 2;        // not accessible since its last save
    public static final int CHUNK_NOT_READY = 3;   // not ready for saving, or neither a LevelChunk nor an ImposterProtoChunk
    public static final int CHUNK_CLEAN = 4;       // nothing unsaved
    public static final int CHUNK_COOLDOWN = 5;    // saved less than 10 s ago (an eager or C2ME idle save after the autosave)
    public static final int CHUNK_NOT_WRITTEN = 6; // eligible, but save() wrote nothing (marked saved meanwhile, an existing full chunk, empty, failed)
    private static final String[] CHUNK_OUTCOMES = {"Saves", "Gone", "Idle", "NotReady", "Clean", "Cooldown", "NotWritten"};
    /** Entity key outcomes (PersistentEntitySectionManager.autoSave's per-chunk step). */
    public static final int ENTITY_STORED = 0;
    public static final int ENTITY_UNLOADED = 1;   // hidden chunk: stored and unloaded
    public static final int ENTITY_PENDING = 2;    // not stored: its load is in flight, or a fresh chunk with entities (load requested)
    private static final String[] ENTITY_OUTCOMES = {"Stores", "Unloads", "Pending"};
    /** Player outcomes. */
    public static final int PLAYER_SAVED = 0;
    public static final int PLAYER_GONE = 1;       // left meanwhile: saved by the logout
    private static final String[] PLAYER_OUTCOMES = {"Saves", "Gone"};

    /** Server thread: inside an autosave that spreads. */
    private static boolean recording;
    private static final LongAdder OFF_THREAD = new LongAdder();
    // server thread
    private static final long[] CHUNK_COUNTS = new long[CHUNK_OUTCOMES.length];
    private static final long[] ENTITY_COUNTS = new long[ENTITY_OUTCOMES.length];
    private static final long[] PLAYER_COUNTS = new long[PLAYER_OUTCOMES.length];
    private static final int[] BUSY = new int[3];
    private static final long[] KIND_DRAINS = new long[3];
    private static long backlog;
    private static long ticks;
    private static int lead = -1;
    private static long tickSpentNanos;
    private static long maxTickSpreadNanos;
    private static long deferredDrains;
    private static long spreadStartTick = -1;
    private static long lastSpreadTicks;
    private static long maxSpreadTicks;
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
        spreadStartTick = ticks;
    }

    public static void endRecording() {
        recording = false;
        finishedSpread();
    }

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

    /**
     * S9-E15, start of a server tick (server thread): a new tick budget, and the next kind with work left leads. Without
     * this call (it comes from the engine's server tick hook) no kind leads and every queue drains within its own budget.
     */
    public static void tickStart() {
        ticks++;
        if (tickSpentNanos > maxTickSpreadNanos) maxTickSpreadNanos = tickSpentNanos;
        tickSpentNanos = 0L;
        lead = nextLead(lead, BUSY);
    }

    /** The kind after {@code previous} (round robin) that has a busy queue, or -1 if none has. */
    static int nextLead(int previous, int[] busy) {
        for (int i = 1; i <= 3; i++) {
            int kind = Math.floorMod(previous + i, 3);
            if (busy[kind] > 0) return kind;
        }
        return -1;
    }

    /** The time {@code kind}'s queue may use now, or -1 to wait for a later tick (S9-E15). */
    static long budgetNanos(int kind, int lead, int[] busy, long spentNanos) {
        long own = EngineConfig.AUTOSAVE_BUDGET_US * 1000L;
        if (EngineConfig.AUTOSAVE_TICK_BUDGET_US <= 0) return own;
        if (lead >= 0 && kind != lead && busy[lead] > 0) return -1L;
        long left = EngineConfig.AUTOSAVE_TICK_BUDGET_US * 1000L - spentNanos;
        return left <= 0L ? -1L : Math.min(own, left);
    }

    private static void busyChanged(int kind, boolean before, boolean after) {
        if (before != after) BUSY[kind] += after ? 1 : -1;
        finishedSpread();
    }

    /** After the recording: the spread is done when no queue has work left (its length in ticks is recorded). */
    private static void finishedSpread() {
        if (recording || spreadStartTick < 0 || BUSY[CHUNKS] + BUSY[ENTITIES] + BUSY[PLAYERS] != 0) return;
        lastSpreadTicks = ticks - spreadStartTick;
        maxSpreadTicks = Math.max(maxSpreadTicks, lastSpreadTicks);
        spreadStartTick = -1;
    }

    /** ChunkMap.saveAllChunks(false) recorded its holders' keys. */
    public static void recordedChunks(Spread queue, LongCollection keys) {
        backlog -= queue.backlog();
        boolean before = queue.busy();
        if (queue.begin(keys)) overlap++;
        backlog += queue.backlog();
        busyChanged(CHUNKS, before, queue.busy());
        chunkQueues++;
        chunkKeys += keys.size();
        maxBacklog = Math.max(maxBacklog, keys.size());
    }

    /** PersistentEntitySectionManager.autoSave() recorded its chunks' keys. */
    public static void recordedEntities(Spread queue, LongCollection keys) {
        backlog -= queue.backlog();
        boolean before = queue.busy();
        if (queue.begin(keys)) overlap++;
        backlog += queue.backlog();
        busyChanged(ENTITIES, before, queue.busy());
        entityQueues++;
        entityKeys += keys.size();
        maxBacklog = Math.max(maxBacklog, keys.size());
    }

    /** PlayerList.saveAll() recorded the online players. */
    public static <T> void recordedPlayers(Queue<T> queue, T[] players) {
        backlog -= queue.backlog();
        boolean before = queue.busy();
        if (queue.begin(players)) overlap++;
        backlog += queue.backlog();
        busyChanged(PLAYERS, before, queue.busy());
        playerQueues++;
        playerKeys += players.length;
    }

    /** A full save (or a non-spreading autosave) covers the chunk queue. */
    public static void fullSaveChunks(Spread queue) { fullSave(CHUNKS, queue); }

    /** A full save (or a non-spreading autosave) covers the entity queue. */
    public static void fullSaveEntities(Spread queue) { fullSave(ENTITIES, queue); }

    private static void fullSave(int kind, Spread queue) {
        backlog -= queue.backlog();
        boolean before = queue.busy();
        if (queue.drop()) dropped++;
        busyChanged(kind, before, false);
    }

    public static void fullSave(Queue<?> queue) {
        backlog -= queue.backlog();
        boolean before = queue.busy();
        if (queue.drop()) dropped++;
        busyChanged(PLAYERS, before, false);
    }

    /** One chunk key's outcome (CHUNK_*), from the chunk drain; true if it saved. */
    public static boolean chunkOutcome(int outcome) {
        CHUNK_COUNTS[outcome]++;
        return outcome == CHUNK_SAVED;
    }

    /** One entity key's outcome (ENTITY_*), from the entity drain; true if it stored (or unloaded). */
    public static boolean entityOutcome(int outcome) {
        ENTITY_COUNTS[outcome]++;
        return outcome != ENTITY_PENDING;
    }

    public static <T> void drainPlayers(Queue<T> queue, Predicate<T> save) {
        long budget = budgetNanos(PLAYERS, lead, BUSY, tickSpentNanos);
        if (budget < 0L) {
            deferredDrains++;
            return;
        }
        long start = EngineTimers.start();
        long t0 = System.nanoTime();
        int before = queue.backlog();
        playerSaves += queue.drain(uuid -> {
            boolean saved = save.test(uuid);
            PLAYER_COUNTS[saved ? PLAYER_SAVED : PLAYER_GONE]++;
            return saved;
        }, EngineConfig.AUTOSAVE_CHUNKS, budget, System::nanoTime);
        backlog -= before - queue.backlog();
        drained(PLAYERS, before > 0, queue.busy(), System.nanoTime() - t0, start);
    }

    public static void drainChunks(Spread queue, LongPredicate save) {
        long budget = budgetNanos(CHUNKS, lead, BUSY, tickSpentNanos);
        if (budget < 0L) {
            deferredDrains++;
            return;
        }
        chunkSaves += drain(CHUNKS, queue, save, budget);
    }

    public static void drainEntities(Spread queue, LongPredicate store) {
        long budget = budgetNanos(ENTITIES, lead, BUSY, tickSpentNanos);
        if (budget < 0L) {
            deferredDrains++;
            return;
        }
        entityStores += drain(ENTITIES, queue, store, budget);
    }

    private static int drain(int kind, Spread queue, LongPredicate save, long budget) {
        long start = EngineTimers.start();
        long t0 = System.nanoTime();
        int before = queue.backlog();
        int done = queue.drain(save, EngineConfig.AUTOSAVE_CHUNKS, budget, System::nanoTime);
        backlog -= before - queue.backlog();
        drained(kind, before > 0, queue.busy(), System.nanoTime() - t0, start);
        return done;
    }

    private static void drained(int kind, boolean wasBusy, boolean busy, long took, long timerStart) {
        drains++;
        KIND_DRAINS[kind]++;
        tickSpentNanos += took;
        maxDrainNanos = Math.max(maxDrainNanos, took);
        busyChanged(kind, wasBusy, busy);
        EngineTimers.add(EngineTimers.AUTOSAVE_SPREAD, timerStart);
    }

    static String counters() {
        StringBuilder out = new StringBuilder();
        out.append("autosaveSpreads=").append(spreads).append(" autosaveChunkQueues=").append(chunkQueues)
            .append(" autosaveChunkKeys=").append(chunkKeys).append(" autosaveChunkSaves=").append(chunkSaves)
            .append(" autosaveEntityQueues=").append(entityQueues).append(" autosaveEntityKeys=").append(entityKeys)
            .append(" autosaveEntityStores=").append(entityStores).append(" autosavePlayerQueues=").append(playerQueues)
            .append(" autosavePlayerKeys=").append(playerKeys).append(" autosavePlayerSaves=").append(playerSaves)
            .append(" autosaveDrains=").append(drains).append(" autosaveMaxBacklog=").append(maxBacklog)
            .append(" autosaveMaxDrainUs=").append(maxDrainNanos / 1000).append(" autosaveDropped=").append(dropped)
            .append(" autosaveOverlap=").append(overlap).append(" autosaveOffThread=").append(OFF_THREAD.sum());
        // S9-E15: outcomes per key (chunk: keys = sum of Chunk*; entity: keys = Stores + Unloads + Pending; player: keys = Saves + Gone)
        for (int i = 1; i < CHUNK_OUTCOMES.length; i++) out.append(" autosaveChunk").append(CHUNK_OUTCOMES[i]).append('=').append(CHUNK_COUNTS[i]);
        out.append(" autosaveEntityStored=").append(ENTITY_COUNTS[ENTITY_STORED]).append(" autosaveEntityUnloads=")
            .append(ENTITY_COUNTS[ENTITY_UNLOADED]).append(" autosaveEntityPending=").append(ENTITY_COUNTS[ENTITY_PENDING])
            .append(" autosavePlayerGone=").append(PLAYER_COUNTS[PLAYER_GONE]);
        for (int k = 0; k < 3; k++) out.append(" autosave").append(KIND_NAMES[k]).append("Drains=").append(KIND_DRAINS[k]);
        out.append(" autosaveBacklog=").append(backlog).append(" autosaveDeferredDrains=").append(deferredDrains)
            .append(" autosaveMaxTickSpreadUs=").append(Math.max(maxTickSpreadNanos, tickSpentNanos) / 1000)
            .append(" autosaveLastSpreadTicks=").append(lastSpreadTicks).append(" autosaveMaxSpreadTicks=").append(maxSpreadTicks);
        return out.toString();
    }
}
