package dev.phoenium.chunk;

import dev.phoenium.mixin.ChunkMapLoadAccess;
import dev.phoenium.mixin.RegionalServerChunkCacheAccess;
import dev.phoenium.ownership.OwnershipContext;
import dev.phoenium.scheduler.*;
import dev.phoenium.service.*;
import dev.phoenium.topology.*;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.*;
import net.minecraft.server.level.*;
import net.minecraft.util.RandomSource;
import net.minecraft.util.random.WeightedList;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.structures.*;
import net.minecraft.world.level.storage.LevelData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static dev.phoenium.chunk.FullDecisionWitnessSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual pinned NaturalSpawner vs the retained production loop; actual native managedBlock
 * and regional dispatcher. Scene/world endpoints are controlled, not live acceptance. */
class NaturalSpawnContinuationNativeTest {
    @BeforeAll static void bootstrap() { FullProductionRegistrationTest.bootstrap(); }
    static final class Draws extends LegacyRandomSource {
        final List<String> draws = new ArrayList<>();
        Draws(long seed) { super(seed); }
        @Override public int next(int bits) { int value = super.next(bits); draws.add(bits + ":" + value); return value; }
    }
    static final class Loop extends BlockableEventLoop<Runnable> implements ManagedBlockPollTurnFence.Loop {
        final Thread thread = Thread.currentThread();
        final ManagedBlockPollTurnFence fence = new ManagedBlockPollTurnFence(thread);
        Runnable body;
        int polls;
        Loop() { super("native-spawn-differential", false); }
        public ManagedBlockPollTurnFence phoenium$naturalSpawnFence() { return fence; }
        public Runnable wrapRunnable(Runnable task) { return task; }
        protected boolean shouldRun(Runnable task) { return true; }
        protected Thread getRunningThread() { return thread; }
        protected boolean pollTask() { return fence.poll(() -> { assertEquals(1, ++polls); body.run(); return true; }); }
    }
    static final class Scene implements AutoCloseable {
        final FullRegistrationFixture f = new FullRegistrationFixture(true);
        final Draws random;
        final Loop loop = new Loop();
        final CompletableFuture<ChunkResult<ChunkAccess>> pending = new CompletableFuture<>();
        final ChunkPos startPos = new ChunkPos(8, 0);
        final List<String> queries = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        final RegionScheduler scheduler;
        final RegionalServiceDispatcher dispatcher;
        final RoutingKey key = new RoutingKey(f.worldId, 0, 0);
        final Player player;
        final ServerLevel level;
        final LevelChunk source;
        final NaturalSpawner.SpawnState state;
        final Structure structure;
        int prefixes;
        Scene(long seed, boolean delayed) throws Exception {
            random = new Draws(seed);
            var regions = Map.of(f.ownerId, f.owner, f.otherId, f.other);
            scheduler = new RegionScheduler(regions, f.directory, f.barrier);
            dispatcher = new RegionalServiceDispatcher("natural-spawn-scene", 1, f.directory, regions,
                new OwnerGateway(f.directory, regions, scheduler), scheduler);
            player = stub(Player.class, List.of(new Answer(Player.class.getMethod("distanceToSqr", double.class, double.class, double.class), ignored -> 2000.0)));
            var originalLevel = f.world.level;
            level = stub(originalLevel.getClass(), List.of(
                new Answer(ServerLevel.class.getMethod("getRandom"), ignored -> random),
                new Answer(ServerLevel.class.getMethod("getWorldBorder"), ignored -> new net.minecraft.world.level.border.WorldBorder()),
                new Answer(ServerLevel.class.getMethod("getNearestPlayer", double.class, double.class, double.class, double.class, boolean.class), ignored -> player),
                new Answer(ServerLevel.class.getMethod("getRespawnData"), ignored -> LevelData.RespawnData.of(Level.NETHER, BlockPos.ZERO, 0, 0)),
                new Answer(ServerLevel.class.getMethod("canSpawnEntitiesInChunk", ChunkPos.class), ignored -> true),
                new Answer(ServerLevel.class.getMethod("getBlockState", BlockPos.class), ignored -> Blocks.AIR.defaultBlockState()),
                new Answer(ServerLevel.class.getMethod("getChunk", int.class, int.class, ChunkStatus.class, boolean.class), args -> {
                    ChunkStatus status = (ChunkStatus) args[2];
                    if (status != ChunkStatus.STRUCTURE_REFERENCES && status != ChunkStatus.STRUCTURE_STARTS) return source();
                    var future = select((int) args[0], (int) args[1], status);
                    loop.managedBlock(future::isDone); return future.join().orElse(null);
                })));
            copyFields(originalLevel, level); set(level, Level.class, "random", random);
            set(f.world, FullTadpoleDecisionTest.Fixture.class, "level", level);
            set(f.runtime, FullRegistrationRuntime.class, "level", level);
            source = stub(LevelChunk.class, List.of(
                new Answer(LevelChunk.class.getMethod("getPos"), ignored -> ChunkPos.ZERO),
                new Answer(LevelChunk.class.getMethod("getHeight", Heightmap.Types.class, int.class, int.class), ignored -> 64),
                new Answer(LevelChunk.class.getMethod("getBlockState", BlockPos.class), ignored -> Blocks.AIR.defaultBlockState())));
            var generator = stub(ChunkGenerator.class, List.of());
            var map = stub(ChunkMap.class, List.of(
                new Answer(FullGenerationSource.class.getMethod("phoenium$fullRegistration"), ignored -> f.runtime),
                new Answer(ChunkMapLoadAccess.class.getMethod("phoenium$mainExecutor"), ignored -> loop)), FullGenerationSource.class, ChunkMapLoadAccess.class);
            copyFields(f.fixtureMap, map); f.fixtureMap = map;
            var cache = stub(ServerChunkCache.class, List.of(
                new Answer(ServerChunkCache.class.getMethod("getGenerator"), ignored -> generator),
                new Answer(RegionalServerChunkCacheAccess.class.getMethod("phoenium$naturalSpawnFuture", int.class, int.class, ChunkStatus.class, boolean.class),
                    args -> select((int) args[0], (int) args[1], (ChunkStatus) args[2]))), RegionalServerChunkCacheAccess.class);
            set(cache, ServerChunkCache.class, "chunkMap", map); set(cache, ServerChunkCache.class, "mainThread", Thread.currentThread());
            set(level, ServerLevel.class, "chunkSource", cache);
            set(level, ServerLevel.class, "structureManager", new StructureManager(level, new WorldOptions(0, true, false), null));
            structure = new DesertPyramidStructure(new Structure.StructureSettings(HolderSet.empty(),
                Map.of(MobCategory.MONSTER, new StructureSpawnOverride(StructureSpawnOverride.BoundingBoxType.STRUCTURE,
                    WeightedList.of(new MobSpawnSettings.SpawnerData(EntityTypes.ZOMBIE, 1, 3)))),
                GenerationStep.Decoration.SURFACE_STRUCTURES, TerrainAdjustment.NONE));
            var piece = new DesertPyramidPiece(RandomSource.create(0), 0, 0);
            set(piece, StructurePiece.class, "boundingBox", new BoundingBox(-128, -64, -128, 128, 128, 128));
            f.input(startPos).setStartForStructure(structure, new StructureStart(structure, startPos, 0, new PiecesContainer(List.of(piece))));
            f.holder(startPos);
            set(f.holder(ChunkPos.ZERO), ChunkHolder.class, "tickingChunkFuture", CompletableFuture.completedFuture(ChunkResult.of(source)));
            var caps = stub(LocalMobCapCalculator.class, List.of(new Answer(LocalMobCapCalculator.class.getMethod("canSpawn", MobCategory.class, ChunkPos.class), ignored -> true)));
            var constructor = NaturalSpawner.SpawnState.class.getDeclaredConstructor(int.class, Object2IntOpenHashMap.class, PotentialCalculator.class, LocalMobCapCalculator.class);
            constructor.setAccessible(true); state = constructor.newInstance(289, new Object2IntOpenHashMap<>(), new PotentialCalculator(), caps);
            if (!delayed) pending.complete(ChunkResult.of(f.input(startPos)));
            loop.body = () -> {
                events.add("poll-enter");
                var completion = dispatcher.submitC2mePersistedPoiAfterRelease(key, ignored -> events.add("dependency-body"));
                completion.completion().thenRun(() -> { dispatcher.requireNaturalSpawnOwnerFree(); pending.complete(ChunkResult.of(f.input(startPos))); events.add("raw"); });
                dispatcher.submit(key, RegionalServiceKind.CHUNK_GAMEPLAY, ignored -> { random.nextInt(); events.add("allowed-gameplay"); });
                dispatcher.executeAndDrain(key, RegionalServiceKind.SAVE_ACKNOWLEDGEMENT, ignored -> events.add("save"));
                events.add("poll-return");
            };
        }
        LevelChunk source() { return source; }
        CompletableFuture<ChunkResult<ChunkAccess>> select(int x, int z, ChunkStatus status) {
            var pos = new ChunkPos(x, z); f.holder(pos);
            queries.add(x + "," + z + ":" + status.getName());
            if (status == ChunkStatus.STRUCTURE_STARTS && pos.equals(startPos)) return pending;
            var chunk = f.input(pos); chunk.addReferenceForStructure(structure, startPos.pack());
            return CompletableFuture.completedFuture(ChunkResult.of(chunk));
        }
        void run(boolean retained) {
            if (retained) {
                NaturalSpawnContinuation.PlayerQueries players = new NaturalSpawnContinuation.PlayerQueries() {
                    public Player nearestNaturalSpawnPlayer(double x, double y, double z) { return player; }
                    public double naturalSpawnPlayerDistance(Player p, double x, double y, double z) { return 2000; }
                };
                try (var capture = new NaturalSpawnContinuation.Capture(players, dispatcher, level, source, key, 1, () -> true)) {
                    dispatcher.executeAndDrain(key, RegionalServiceKind.CHUNK_GAMEPLAY, ignored -> capture.original(() -> {
                        prefixes++; random.nextInt();
                        assertTrue(NaturalSpawnContinuation.capture(level, source, state, List.of(MobCategory.MONSTER)));
                    }));
                    capture.finish(Runnable::run);
                }
            } else {
                prefixes++; random.nextInt(); NaturalSpawner.spawnForChunk(level, source, state, List.of(MobCategory.MONSTER));
            }
            random.nextInt(); events.add("native-suffix");
        }
        public void close() { dispatcher.requireNaturalSpawnOwnerFree(); scheduler.stop(); }
    }
    static void copyFields(Object source, Object target) throws Exception {
        for (Class<?> type = source.getClass(); type != null && type != Object.class; type = type.getSuperclass())
            for (var field : type.getDeclaredFields()) if (type.isInstance(target) && !Modifier.isStatic(field.getModifiers())) {
                field.setAccessible(true); field.set(target, field.get(source));
            }
    }
    @ParameterizedTest @ValueSource(longs = {812, 1234, 4782, 12345, 92173})
    void retainedLoopMatchesNativeQueriesAndEverySharedRandomDrawAcrossOnePoll(long seed) throws Exception {
        compare(seed, true);
    }
    @ParameterizedTest @ValueSource(longs = {812, 1234, 4782, 12345, 92173})
    void alreadyReadyQueriesMatchNativeWithZeroPolls(long seed) throws Exception { compare(seed, false); }
    private void compare(long seed, boolean delayed) throws Exception {
        try (var nativeScene = new Scene(seed, delayed); var retained = new Scene(seed, delayed)) {
            nativeScene.run(false); retained.run(true);
            assertFalse(nativeScene.queries.isEmpty(), "seed must exercise actual reached structure queries");
            assertEquals(nativeScene.queries, retained.queries);
            assertEquals(nativeScene.random.draws, retained.random.draws, "same persistent RNG, same native branch draws and suffix");
            assertEquals(nativeScene.events, retained.events);
            assertEquals(delayed ? 1 : 0, retained.loop.polls);
            assertEquals(1, retained.prefixes); assertEquals(0, retained.dispatcher.pendingCount());
            assertFalse(retained.f.barrier.tripped());
        }
    }
}
