package dev.phoenium.engine;

import dev.phoenium.engine.mixin.EngineTrackedEntityAccess;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.objects.Reference2ObjectMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.ToIntFunction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * S9-E6 (engine mode; {@link EngineConfig#TRACKER_INDEX}): ChunkMap.move(player) and the moved-players pass of
 * ChunkMap.tick() visit only the tracked entities the S9-E4 filter lets through, instead of every tracked entity of the
 * level. The filter ({@link EngineTracker#mayChange}) passes a tracked entity for a player when its chunk is within the
 * player's view distance + 1 chunks on both axes, or when the player sees it now (its seenBy has the player's
 * connection); for any other one the per-player check cannot change anything. The index finds exactly that set: the
 * entities of the tiles that overlap the player's box, each checked by its chunk, and a reverse index of every seenBy.
 * So the visits it leaves out are exactly the checks the filter skips, and every visit runs the same checks as before
 * (in another order; vanilla's is the entity map's hash order, which nothing depends on).
 *
 * <p>The index follows ChunkMap.addEntity / removeEntity (the entity map's put and remove), each section change of an
 * entity (PersistentEntitySectionManager.Callback.onMove after the new section key is stored; a tile's moves are
 * applied on the server thread after its colour) and each seenBy add or remove (TrackedEntity.updatePlayer /
 * removePlayer, the only places vanilla changes seenBy). It answers only on the server thread with no tile or colour
 * running, so with every section move applied; anywhere else the vanilla loop runs (trackerIndexFallbacks). A change
 * from another thread marks it for a rebuild (trackerIndexOffThread). Every {@link EngineConfig#TRACKER_INDEX_CHECK}
 * ticks the head of ChunkMap.tick() compares it with the entity map (every {@link EngineConfig#TRACKER_INDEX_FULL}-th
 * check also member by member with every seenBy); a difference is counted (trackerIndexStale, trackerReverseStale),
 * logged and repaired by a rebuild. Both counts stay 0 when the index is exact.
 */
public final class EngineTrackerIndex {
    private static final Logger LOGGER = LoggerFactory.getLogger("Phoenium");
    private static final AtomicInteger STALE_LOGS = new AtomicInteger();
    private static final LongAdder OFF_THREAD = new LongAdder();
    private static final LongAdder FALLBACKS = new LongAdder();
    // server thread
    private static long moves;
    private static long passes;
    private static long visited;
    private static long avoided;
    private static long scanned;
    private static long checks;
    private static long rebuilds;
    private static long stale;
    private static long reverseStale;

    /** A ChunkMap with its index (the engine's ChunkMap tracker mixin). */
    public interface Holder {
        /** This ChunkMap's index, created on first use. */
        EngineTrackerIndex phoenium$trackerIndex();

        /** This ChunkMap's index, or null before its first use. */
        EngineTrackerIndex phoenium$trackerIndexIfPresent();
    }

    /** A ChunkMap.TrackedEntity with its entry (the engine's TrackedEntity mixin). */
    public interface Indexed {
        TrackerEntry phoenium$indexEntry();

        void phoenium$setIndexEntry(TrackerEntry entry);
    }

    /** An indexed entity: its tile bucket and slot, its visit stamp, and what the index reads of it. */
    abstract static class Entry {
        /** What a visit hands out (the ChunkMap.TrackedEntity). */
        final Object item;
        long tile;
        Bucket bucket;
        int slot;
        int stamp;
        boolean removed;

        Entry(Object item) { this.item = item; }

        abstract int chunkX();

        abstract int chunkZ();

        /** The connections that see it now (the tracker's seenBy). */
        abstract Set<?> seenBy();
    }

    /** A ChunkMap.TrackedEntity's entry. */
    public static final class TrackerEntry extends Entry {
        final EngineTrackerIndex index;
        final Entity entity;
        private final Set<?> seenBy;

        TrackerEntry(EngineTrackerIndex index, Object tracker, Entity entity, Set<?> seenBy) {
            super(tracker);
            this.index = index;
            this.entity = entity;
            this.seenBy = seenBy;
        }

        @Override int chunkX() { return this.entity.chunkPosition().x(); }

        @Override int chunkZ() { return this.entity.chunkPosition().z(); }

        @Override Set<?> seenBy() { return this.seenBy; }

        /** TrackedEntity.updatePlayer added {@code connection} to seenBy. */
        public void seen(Object connection) { this.index.seen(this, connection); }

        /** TrackedEntity.removePlayer removed {@code connection} from seenBy. */
        public void unseen(Object connection) { this.index.unseen(this, connection); }
    }

    static final class Bucket {
        Entry[] entries = new Entry[8];
        int size;
    }

    /** One loop's visits: the vanilla loop's iterator, reusable once the loop has reached its end. */
    static final class Candidates implements ObjectIterator<Object> {
        Object[] items = new Object[64];
        int size;
        private int next;
        boolean busy;

        void add(Object item) {
            if (this.size == this.items.length) this.items = Arrays.copyOf(this.items, this.size * 2);
            this.items[this.size++] = item;
        }

        @Override
        public boolean hasNext() {
            if (this.next < this.size) return true;
            this.busy = false;
            return false;
        }

        @Override
        public Object next() {
            if (this.next >= this.size) throw new NoSuchElementException();
            return this.items[this.next++];
        }
    }

    private final Thread thread;
    private final int shift;
    private final Int2ObjectMap<?> trackers;
    private final ToIntFunction<ServerPlayer> viewDistance;
    private final Long2ObjectOpenHashMap<Bucket> tiles = new Long2ObjectOpenHashMap<>();
    private final Reference2ObjectOpenHashMap<Object, ReferenceOpenHashSet<Entry>> seen = new Reference2ObjectOpenHashMap<>();
    private final Candidates shared = new Candidates();
    private int size;
    private long seenTotal;
    private int stamp;
    private int ticks;
    private volatile boolean dirty;

    /** {@code trackers} is the ChunkMap's entity map; {@code viewDistance} its getPlayerViewDistance. */
    public EngineTrackerIndex(Thread thread, Int2ObjectMap<?> trackers, ToIntFunction<ServerPlayer> viewDistance) {
        this(thread, EngineConfig.TILE_SHIFT, trackers, viewDistance);
    }

    EngineTrackerIndex(Thread thread, int shift, Int2ObjectMap<?> trackers, ToIntFunction<ServerPlayer> viewDistance) {
        this.thread = thread;
        this.shift = shift;
        this.trackers = trackers;
        this.viewDistance = viewDistance;
    }

    // ---- what the hooks report ----

    /** ChunkMap.addEntity put {@code tracker} into the entity map. */
    public void added(Object tracker) {
        if (!onThread() || this.dirty) return;
        index(tracker);
    }

    /** ChunkMap.removeEntity took {@code tracker} out of the entity map. */
    public void removed(Object tracker) {
        if (!onThread() || this.dirty) return;
        TrackerEntry entry = ((Indexed) tracker).phoenium$indexEntry();
        if (entry != null && entry.index == this && !entry.removed) remove(entry);
    }

    /** The entity's section change was applied (Callback.onMove, after the new section key is stored). */
    public static void sectionMoved(Entity entity) {
        if (!(entity.level() instanceof ServerLevel level)) return;
        EngineTrackerIndex index = ((Holder) level.getChunkSource().chunkMap).phoenium$trackerIndexIfPresent();
        if (index != null) index.moved(entity);
    }

    private void moved(Entity entity) {
        if (!onThread() || this.dirty) return;
        Object tracker = this.trackers.get(entity.getId());
        if (tracker == null) return;
        TrackerEntry entry = ((Indexed) tracker).phoenium$indexEntry();
        if (entry != null && entry.index == this && !entry.removed && entry.entity == entity) relocate(entry);
    }

    void seen(Entry entry, Object connection) {
        if (entry.removed || !onThread() || this.dirty) return;
        addSeen(entry, connection);
    }

    void unseen(Entry entry, Object connection) {
        if (entry.removed || !onThread() || this.dirty) return;
        removeSeen(entry, connection);
    }

    private boolean onThread() {
        if (Thread.currentThread() == this.thread) return true;
        OFF_THREAD.increment();
        this.dirty = true;
        return false;
    }

    // ---- what the loops ask ----

    /** ChunkMap.move(player): the trackers its loop visits (the player's own first), or null: the vanilla loop runs. */
    public ObjectIterator<Object> forMove(ServerPlayer player) {
        if (!ready()) return null;
        Candidates out = candidates();
        int stamp = nextStamp();
        Object own = this.trackers.get(player.getId());
        if (own != null && ((EngineTrackedEntityAccess) own).phoenium$entity() == player) {
            TrackerEntry entry = ((Indexed) own).phoenium$indexEntry();
            if (entry != null) entry.stamp = stamp;
            out.add(own);
        }
        gather(player, stamp, out);
        moves++;
        count(out);
        return out;
    }

    /**
     * ChunkMap.tick()'s pass over the trackers for the players that changed section: the trackers that pass can change,
     * or null: the vanilla pass runs.
     */
    public ObjectIterator<Object> forMoved(List<ServerPlayer> moved) {
        if (!ready()) return null;
        Candidates out = candidates();
        int stamp = nextStamp();
        for (int i = 0, n = moved.size(); i < n; i++) gather(moved.get(i), stamp, out);
        passes++;
        count(out);
        return out;
    }

    private void gather(ServerPlayer player, int stamp, Candidates out) {
        ChunkPos at = player.chunkPosition();
        collect(at.x(), at.z(), this.viewDistance.applyAsInt(player) + 1, player.connection, stamp, out);
    }

    private void count(Candidates out) {
        visited += out.size;
        avoided += Math.max(0, this.trackers.size() - out.size);
    }

    /** Server thread, no tile and no colour running (every section move applied); rebuilt first when marked. */
    private boolean ready() {
        if (Thread.currentThread() != this.thread || TileEngine.inColour() || TileEngine.current() != null) {
            FALLBACKS.increment();
            return false;
        }
        if (this.dirty) rebuild();
        return true;
    }

    private Candidates candidates() {
        Candidates out = this.shared.busy ? new Candidates() : this.shared;
        out.busy = true;
        out.size = 0;
        out.next = 0;
        return out;
    }

    /** ChunkMap.tick() head: the periodic comparison with the entity map. */
    public void tick() {
        this.shared.busy = false;   // a loop that threw before its end
        if (EngineConfig.TRACKER_INDEX_CHECK == 0 || ++this.ticks < EngineConfig.TRACKER_INDEX_CHECK) return;
        this.ticks = 0;
        if (Thread.currentThread() != this.thread || TileEngine.inColour() || TileEngine.current() != null) return;
        if (this.dirty) rebuild();
        checks++;
        boolean full = checks % EngineConfig.TRACKER_INDEX_FULL == 0;
        int entryErrors = 0;
        int reverseErrors = 0;
        long seenSum = 0;
        int live = 0;
        for (Object tracker : this.trackers.values()) {
            live++;
            TrackerEntry entry = ((Indexed) tracker).phoenium$indexEntry();
            if (entry == null || entry.index != this || entry.removed || entry.item != tracker) {
                entryErrors++;
                noteStale("unindexed", String.valueOf(((EngineTrackedEntityAccess) tracker).phoenium$entity()));
                continue;
            }
            String problem = placement(entry);
            if (problem != null) {
                entryErrors++;
                noteStale(problem, String.valueOf(entry.entity));
            }
            seenSum += entry.seenBy().size();
            if (full) reverseErrors += reverseMisses(entry);
        }
        if (live != this.size) {
            entryErrors++;
            noteStale("entries " + this.size + " for " + live + " trackers", "-");
        }
        if (seenSum != this.seenTotal) {
            reverseErrors++;
            noteStale("reverse " + this.seenTotal + " for " + seenSum + " seenBy members", "-");
        }
        if (full) reverseErrors += reverseExtras();
        if (entryErrors + reverseErrors == 0) return;
        stale += entryErrors;
        reverseStale += reverseErrors;
        rebuild();
    }

    private static void noteStale(String problem, String entity) {
        if (STALE_LOGS.incrementAndGet() <= 10) LOGGER.warn("PHOENIUM_ENGINE trackerIndex stale: {} entity={}", problem, entity);
    }

    private void rebuild() {
        this.dirty = false;
        for (Bucket bucket : this.tiles.values()) {
            for (int i = 0; i < bucket.size; i++) bucket.entries[i].removed = true;
        }
        this.tiles.clear();
        this.seen.clear();
        this.size = 0;
        this.seenTotal = 0;
        for (Object tracker : this.trackers.values()) index(tracker);
        rebuilds++;
    }

    private void index(Object tracker) {
        Indexed indexed = (Indexed) tracker;
        TrackerEntry old = indexed.phoenium$indexEntry();
        if (old != null && old.index == this && !old.removed) remove(old);
        EngineTrackedEntityAccess access = (EngineTrackedEntityAccess) tracker;
        TrackerEntry entry = new TrackerEntry(this, tracker, access.phoenium$entity(), access.phoenium$seenBy());
        indexed.phoenium$setIndexEntry(entry);
        add(entry);
    }

    // ---- the structure (no game classes below) ----

    void add(Entry entry) {
        bucket(entry, tileOf(entry));
        this.size++;
        for (Object connection : entry.seenBy()) addSeen(entry, connection);
    }

    void remove(Entry entry) {
        unbucket(entry);
        this.size--;
        for (Object connection : entry.seenBy()) removeSeen(entry, connection);
        entry.removed = true;
    }

    /** The entry's chunk may have changed: it moves to its tile's bucket. */
    void relocate(Entry entry) {
        long tile = tileOf(entry);
        if (tile == entry.tile) return;
        unbucket(entry);
        bucket(entry, tile);
    }

    /**
     * Adds to {@code out} the item of each entry whose chunk is within {@code reach} chunks of (chunkX, chunkZ) on both
     * axes, then of each entry {@code connection} sees; each entry at most once per {@code stamp}.
     */
    void collect(int chunkX, int chunkZ, int reach, Object connection, int stamp, Candidates out) {
        int minX = chunkX - reach;
        int maxX = chunkX + reach;
        int minZ = chunkZ - reach;
        int maxZ = chunkZ + reach;
        int lastTileX = maxX >> this.shift;
        int lastTileZ = maxZ >> this.shift;
        long examined = 0;
        for (int tileX = minX >> this.shift; tileX <= lastTileX; tileX++) {
            for (int tileZ = minZ >> this.shift; tileZ <= lastTileZ; tileZ++) {
                Bucket bucket = this.tiles.get(TileGeometry.key(tileX, tileZ));
                if (bucket == null) continue;
                Entry[] entries = bucket.entries;
                int n = bucket.size;
                examined += n;
                for (int i = 0; i < n; i++) {
                    Entry entry = entries[i];
                    if (entry.stamp == stamp) continue;
                    int x = entry.chunkX();
                    int z = entry.chunkZ();
                    if (x < minX || x > maxX || z < minZ || z > maxZ) continue;
                    entry.stamp = stamp;
                    out.add(entry.item);
                }
            }
        }
        scanned += examined;
        ReferenceOpenHashSet<Entry> sees = this.seen.get(connection);
        if (sees == null) return;
        for (Entry entry : sees) {
            if (entry.stamp == stamp || entry.removed) continue;
            entry.stamp = stamp;
            out.add(entry.item);
        }
    }

    int nextStamp() {
        if (++this.stamp == 0) {   // wrapped: no entry may keep a current stamp
            for (Bucket bucket : this.tiles.values()) {
                for (int i = 0; i < bucket.size; i++) bucket.entries[i].stamp = 0;
            }
            this.stamp = 1;
        }
        return this.stamp;
    }

    /** Null when the entry sits in its chunk's tile bucket at its slot, else what is wrong. */
    String placement(Entry entry) {
        Bucket bucket = entry.bucket;
        if (bucket == null || this.tiles.get(entry.tile) != bucket || entry.slot >= bucket.size || bucket.entries[entry.slot] != entry)
            return "bucket slot " + entry.slot;
        long tile = tileOf(entry);
        if (tile != entry.tile) {
            return "tile " + TileGeometry.keyX(entry.tile) + "," + TileGeometry.keyZ(entry.tile) + " for chunk " + entry.chunkX() + ","
                + entry.chunkZ();
        }
        return null;
    }

    /** seenBy members of the entry missing from the reverse index. */
    int reverseMisses(Entry entry) {
        int misses = 0;
        for (Object connection : entry.seenBy()) {
            ReferenceOpenHashSet<Entry> set = this.seen.get(connection);
            if (set == null || !set.contains(entry)) misses++;
        }
        return misses;
    }

    /** Reverse index members that are not (or no longer) in their entry's seenBy. */
    int reverseExtras() {
        int extras = 0;
        for (Reference2ObjectMap.Entry<Object, ReferenceOpenHashSet<Entry>> pair : this.seen.reference2ObjectEntrySet()) {
            for (Entry entry : pair.getValue()) {
                if (entry.removed || !entry.seenBy().contains(pair.getKey())) extras++;
            }
        }
        return extras;
    }

    int size() { return this.size; }

    long seenTotal() { return this.seenTotal; }

    void stampForTest(int value) { this.stamp = value; }

    private long tileOf(Entry entry) { return TileGeometry.key(entry.chunkX() >> this.shift, entry.chunkZ() >> this.shift); }

    private void bucket(Entry entry, long tile) {
        Bucket bucket = this.tiles.get(tile);
        if (bucket == null) this.tiles.put(tile, bucket = new Bucket());
        if (bucket.size == bucket.entries.length) bucket.entries = Arrays.copyOf(bucket.entries, bucket.size * 2);
        entry.tile = tile;
        entry.bucket = bucket;
        entry.slot = bucket.size;
        bucket.entries[bucket.size++] = entry;
    }

    private void unbucket(Entry entry) {
        Bucket bucket = entry.bucket;
        int last = --bucket.size;
        Entry moved = bucket.entries[last];
        bucket.entries[entry.slot] = moved;
        moved.slot = entry.slot;
        bucket.entries[last] = null;
        if (last == 0) this.tiles.remove(entry.tile);
        entry.bucket = null;
    }

    private void addSeen(Entry entry, Object connection) {
        ReferenceOpenHashSet<Entry> set = this.seen.get(connection);
        if (set == null) this.seen.put(connection, set = new ReferenceOpenHashSet<>());
        if (set.add(entry)) this.seenTotal++;
    }

    private void removeSeen(Entry entry, Object connection) {
        ReferenceOpenHashSet<Entry> set = this.seen.get(connection);
        if (set == null || !set.remove(entry)) return;
        this.seenTotal--;
        if (set.isEmpty()) this.seen.remove(connection);
    }

    static String counters() {
        return "trackerIndexMoves=" + moves + " trackerIndexPasses=" + passes + " trackerIndexVisited=" + visited
            + " trackerIndexAvoided=" + avoided + " trackerIndexScanned=" + scanned + " trackerIndexFallbacks=" + FALLBACKS.sum()
            + " trackerIndexOffThread=" + OFF_THREAD.sum() + " trackerIndexChecks=" + checks + " trackerIndexRebuilds=" + rebuilds
            + " trackerIndexStale=" + stale + " trackerReverseStale=" + reverseStale;
    }
}
