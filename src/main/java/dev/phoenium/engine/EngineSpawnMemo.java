package dev.phoenium.engine;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.phoenium.engine.mixin.EngineLocalMobCapAccess;
import dev.phoenium.engine.mixin.EngineMobCountsAccess;
import dev.phoenium.engine.mixin.EnginePointChargeAccess;
import dev.phoenium.engine.mixin.EnginePotentialAccess;
import dev.phoenium.engine.mixin.EngineSpawnStateAccess;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
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
 */
public final class EngineSpawnMemo implements NaturalSpawner.ChunkGetter {
    private static final Logger LOGGER = LoggerFactory.getLogger("Phoenium");
    @SuppressWarnings("unchecked")
    private static final Object2IntOpenHashMap<MobCategory>[] VANILLA = new Object2IntOpenHashMap[0];
    // server thread
    private static long calls;
    private static long queries;
    private static long hits;
    private static long chunkCount;
    private static long fastMobs;
    private static long checks;
    private static long stale;

    /** A level's memo settings (the engine's ServerChunkCache mixin keeps one per level). */
    public static final class Level {
        int states;
        int expected = 16;
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
            fastMobs++;
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

    private final NaturalSpawner.ChunkGetter getter;
    private final Caps caps;
    private final Long2ObjectOpenHashMap<Chunk> chunks;
    private LevelChunk found;
    private final Consumer<LevelChunk> sink = chunk -> this.found = chunk;

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
            chunkCount++;
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
        Caps caps = new Caps(chunkMap, Thread.currentThread());
        EngineSpawnMemo memo = new EngineSpawnMemo(getter, caps, level.expected);
        NaturalSpawner.SpawnState state = original.call(spawnableChunks, entities, memo, caps);
        calls++;
        level.expected = Math.max(16, memo.chunks.size());
        if (EngineConfig.SPAWN_MEMO_CHECK > 0 && ++level.states % EngineConfig.SPAWN_MEMO_CHECK == 0) {
            NaturalSpawner.SpawnState reference = original.call(spawnableChunks, entities, getter, new LocalMobCapCalculator(chunkMap));
            checks++;
            String difference = difference(state, reference);
            if (difference != null) {
                stale++;
                if (stale <= 10) LOGGER.warn("PHOENIUM_ENGINE spawnMemo stale (using the vanilla state): {}", difference);
                return reference;
            }
        }
        return state;
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
        return "spawnMemoCalls=" + calls + " spawnMemoQueries=" + queries + " spawnMemoHits=" + hits + " spawnMemoChunks=" + chunkCount
            + " spawnMemoFastMobs=" + fastMobs + " spawnMemoChecks=" + checks + " spawnMemoStale=" + stale;
    }
}
