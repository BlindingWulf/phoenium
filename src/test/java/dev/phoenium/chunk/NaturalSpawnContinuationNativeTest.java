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
import org.junit.jupiter.api.Test;
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
        int polls, expectedPolls = 1;
        Loop() { super("native-spawn-differential", false); }
        public ManagedBlockPollTurnFence phoenium$naturalSpawnFence() { return fence; }
        public Runnable wrapRunnable(Runnable task) { return task; }
        protected boolean shouldRun(Runnable task) { return true; }
        protected Thread getRunningThread() { return thread; }
        protected boolean pollTask() { return fence.poll(() -> { assertTrue(++polls <= expectedPolls); body.run(); return true; }); }
    }
    static final class Scene implements AutoCloseable {
        final FullRegistrationFixture f = new FullRegistrationFixture(true);
        final Draws random;
        final Loop loop = new Loop();
        final CompletableFuture<ChunkResult<ChunkAccess>> pending = new CompletableFuture<>();
        final CompletableFuture<ChunkResult<ChunkAccess>> pendingSecond = new CompletableFuture<>();
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
        final boolean authoritative;
        final int missingQuery;
        int prefixes, referenceQueries;
        boolean secondDelay;
        int created, finalized, inserted, capped;
        final List<Mob> mobs = new ArrayList<>();
        EntityType<?> successfulType;
        Scene(long seed, boolean delayed) throws Exception {
            this(seed, delayed, true, Level.OVERWORLD, 1);
        }
        Scene(long seed, boolean delayed, boolean authoritative,
              net.minecraft.resources.ResourceKey<Level> dimension, int missingQuery) throws Exception {
            this(seed, delayed, authoritative, dimension, missingQuery, "STRUCTURE");
        }
        Scene(long seed, boolean delayed, boolean authoritative,
              net.minecraft.resources.ResourceKey<Level> dimension, int missingQuery, String branch) throws Exception {
            this.authoritative = authoritative; this.missingQuery = missingQuery;
            random = new Draws(seed);
            var regions = Map.of(f.ownerId, f.owner, f.otherId, f.other);
            scheduler = new RegionScheduler(regions, f.directory, f.barrier);
            dispatcher = new RegionalServiceDispatcher("natural-spawn-scene", 1, f.directory, regions,
                new OwnerGateway(f.directory, regions, scheduler), scheduler);
            player = stub(Player.class, List.of(new Answer(Player.class.getMethod("distanceToSqr", double.class, double.class, double.class), ignored -> 2000.0)));
            var originalLevel = f.world.level;
            level = stub(originalLevel.getClass(), List.of(
                new Answer(ServerLevel.class.getMethod("getRandom"), ignored -> random),
                new Answer(ServerLevel.class.getMethod("dimension"), ignored -> dimension),
                new Answer(ServerLevel.class.getMethod("getWorldBorder"), ignored -> new net.minecraft.world.level.border.WorldBorder()),
                new Answer(ServerLevel.class.getMethod("getNearestPlayer", double.class, double.class, double.class, double.class, boolean.class), ignored -> player),
                new Answer(ServerLevel.class.getMethod("getRespawnData"), ignored -> LevelData.RespawnData.of(Level.NETHER, BlockPos.ZERO, 0, 0)),
                new Answer(ServerLevel.class.getMethod("canSpawnEntitiesInChunk", ChunkPos.class), ignored -> true),
                new Answer(ServerLevel.class.getMethod("getBlockState", BlockPos.class), ignored ->
                    (branch.equals("FORTRESS") ? Blocks.NETHER_BRICKS : Blocks.AIR).defaultBlockState()),
                new Answer(ServerLevel.class.getMethod("noCollision", net.minecraft.world.phys.AABB.class), ignored -> branch.equals("SUCCESS")),
                new Answer(ServerLevel.class.getMethod("getCurrentDifficultyAt", BlockPos.class), ignored ->
                    new net.minecraft.world.DifficultyInstance(net.minecraft.world.Difficulty.HARD, 100000, 100000, 0.5F)),
                new Answer(ServerLevel.class.getMethod("addFreshEntityWithPassengers", Entity.class), args -> {
                    assertTrue(mobs.stream().anyMatch(mob -> mob == args[0])); inserted++; events.add("insert:" + inserted); return null;
                }),
                new Answer(ServerLevel.class.getMethod("getChunk", int.class, int.class, ChunkStatus.class, boolean.class), args -> {
                    ChunkStatus status = (ChunkStatus) args[2];
                    if (status != ChunkStatus.STRUCTURE_REFERENCES && status != ChunkStatus.STRUCTURE_STARTS) return source();
                    var future = select((int) args[0], (int) args[1], status);
                    ChunkSynchronousAccessGuard.beforeWait(future::isDone);
                    loop.managedBlock(future::isDone); return future.join().orElse(null);
                })));
            copyFields(originalLevel, level); set(level, Level.class, "random", random);
            set(f.world, FullTadpoleDecisionTest.Fixture.class, "level", level);
            set(f.runtime, FullRegistrationRuntime.class, "level", level);
            source = stub(LevelChunk.class, List.of(
                new Answer(LevelChunk.class.getMethod("getPos"), ignored -> ChunkPos.ZERO),
                new Answer(LevelChunk.class.getMethod("getHeight", Heightmap.Types.class, int.class, int.class), ignored -> 64),
                new Answer(LevelChunk.class.getMethod("getNoiseBiome", int.class, int.class, int.class), ignored -> level.getBiome(BlockPos.ZERO)),
                new Answer(LevelChunk.class.getMethod("getBlockState", BlockPos.class), ignored -> Blocks.AIR.defaultBlockState())));
            var generator = stub(ChunkGenerator.class, List.of());
            var mapAnswers = new ArrayList<>(List.of(
                new Answer(FullGenerationSource.class.getMethod("phoenium$fullRegistration"), ignored -> authoritative ? f.runtime : null),
                new Answer(ChunkMapLoadAccess.class.getMethod("phoenium$mainExecutor"), ignored -> loop)));
            // Allows the identical regression to run against a381d61, which lacks this binding.
            for (var method : FullGenerationSource.class.getMethods())
                if (method.getName().equals("phoenium$naturalSpawnStructures")) mapAnswers.add(new Answer(method, ignored -> f.runtime));
            var map = stub(ChunkMap.class, mapAnswers, FullGenerationSource.class, ChunkMapLoadAccess.class);
            copyFields(f.fixtureMap, map); f.fixtureMap = map;
            var cache = stub(ServerChunkCache.class, List.of(
                new Answer(ServerChunkCache.class.getMethod("getGenerator"), ignored -> generator),
                new Answer(RegionalServerChunkCacheAccess.class.getMethod("phoenium$naturalSpawnFuture", int.class, int.class, ChunkStatus.class, boolean.class),
                    args -> {
                        assertTrue(OwnershipContext.current().isEmpty()); assertFalse(f.owner.leases().hasActiveLease());
                        assertEquals(true, args[3]);
                        return select((int) args[0], (int) args[1], (ChunkStatus) args[2]);
                    })), RegionalServerChunkCacheAccess.class);
            set(cache, ServerChunkCache.class, "chunkMap", map); set(cache, ServerChunkCache.class, "mainThread", Thread.currentThread());
            set(level, ServerLevel.class, "chunkSource", cache);
            set(level, ServerLevel.class, "structureManager", new StructureManager(level, new WorldOptions(0, true, false), null));
            if (branch.equals("SUCCESS")) successfulType = stub(EntityType.class, List.of(
                new Answer(EntityType.class.getMethod("getCategory"), ignored -> MobCategory.MONSTER),
                new Answer(EntityType.class.getMethod("canSummon"), ignored -> true),
                new Answer(EntityType.class.getMethod("canSpawnFarFromPlayer"), ignored -> true),
                new Answer(EntityType.class.getMethod("isAllowedInPeaceful"), ignored -> true),
                new Answer(EntityType.class.getMethod("getSpawnAABB", double.class, double.class, double.class), ignored -> new net.minecraft.world.phys.AABB(0, 0, 0, 1, 1, 1)),
                new Answer(EntityType.class.getMethod("create", Level.class, EntitySpawnReason.class), ignored -> createMob())));
            var settings = new Structure.StructureSettings(HolderSet.empty(),
                Map.of(MobCategory.MONSTER, new StructureSpawnOverride(branch.equals("PIECE")
                    ? StructureSpawnOverride.BoundingBoxType.PIECE : StructureSpawnOverride.BoundingBoxType.STRUCTURE,
                    WeightedList.of(new MobSpawnSettings.SpawnerData(branch.equals("SUCCESS") ? successfulType : EntityTypes.ZOMBIE, branch.equals("SUCCESS") ? 3 : 1, 3)))),
                GenerationStep.Decoration.SURFACE_STRUCTURES, TerrainAdjustment.NONE);
            structure = branch.equals("FORTRESS") ? new NetherFortressStructure(settings) : new DesertPyramidStructure(settings);
            if (branch.equals("FORTRESS")) {
                var registry = new MappedRegistry<Structure>(net.minecraft.core.registries.Registries.STRUCTURE, com.mojang.serialization.Lifecycle.stable());
                registry.register(BuiltinStructures.FORTRESS, structure, RegistrationInfo.BUILT_IN);
                var access = new RegistryAccess.ImmutableRegistryAccess(List.of(registry)).freeze();
                set(level, ServerLevel.class, "structureManager", new StructureManager(level, new WorldOptions(0, true, false), null) {
                    @Override public RegistryAccess registryAccess() { return access; }
                });
            }
            var piece = new DesertPyramidPiece(RandomSource.create(0), 0, 0);
            set(piece, StructurePiece.class, "boundingBox", branch.equals("BIOME")
                ? new BoundingBox(120, 0, 0, 128, 128, 15) : new BoundingBox(-128, -64, -128, 128, 128, 128));
            f.input(startPos).setStartForStructure(structure, new StructureStart(structure, startPos, 0, new PiecesContainer(List.of(piece))));
            f.holder(startPos);
            set(f.holder(ChunkPos.ZERO), ChunkHolder.class, "tickingChunkFuture", CompletableFuture.completedFuture(ChunkResult.of(source)));
            var caps = stub(LocalMobCapCalculator.class, List.of(new Answer(LocalMobCapCalculator.class.getMethod("canSpawn", MobCategory.class, ChunkPos.class), ignored -> true),
                new Answer(LocalMobCapCalculator.class.getMethod("addMob", ChunkPos.class, MobCategory.class), ignored -> { capped++; events.add("cap:" + capped); return null; })));
            var constructor = NaturalSpawner.SpawnState.class.getDeclaredConstructor(int.class, Object2IntOpenHashMap.class, PotentialCalculator.class, LocalMobCapCalculator.class);
            constructor.setAccessible(true); state = constructor.newInstance(289, new Object2IntOpenHashMap<>(), new PotentialCalculator(), caps);
            if (!delayed) pending.complete(ChunkResult.of(f.input(startPos)));
            loop.body = () -> {
                events.add("poll-enter");
                var completion = dispatcher.submitC2mePersistedPoiAfterRelease(key, ignored -> events.add("dependency-body"));
                completion.completion().thenRun(() -> {
                    dispatcher.requireNaturalSpawnOwnerFree();
                    var future = pending.isDone() && secondDelay ? pendingSecond : pending;
                    assertTrue(future.complete(ChunkResult.of(f.input(startPos))));
                    assertFalse(future.complete(ChunkResult.of(f.input(startPos))), "duplicate completion is inert");
                    events.add("raw");
                });
                dispatcher.submit(key, RegionalServiceKind.CHUNK_GAMEPLAY, ignored -> { random.nextInt(); events.add("allowed-gameplay"); });
                dispatcher.executeAndDrain(key, RegionalServiceKind.SAVE_ACKNOWLEDGEMENT, ignored -> events.add("save"));
                events.add("poll-return");
            };
        }
        LevelChunk source() { return source; }
        Mob createMob() {
            try {
                int id = ++created;
                var box = new Mob[1];
                Mob mob = stub(Mob.class, List.of(
                    new Answer(Mob.class.getMethod("snapTo", double.class, double.class, double.class, float.class, float.class), args -> {
                        try { set(box[0], Entity.class, "blockPosition", BlockPos.containing((double) args[0], (double) args[1], (double) args[2])); }
                        catch (Exception failure) { throw new AssertionError(failure); } return null;
                    }),
                    new Answer(Mob.class.getMethod("checkSpawnRules", LevelAccessor.class, EntitySpawnReason.class), ignored -> true),
                    new Answer(Mob.class.getMethod("checkSpawnObstruction", LevelReader.class), ignored -> true),
                    new Answer(Mob.class.getMethod("getMaxSpawnClusterSize"), ignored -> 99),
                    new Answer(Mob.class.getMethod("isMaxGroupSizeReached", int.class), ignored -> false),
                    new Answer(Mob.class.getMethod("finalizeSpawn", ServerLevelAccessor.class, net.minecraft.world.DifficultyInstance.class, EntitySpawnReason.class, SpawnGroupData.class), args -> {
                        finalized++; events.add("finalize:" + id); return args[3];
                    })));
                box[0] = mob; set(mob, Entity.class, "type", successfulType); set(mob, Entity.class, "level", level);
                set(mob, Entity.class, "passengers", com.google.common.collect.ImmutableList.of());
                mobs.add(mob); return mob;
            } catch (Exception failure) { throw new AssertionError(failure); }
        }
        CompletableFuture<ChunkResult<ChunkAccess>> select(int x, int z, ChunkStatus status) {
            var pos = new ChunkPos(x, z); f.holder(pos);
            queries.add(x + "," + z + ":" + status.getName());
            if (status == ChunkStatus.STRUCTURE_REFERENCES) referenceQueries++;
            if (status == ChunkStatus.STRUCTURE_STARTS && pos.equals(startPos))
                return secondDelay && referenceQueries > missingQuery ? pendingSecond
                    : referenceQueries >= missingQuery ? pending : CompletableFuture.completedFuture(ChunkResult.of(f.input(startPos)));
            var chunk = f.input(pos); chunk.addReferenceForStructure(structure, startPos.pack());
            return CompletableFuture.completedFuture(ChunkResult.of(chunk));
        }
        void run(boolean retained) {
            NaturalSpawnSceneHooks.active = true;
            try (var serviceTurn = FullFinishingServices.forServer(level.getServer()).referenceAccess()) {
            if (retained) {
                NaturalSpawnContinuation.PlayerQueries players = new NaturalSpawnContinuation.PlayerQueries() {
                    public Player nearestNaturalSpawnPlayer(double x, double y, double z) { return player; }
                    public double naturalSpawnPlayerDistance(Player p, double x, double y, double z) { return 2000; }
                };
                try (var capture = new NaturalSpawnContinuation.Capture(players, dispatcher, level, source, key, 1, () -> true)) {
                    dispatcher.executeAndDrain(key, RegionalServiceKind.CHUNK_GAMEPLAY, ignored -> capture.original(() -> {
                        prefixes++; random.nextInt();
                        NaturalSpawner.spawnForChunk(level, source, state, List.of(MobCategory.MONSTER));
                    }));
                    capture.finish(Runnable::run);
                }
            } else {
                prefixes++; random.nextInt(); NaturalSpawner.spawnForChunk(level, source, state, List.of(MobCategory.MONSTER));
            }
            random.nextInt(); events.add("native-suffix");
            } finally { NaturalSpawnSceneHooks.active = false; }
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
    @Test void ordinaryNetherPackValidationPreparesOutsideOwnerThroughNativeEntrySeam() throws Exception {
        compareOrdinary(Level.NETHER, true, 2);
    }
    @Test void ordinaryNetherInitialSelectionPreparesOutsideOwnerThroughNativeEntrySeam() throws Exception {
        compareOrdinary(Level.NETHER, true, 1);
    }
    @Test void ordinaryNetherReadyQueriesRetainNativeResultAndOrder() throws Exception {
        compareOrdinary(Level.NETHER, false, 1);
    }
    @Test void ordinaryOverworldStillUsesTheSamePreparationBoundary() throws Exception {
        compareOrdinary(Level.OVERWORLD, true, 2);
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({"FORTRESS,true", "FORTRESS,false", "PIECE,true", "BIOME,true"})
    void ordinaryNetherFortressPieceAndBiomePathsMatchNative(String branch, boolean delayed) throws Exception {
        try (var nativeScene = new Scene(812, delayed, false, Level.NETHER, 1, branch);
             var retained = new Scene(812, delayed, false, Level.NETHER, 1, branch)) {
            nativeScene.run(false); retained.run(true);
            assertFalse(retained.queries.isEmpty());
            assertEquals(nativeScene.queries, retained.queries);
            assertEquals(nativeScene.random.draws, retained.random.draws);
            assertEquals(nativeScene.events, retained.events);
            assertEquals(delayed ? 1 : 0, retained.loop.polls);
        }
    }
    @Test void ordinaryNetherSuccessiveIncompleteQueriesRetainTheirOwnPollAndGeneration() throws Exception {
        try (var nativeScene = new Scene(812, true, false, Level.NETHER, 2);
             var retained = new Scene(812, true, false, Level.NETHER, 2)) {
            for (var scene : List.of(nativeScene, retained)) { scene.secondDelay = true; scene.loop.expectedPolls = 2; }
            nativeScene.run(false); retained.run(true);
            assertEquals(nativeScene.queries, retained.queries);
            assertEquals(nativeScene.random.draws, retained.random.draws);
            assertEquals(nativeScene.events, retained.events); assertEquals(2, retained.loop.polls);
            assertEquals(1, retained.prefixes); assertFalse(retained.f.barrier.tripped());
        }
    }
    @Test void completedMobPrefixIsNotReplayedAfterLaterPackQuerySuspends() throws Exception {
        try (var nativeScene = new Scene(812, true, false, Level.NETHER, 3, "SUCCESS");
             var retained = new Scene(812, true, false, Level.NETHER, 3, "SUCCESS")) {
            nativeScene.run(false); retained.run(true);
            assertTrue(retained.finalized > 1);
            assertTrue(retained.events.indexOf("cap:1") < retained.events.indexOf("poll-enter"));
            assertEquals(nativeScene.events, retained.events); assertEquals(nativeScene.random.draws, retained.random.draws);
            assertEquals(nativeScene.queries, retained.queries);
            assertEquals(retained.created, retained.finalized); assertEquals(retained.finalized, retained.inserted);
            assertEquals(retained.inserted, retained.capped);
            assertEquals(retained.capped, retained.state.getMobCategoryCounts().getInt(MobCategory.MONSTER));
            assertNull(FreshPassengerAdmission.currentNaturalSpawnScope(retained.level));
            assertEquals(1, retained.prefixes);
        }
    }
    @Test void boundedTelemetryProvesTheOrdinaryNetherPackQuerySettled() throws Exception {
        String previous = System.getProperty("phoenium.test.diagnostics");
        var messages = new ArrayList<String>();
        var logger = (org.apache.logging.log4j.core.Logger) org.apache.logging.log4j.LogManager.getLogger(NaturalSpawnObservation.class);
        var appender = new org.apache.logging.log4j.core.appender.AbstractAppender("natural-spawn-test", null,
                org.apache.logging.log4j.core.layout.PatternLayout.createDefaultLayout(), false,
                org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY) {
            @Override public void append(org.apache.logging.log4j.core.LogEvent event) { messages.add(event.getMessage().getFormattedMessage()); }
        };
        appender.start(); logger.addAppender(appender);
        try {
            System.setProperty("phoenium.test.diagnostics", "true");
            compareOrdinary(Level.NETHER, true, 2);
            for (String kind : List.of("INITIAL_SELECTION", "PACK_VALIDATION"))
                assertTrue(messages.stream().anyMatch(message -> message.contains(kind + ":SETTLED:references=PRESENT")
                    && message.contains("dimension=minecraft:the_nether") && message.contains("complete=true")
                    && message.contains("generation=") && message.contains("ownerFree=true")), kind);
            assertTrue(messages.size() <= 32);
        } finally {
            if (previous == null) System.clearProperty("phoenium.test.diagnostics"); else System.setProperty("phoenium.test.diagnostics", previous);
            logger.removeAppender(appender); appender.stop();
        }
    }
    private void compareOrdinary(net.minecraft.resources.ResourceKey<Level> dimension, boolean delayed, int missingQuery) throws Exception {
        try (var nativeScene = new Scene(812, delayed, false, dimension, missingQuery);
             var retained = new Scene(812, delayed, false, dimension, missingQuery)) {
            assertEquals(dimension, retained.level.dimension());
            nativeScene.run(false); retained.run(true);
            assertTrue(retained.referenceQueries > 2, "initial selection, membership and subsequent attempts must be reached");
            assertEquals(nativeScene.queries, retained.queries);
            assertEquals(nativeScene.random.draws, retained.random.draws);
            assertEquals(nativeScene.events, retained.events);
            assertEquals(delayed ? 1 : 0, retained.loop.polls);
            assertEquals(1, retained.prefixes); assertEquals(0, retained.dispatcher.pendingCount());
            assertFalse(retained.f.barrier.tripped());
            // A fresh callback cannot inherit the previous capture/query or replay its prefix.
            retained.run(true); assertEquals(2, retained.prefixes);
        }
    }
}
