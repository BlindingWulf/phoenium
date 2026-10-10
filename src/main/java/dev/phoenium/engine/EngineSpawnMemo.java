package dev.phoenium.engine;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.phoenium.engine.mixin.EngineLocalMobCapAccess;
import dev.phoenium.engine.mixin.EngineMobCountsAccess;
import dev.phoenium.engine.mixin.EnginePointChargeAccess;
import dev.phoenium.engine.mixin.EnginePotentialAccess;
import dev.phoenium.engine.mixin.EngineSpawnStateAccess;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LocalMobCapCalculator;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.chunk.LevelChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * S9-E7 (engine mode; {@link EngineConfig#SPAWN_MEMO}): NaturalSpawner.createState with each chunk looked up once per
 * call. Vanilla queries the chunk of every counted entity through ServerChunkCache.getFullChunk (holder map, full-chunk
 * future) and, for every mob, LocalMobCapCalculator.addMob finds the players near that chunk and each player's counts in
 * two hash maps. Here the first entity of a chunk takes exactly that path; the loaded chunk is remembered for the rest of
 * the call, with the count maps its mobs increment, so the other entities of the chunk cost one lookup. Only loaded
 * lookups are remembered: a chunk that was not ready is queried again, as vanilla does, and a loaded chunk stays loaded
 * while the server thread builds the state (unloads and ticket changes run on the server thread). The entities, their
 * order, the charges and every count are vanilla's: createState itself still runs, with this as its ChunkGetter and
 * {@link Caps} as its LocalMobCapCalculator.
 *
 * <p>Every {@link EngineConfig#SPAWN_MEMO_CHECK}-th state of a level is also built the vanilla way and compared field by
 * field (spawnMemoChecks); a difference is counted (spawnMemoStale), logged, and the vanilla state is used.
 *
 * <p>S9-E9 ({@link EngineConfig#SPAWN_SLICES}): with enough entities, createState runs once per contiguous slice of the
 * entity list, on the tile threads, each slice with its own memo and Caps; the slices' states are merged in slice order
 * into the first: charges concatenated (the serial pass's list, so every potential sum is vanilla's), category counts
 * and each player's local counts summed, players-near lists united (each chunk's list is computed from the same state).
 * Per entity a slice reads only the entity, its chunk's biome and the chunk map; the players-near lookups first run the
 * natural-spawn distance tracker's pending updates, a write, so the server thread runs them before the slices start and
 * the slices' lookups only read. The check above compares the merged state with the vanilla one.
 *
 * <p>S9-E15: a check pins the chunk availability both builds read ({@link Pinned}). With C2ME's chunk system the full-chunk
 * futures are published and demoted by its scheduler threads, so a chunk can become loaded or stop being loaded while
 * the server thread builds a state; D33's check rebuilt the vanilla state a moment after the memo's against the live
 * availability, and the one s19b difference (CREATURE 138 vs 137 in the join storm) is what a chunk demoted in between
 * gives. Pinned, the two builds see the same chunks, so spawnMemoStale counts only differences of the memo's own
 * counting (must stay 0); spawnMemoPinMisses (a query outside the pinned set) must stay 0 too. Informational:
 * spawnMemoSmeared/SmearedChunks (checks whose pinned chunks changed availability by the end of the check) and
 * spawnMemoLiveDiffs (the memo state against a vanilla state on the live availability, D33's comparison). Vanilla's own
 * state reads that availability entity by entity, so under C2ME it is no single instant either.
 */
public final class EngineSpawnMemo implements NaturalSpawner.ChunkGetter {
    private static final Logger LOGGER = LoggerFactory.getLogger("Phoenium");
    @SuppressWarnings("unchecked")
    private static final Object2IntOpenHashMap<MobCategory>[] VANILLA = new Object2IntOpenHashMap[0];
    // server thread
    private static long calls;
    private static long totalQueries;
    private static long totalHits;
    private static long totalChunks;
    private static long totalFastMobs;
    private static long checks;
    private static long stale;
    private static long sliceStates;
    private static long sliceCount;
    private static long sliceWorkNanos;
    private static long pinMisses;
    private static long smeared;
    private static long smearedChunks;
    private static long liveDiffs;

    /** A level's memo settings (the engine's ServerChunkCache mixin keeps one per level). */
    public static final class Level {
        int states;
        int expected = 16;
        /** S9-E9: the entities counted by the last state, and the list a sliced state is counted from. */
        int lastEntities;
        final ArrayList<Entity> entities = new ArrayList<>();
    }

    /** A chunk looked up in this call: the loaded chunk, its ChunkPos (the one its mobs are counted at), its count maps. */
    static final class Chunk {
        final LevelChunk level;
        final ChunkPos pos;
        Object2IntOpenHashMap<MobCategory>[] counts;

        Chunk(LevelChunk level) {
            this.level = level;
            this.pos = level.getPos();
        }
    }

    /**
     * The call's LocalMobCapCalculator. While createState counts the entities of a remembered chunk, addMob at that chunk's
     * ChunkPos increments the count maps the vanilla method would find (the players near the chunk, in their order, and
     * each one's counts); the first mob of the chunk goes through the vanilla method, which creates them. Any other call
     * (another position, a later afterSpawn, another thread) is the vanilla method.
     */
    public static final class Caps extends LocalMobCapCalculator {
        private final Thread thread;
        Chunk current;
        long fastMobs;

        Caps(ChunkMap chunkMap, Thread thread) {
            super(chunkMap);
            this.thread = thread;
        }

        @Override
        public void addMob(ChunkPos pos, MobCategory category) {
            Chunk chunk = this.current;
            if (chunk == null || pos != chunk.pos || chunk.counts == VANILLA || Thread.currentThread() != this.thread) {
                super.addMob(pos, category);
                return;
            }
            Object2IntOpenHashMap<MobCategory>[] counts = chunk.counts;
            if (counts == null) {
                super.addMob(pos, category);
                chunk.counts = resolve(pos);
                return;
            }
            // MobCounts.add: counts.computeInt(category, (k, v) -> v == null ? 1 : v + 1); addTo with a default of 0 is the same
            for (Object2IntOpenHashMap<MobCategory> playerCounts : counts) playerCounts.addTo(category, 1);
            this.fastMobs++;
        }

        /** The count maps addMob(pos, ...) increments, after the vanilla addMob created them; VANILLA if not resolvable. */
        @SuppressWarnings("unchecked")
        private Object2IntOpenHashMap<MobCategory>[] resolve(ChunkPos pos) {
            EngineLocalMobCapAccess access = (EngineLocalMobCapAccess) (Object) this;
            List<ServerPlayer> near = access.phoenium$playersNear().get(pos.pack());
            if (near == null) return VANILLA;
            Map<ServerPlayer, ?> perPlayer = access.phoenium$playerMobCounts();
            Object2IntOpenHashMap<MobCategory>[] out = new Object2IntOpenHashMap[near.size()];
            for (int i = 0; i < out.length; i++) {
                Object mobCounts = perPlayer.get(near.get(i));
                if (mobCounts == null) return VANILLA;
                Object2IntMap<MobCategory> map = ((EngineMobCountsAccess) mobCounts).phoenium$counts();
                if (!(map instanceof Object2IntOpenHashMap<MobCategory> open) || open.defaultReturnValue() != 0) return VANILLA;
                out[i] = open;
            }
            return out;
        }
    }

    /**
     * S9-E15: a check's chunk availability, pinned: every chunk the counted entities query (createState's own filter and
     * key) is asked of the live getter once, on the server thread, before the builds; both builds then read this (read
     * only, so the slices may share it). A query outside the set goes to the live getter and is counted.
     */
    static final class Pinned implements NaturalSpawner.ChunkGetter {
        private final NaturalSpawner.ChunkGetter live;
        private final Long2ObjectOpenHashMap<LevelChunk> loaded = new Long2ObjectOpenHashMap<>();
        private final LongOpenHashSet absent = new LongOpenHashSet();
        final LongAdder misses = new LongAdder();

        Pinned(NaturalSpawner.ChunkGetter live, List<Entity> entities) {
            this.live = live;
            LevelChunk[] found = new LevelChunk[1];
            for (Entity entity : entities) {
                if (entity instanceof Mob mob && (mob.isPersistenceRequired() || mob.requiresCustomPersistence())) continue;
                if (entity.getType().getCategory() == MobCategory.MISC) continue;
                long key = ChunkPos.pack(entity.blockPosition());
                if (this.loaded.containsKey(key) || this.absent.contains(key)) continue;
                found[0] = null;
                live.query(key, chunk -> found[0] = chunk);
                if (found[0] != null) this.loaded.put(key, found[0]);
                else this.absent.add(key);
            }
        }

        @Override
        public void query(long pos, Consumer<LevelChunk> consumer) {
            LevelChunk chunk = this.loaded.get(pos);
            if (chunk != null) {
                consumer.accept(chunk);
                return;
            }
            if (this.absent.contains(pos)) return;
            this.misses.increment();
            this.live.query(pos, consumer);
        }

        /** Server thread: the pinned chunks whose live availability (or chunk object) is now different. */
        int changed() {
            int changed = 0;
            LevelChunk[] found = new LevelChunk[1];
            for (Long2ObjectMap.Entry<LevelChunk> entry : this.loaded.long2ObjectEntrySet()) {
                found[0] = null;
                this.live.query(entry.getLongKey(), chunk -> found[0] = chunk);
                if (found[0] != entry.getValue()) changed++;
            }
            for (long key : this.absent) {
                found[0] = null;
                this.live.query(key, chunk -> found[0] = chunk);
                if (found[0] != null) changed++;
            }
            return changed;
        }
    }

    private final NaturalSpawner.ChunkGetter getter;
    private final Caps caps;
    private final Long2ObjectOpenHashMap<Chunk> chunks;
    private LevelChunk found;
    private final Consumer<LevelChunk> sink = chunk -> this.found = chunk;
    // the thread building this memo's state
    private long queries;
    private long hits;

    private EngineSpawnMemo(NaturalSpawner.ChunkGetter getter, Caps caps, int expected) {
        this.getter = getter;
        this.caps = caps;
        this.chunks = new Long2ObjectOpenHashMap<>(expected);
    }

    @Override
    public void query(long pos, Consumer<LevelChunk> consumer) {
        queries++;
        Chunk chunk = this.chunks.get(pos);
        if (chunk == null) {
            this.getter.query(pos, this.sink);
            LevelChunk loaded = this.found;
            this.found = null;
            if (loaded == null) return; // not loaded: vanilla's getter did not call the consumer either
            chunk = new Chunk(loaded);
            this.chunks.put(pos, chunk);
        } else {
            hits++;
        }
        this.caps.current = chunk;
        try {
            consumer.accept(chunk.level);
        } finally {
            this.caps.current = null;
        }
    }

    /** The wrapped NaturalSpawner.createState call of ServerChunkCache.tickChunks (server thread). */
    public static NaturalSpawner.SpawnState createState(Level level, int spawnableChunks, Iterable<Entity> entities,
                                                        NaturalSpawner.ChunkGetter getter, LocalMobCapCalculator vanillaCaps,
                                                        Operation<NaturalSpawner.SpawnState> original) {
        ChunkMap chunkMap = ((EngineLocalMobCapAccess) vanillaCaps).phoenium$chunkMap();
        Iterable<Entity> counted = entities;
        ArrayList<Entity> list = level.entities;
        boolean check = EngineConfig.SPAWN_MEMO_CHECK > 0 && ++level.states % EngineConfig.SPAWN_MEMO_CHECK == 0;
        try {
            NaturalSpawner.ChunkGetter source = getter;
            Pinned pinned = null;
            if (check) {
                // S9-E15: one entity list and one chunk availability for the memo's build and the reference
                for (Entity entity : entities) list.add(entity);
                counted = list;
                pinned = new Pinned(getter, list);
                source = pinned;
            }
            NaturalSpawner.SpawnState state = null;
            boolean slicing = false;
            // S9-E9: slice when the last state counted enough entities for two slices
            if (EngineConfig.SPAWN_SLICES && level.lastEntities >= 2 * EngineConfig.SPAWN_SLICE_MIN && TileEngine.canRunParallel()) {
                if (counted != list) {
                    for (Entity entity : entities) list.add(entity);
                    counted = list;
                }
                slicing = true;
                level.lastEntities = list.size();
                state = sliced(level, chunkMap, spawnableChunks, list, source, original);
            }
            if (state == null) {
                Caps caps = new Caps(chunkMap, Thread.currentThread());
                EngineSpawnMemo memo = new EngineSpawnMemo(source, caps, level.expected);
                state = original.call(spawnableChunks, counted, memo, caps);
                memo.addCounters();
                level.expected = Math.max(16, memo.chunks.size());
                if (!slicing) level.lastEntities = (int) Math.min(Integer.MAX_VALUE, memo.queries);
            }
            calls++;
            if (check) {
                NaturalSpawner.SpawnState reference = original.call(spawnableChunks, counted, pinned, new LocalMobCapCalculator(chunkMap));
                checks++;
                pinMisses += pinned.misses.sum();
                String difference = difference(state, reference);
                // informational: D33's comparison (live availability, a moment later), and how much of it moved meanwhile
                String liveDifference = EngineConfig.SPAWN_MEMO_LIVE_CHECK
                    ? difference(state, original.call(spawnableChunks, counted, getter, new LocalMobCapCalculator(chunkMap))) : null;
                int changed = pinned.changed();
                if (changed > 0) {
                    smeared++;
                    smearedChunks += changed;
                }
                if (liveDifference != null) {
                    liveDiffs++;
                    if (liveDiffs <= 10) {
                        LOGGER.info("PHOENIUM_ENGINE spawnMemo live availability moved (informational; pinned check {}): {} changedChunks={}",
                            difference == null ? "equal" : "different", liveDifference, changed);
                    }
                }
                if (difference != null) {
                    stale++;
                    if (stale <= 10) LOGGER.warn("PHOENIUM_ENGINE spawnMemo stale (using the vanilla state): {}", difference);
                    return reference;
                }
            }
            return state;
        } finally {
            list.clear();
        }
    }

    /**
     * S9-E9: the state counted from contiguous slices of {@code list} on the tile threads and merged in slice order, or
     * null (count it on this thread) when the list is too short for two slices or no parallel job can run.
     */
    private static NaturalSpawner.SpawnState sliced(Level level, ChunkMap chunkMap, int spawnableChunks, ArrayList<Entity> list,
                                                    NaturalSpawner.ChunkGetter getter, Operation<NaturalSpawner.SpawnState> original) {
        int count = list.size();
        int threads = TileEngine.parallelThreads();
        int most = Math.min(2 * threads, count / EngineConfig.SPAWN_SLICE_MIN);
        // whole rounds: no thread runs one slice more than the rest
        int slices = most > threads ? most - most % threads : most;
        if (slices < 2) return null;
        // the slices' players-near lookups run this tracker's pending updates first (a write): run them here, so they only read
        chunkMap.getDistanceManager().hasPlayersNearby(0L);
        NaturalSpawner.SpawnState[] states = new NaturalSpawner.SpawnState[slices];
        EngineSpawnMemo[] memos = new EngineSpawnMemo[slices];
        int expected = Math.max(16, level.expected / slices);
        long work = TileEngine.runParallel(slices, 1, slice -> {
            Caps caps = new Caps(chunkMap, Thread.currentThread());
            EngineSpawnMemo memo = new EngineSpawnMemo(getter, caps, expected);
            int from = (int) ((long) slice * count / slices);
            int to = (int) ((long) (slice + 1) * count / slices);
            states[slice] = original.call(spawnableChunks, list.subList(from, to), memo, caps);
            memos[slice] = memo;
        }, false);
        if (work < 0L) return null;
        NaturalSpawner.SpawnState state = states[0];
        int chunks = 0;
        for (int slice = 0; slice < slices; slice++) {
            if (slice > 0) merge(state, states[slice]);
            memos[slice].addCounters();
            chunks += memos[slice].chunks.size();
        }
        level.expected = Math.max(16, chunks);
        sliceStates++;
        sliceCount += slices;
        sliceWorkNanos += work;
        return state;
    }

    /** S9-E9: adds a later slice's state to {@code into}: charges appended in order, counts summed, players-near united. */
    @SuppressWarnings("unchecked")
    static void merge(NaturalSpawner.SpawnState into, NaturalSpawner.SpawnState from) {
        EngineSpawnStateAccess a = (EngineSpawnStateAccess) into;
        EngineSpawnStateAccess b = (EngineSpawnStateAccess) from;
        ((List<Object>) ((EnginePotentialAccess) a.phoenium$potential()).phoenium$charges())
            .addAll(((EnginePotentialAccess) b.phoenium$potential()).phoenium$charges());
        Object2IntOpenHashMap<MobCategory> categories = a.phoenium$mobCategoryCounts();
        for (Object2IntMap.Entry<MobCategory> entry : b.phoenium$mobCategoryCounts().object2IntEntrySet())
            categories.addTo(entry.getKey(), entry.getIntValue());
        EngineLocalMobCapAccess capsA = (EngineLocalMobCapAccess) a.phoenium$localMobCaps();
        EngineLocalMobCapAccess capsB = (EngineLocalMobCapAccess) b.phoenium$localMobCaps();
        Long2ObjectMap<List<ServerPlayer>> near = capsA.phoenium$playersNear();
        for (Long2ObjectMap.Entry<List<ServerPlayer>> entry : capsB.phoenium$playersNear().long2ObjectEntrySet())
            near.putIfAbsent(entry.getLongKey(), entry.getValue());
        Map<ServerPlayer, Object> perPlayer = (Map<ServerPlayer, Object>) capsA.phoenium$playerMobCounts();
        for (Map.Entry<ServerPlayer, ?> entry : capsB.phoenium$playerMobCounts().entrySet()) {
            Object counts = perPlayer.get(entry.getKey());
            if (counts == null) {
                perPlayer.put(entry.getKey(), entry.getValue());   // the slice's state is dropped: its counts move over
                continue;
            }
            Object2IntMap<MobCategory> target = ((EngineMobCountsAccess) counts).phoenium$counts();
            for (Object2IntMap.Entry<MobCategory> count : ((EngineMobCountsAccess) entry.getValue()).phoenium$counts().object2IntEntrySet())
                target.mergeInt(count.getKey(), count.getIntValue(), Integer::sum);
        }
    }

    /** Server thread: this memo's lookups and fast counts join the totals. */
    private void addCounters() {
        totalQueries += queries;
        totalHits += hits;
        totalChunks += chunks.size();
        totalFastMobs += caps.fastMobs;
    }

    /** The first difference between two freshly built spawn states, or null. */
    static String difference(NaturalSpawner.SpawnState memo, NaturalSpawner.SpawnState vanilla) {
        EngineSpawnStateAccess a = (EngineSpawnStateAccess) memo;
        EngineSpawnStateAccess b = (EngineSpawnStateAccess) vanilla;
        if (!a.phoenium$mobCategoryCounts().equals(b.phoenium$mobCategoryCounts()))
            return "mobCategoryCounts " + a.phoenium$mobCategoryCounts() + " vs " + b.phoenium$mobCategoryCounts();
        List<?> chargesA = ((EnginePotentialAccess) a.phoenium$potential()).phoenium$charges();
        List<?> chargesB = ((EnginePotentialAccess) b.phoenium$potential()).phoenium$charges();
        if (chargesA.size() != chargesB.size()) return "charges " + chargesA.size() + " vs " + chargesB.size();
        for (int i = 0, n = chargesA.size(); i < n; i++) {
            EnginePointChargeAccess x = (EnginePointChargeAccess) chargesA.get(i);
            EnginePointChargeAccess y = (EnginePointChargeAccess) chargesB.get(i);
            if (!x.phoenium$pos().equals(y.phoenium$pos()) || Double.compare(x.phoenium$charge(), y.phoenium$charge()) != 0)
                return "charge " + i + " " + x.phoenium$pos() + "/" + x.phoenium$charge() + " vs " + y.phoenium$pos() + "/" + y.phoenium$charge();
        }
        EngineLocalMobCapAccess capsA = (EngineLocalMobCapAccess) a.phoenium$localMobCaps();
        EngineLocalMobCapAccess capsB = (EngineLocalMobCapAccess) b.phoenium$localMobCaps();
        if (!capsA.phoenium$playersNear().equals(capsB.phoenium$playersNear()))
            return "playersNear " + capsA.phoenium$playersNear().size() + " vs " + capsB.phoenium$playersNear().size() + " chunks";
        Map<ServerPlayer, ?> countsA = capsA.phoenium$playerMobCounts();
        Map<ServerPlayer, ?> countsB = capsB.phoenium$playerMobCounts();
        if (!countsA.keySet().equals(countsB.keySet())) return "playerMobCounts " + countsA.size() + " vs " + countsB.size() + " players";
        for (Map.Entry<ServerPlayer, ?> entry : countsA.entrySet()) {
            Object2IntMap<MobCategory> x = ((EngineMobCountsAccess) entry.getValue()).phoenium$counts();
            Object2IntMap<MobCategory> y = ((EngineMobCountsAccess) countsB.get(entry.getKey())).phoenium$counts();
            if (!x.equals(y)) return "counts of " + entry.getKey().getScoreboardName() + " " + x + " vs " + y;
        }
        return null;
    }

    static String counters() {
        return "spawnMemoCalls=" + calls + " spawnMemoQueries=" + totalQueries + " spawnMemoHits=" + totalHits + " spawnMemoChunks=" + totalChunks
            + " spawnMemoFastMobs=" + totalFastMobs + " spawnMemoChecks=" + checks + " spawnMemoStale=" + stale
            + " spawnSliceStates=" + sliceStates + " spawnSlices=" + sliceCount + " spawnSliceWorkUs=" + sliceWorkNanos / 1000
            + " spawnMemoPinMisses=" + pinMisses + " spawnMemoSmeared=" + smeared + " spawnMemoSmearedChunks=" + smearedChunks
            + " spawnMemoLiveDiffs=" + liveDiffs;
    }
}
