package dev.phoenium.chunk;

import dev.phoenium.core.TopologyGeneration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.*;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.animal.frog.Tadpole;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import net.minecraft.world.level.storage.PrimaryLevelData;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static dev.phoenium.chunk.FullDecisionWitnessSupport.*;
import static dev.phoenium.chunk.FullInitializedInputBindingTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real admitted registrar suffix and original difficulty/collision/getter/shape bodies.
 * The world endpoints and serial context inputs are controlled; no private result is supplied
 * by the fixture. The production query authority must consume the actual selected futures.
 */
final class FullFinishingQueriesTest {
    @BeforeAll static void bootstrap() { FullProductionRegistrationTest.bootstrap(); }

    @ParameterizedTest @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void originalBoundaryPairFinishesWithoutPublishingOrCachingEitherPrivateInput(boolean reload, boolean reverse) throws Exception {
        var f = new FullRegistrationFixture(false);
        var x = ChunkPos.ZERO; var y = new ChunkPos(1, 0);
        try {
            saved(f, x, reload, tag(15.8, 8, Tadpole.ticksToBeFrog));
            saved(f, y, reload, tag(16.2, 8, Tadpole.ticksToBeFrog));
            f.start(reverse ? y : x, ChunkStatus.FULL); f.start(reverse ? x : y, ChunkStatus.FULL);
            f.holder(x).scheduleChunkGenerationTask(ChunkStatus.FULL, f.fixtureMap);
            f.holder(y).scheduleChunkGenerationTask(ChunkStatus.FULL, f.fixtureMap);
            f.drain();
            var a = f.runtime.graph(f.holder(reverse ? y : x));
            var b = f.runtime.graph(f.holder(reverse ? x : y));
            var firstSlot = slot(a.holder); var secondSlot = slot(b.holder);
            installGetterAndContext(f);
            assertEquals(ChunkStageAdmission.Result.APPLIED, finish(f, a));
            assertTrue(a.conversion().isDone()); assertFalse(a.adjacent().isDone());
            assertFalse(firstSlot.isDone()); assertFalse(secondSlot.isDone());
            assertEquals(ChunkStageAdmission.Result.APPLIED, finish(f, b));
            assertFalse(firstSlot.isDone()); assertFalse(secondSlot.isDone());
            assertEquals(2, f.world.sounds); assertEquals(4, f.world.inserted.size());
            assertTrue(Arrays.stream((ChunkAccess[]) field(f.world.cache, ServerChunkCache.class, "lastChunk")).allMatch(Objects::isNull));
            assertEquals(0, f.world.tickets.getTickets(x.pack()).size());
            assertFalse(f.owner.leases().hasActiveLease());
        } finally { close(f); }
    }

    @Test void originalNonAirNeighborCreatesImmediateEntityAndRetainsUnknownTicketPrefix() throws Exception {
        var f = new FullRegistrationFixture(true);
        var x = ChunkPos.ZERO; var y = new ChunkPos(1, 0);
        var block = new BlockPos(16, 8, 8);
        try {
            saved(f, x, false, tag(15.8, 8, Tadpole.ticksToBeFrog));
            saved(f, y, false, tag(16.2, 8, 1));
            f.input(y).getSection(0).setBlockState(0, 8, 8, Blocks.SHULKER_BOX.defaultBlockState());
            f.start(x, ChunkStatus.FULL); f.start(y, ChunkStatus.FULL);
            f.holder(x).scheduleChunkGenerationTask(ChunkStatus.FULL, f.fixtureMap);
            f.holder(y).scheduleChunkGenerationTask(ChunkStatus.FULL, f.fixtureMap);
            f.drain();
            var a = f.runtime.graph(f.holder(x)); var b = f.runtime.graph(f.holder(y));
            var actual = b.prepared().orElseThrow().actual();
            assertFalse(actual.getBlockEntities().containsKey(block));
            installGetterAndContext(f);
            assertEquals(ChunkStageAdmission.Result.APPLIED, finish(f, a));
            var created = assertInstanceOf(ShulkerBoxBlockEntity.class, actual.getBlockEntities().get(block));
            assertEquals(false, field(actual, LevelChunk.class, "loaded"));
            assertFalse(slot(b.holder).isDone());
            assertTrue(f.world.tickets.getTickets(y.pack()).stream().anyMatch(ticket -> ticket.getType() == TicketType.UNKNOWN
                    && ticket.getTicketLevel() == ChunkLevel.byStatus(ChunkStatus.FULL)));
            assertEquals(0, ((List<?>) field(f.world.level, Level.class, "blockEntityTickers")).size(),
                    "The neighboring preparation is not loaded yet; original isInLevel postpones its ticker");
            assertEquals(ChunkStageAdmission.Result.APPLIED, finish(f, b));
            assertSame(created, actual.getBlockEntities().get(block));
            assertEquals(true, field(actual, LevelChunk.class, "loaded"));
            assertEquals(1, ((List<?>) field(f.world.level, Level.class, "blockEntityTickers")).size(),
                    "The neighbor's original later suffix registers the same immediate entity once");
            assertEquals(1, f.world.sounds);
        } finally { close(f); }
    }

    @Test void wrongSelectedFutureIsCaughtByVanillaButStillFailsFinishingBarrier() throws Exception {
        var f = new FullRegistrationFixture(true);
        try {
            saved(f, ChunkPos.ZERO, false, tag(8, 8, Tadpole.ticksToBeFrog));
            f.start(ChunkPos.ZERO, ChunkStatus.FULL);
            f.holder(ChunkPos.ZERO).scheduleChunkGenerationTask(ChunkStatus.FULL, f.fixtureMap); f.drain();
            var graph = f.runtime.graph(f.holder(ChunkPos.ZERO)); installGetterAndContext(f);
            f.world.queryEndpoint = args -> {
                FullFinishingQueries.selected(f.world.level, (int) args[0], (int) args[1],
                        (ChunkStatus) args[2], (boolean) args[3], new CompletableFuture<>());
                throw new AssertionError("foreign future accepted");
            };
            assertEquals(ChunkStageAdmission.Result.FAILED_BARRIER, finish(f, graph));
            assertTrue(f.barrier.record().isPresent());
            assertEquals(0, f.world.sounds); assertTrue(f.world.inserted.isEmpty());
            assertNotEquals(ChunkCustodyModel.StageState.APPLIED, graph.publication.state().orElse(null));
        } finally { close(f); }
    }

    @Test void originalMovingPistonShapeReadsExactRetainedProgressWithoutAdvancingItsTicker() throws Exception {
        var f = new FullRegistrationFixture(true); var y = new ChunkPos(1, 0); var block = new BlockPos(16, 8, 8);
        try {
            saved(f, ChunkPos.ZERO, false, tag(15.8, 8, Tadpole.ticksToBeFrog));
            saved(f, y, false, tag(16.2, 8, 1));
            var state = Blocks.MOVING_PISTON.defaultBlockState();
            var piston = new PistonMovingBlockEntity(block, state, Blocks.STONE.defaultBlockState(), Direction.EAST, true, false);
            set(piston, PistonMovingBlockEntity.class, "progress", 0.5F);
            set(piston, PistonMovingBlockEntity.class, "progressO", 0.5F);
            f.input(y).getSection(0).setBlockState(0, 8, 8, state); f.input(y).setBlockEntity(piston);
            f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.start(y, ChunkStatus.FULL); f.drain();
            var graph = f.runtime.graph(f.holder(ChunkPos.ZERO)); var neighbor = f.runtime.graph(f.holder(y));
            if (neighbor.preparation.outcome().getNow(null) instanceof ChunkStageOutcome.VanillaFailure<?> failed)
                throw new AssertionError("original neighboring piston preparation", failed.failure());
            if (neighbor.preparation.outcome().getNow(null) instanceof ChunkStageOutcome.BarrierFailure<?> failed)
                throw new AssertionError("neighboring piston preparation barrier", failed.failure());
            assertEquals(FullInitializedInputs.State.SATISFIED, graph.inputEvaluation().state(), graph.inputEvaluation().obligation());
            installGetterAndContext(f);
            assertEquals(ChunkStageAdmission.Result.APPLIED, finish(f, graph), () -> "inputs=" + graph.inputEvaluation()
                    + " stage=" + graph.finishing.state() + " work=" + f.services.custody().workState(graph.work)
                    + " preparation=" + graph.preparation.outcome().getNow(null));
            assertSame(piston, neighbor.prepared().orElseThrow().actual().getBlockEntities().get(block));
            assertEquals(0.5F, piston.getProgress(1));
            assertFalse(f.world.tickets.getTickets(y.pack()).isEmpty(), "The original dynamic lookup commits FULL,true UNKNOWN ticket");
            assertEquals(0, ((List<?>) field(f.world.level, Level.class, "blockEntityTickers")).size());
            assertEquals(1, f.world.sounds);
        } finally { close(f); }
    }

    @Test void originalPublishedNeighborResultRemainsReadyAndCanUseOrdinaryCache() throws Exception {
        var f = new FullRegistrationFixture(true); var y = new ChunkPos(1, 0);
        try {
            saved(f, y, false, tag(16.2, 8, 1)); f.startBaseline(y);
            var readySlot = f.holder(y).scheduleChunkGenerationTask(ChunkStatus.FULL, f.fixtureMap);
            assertTrue(readySlot.isDone());
            ChunkAccess ready = readySlot.join().orElse(null); assertInstanceOf(LevelChunk.class, ready);
            saved(f, ChunkPos.ZERO, false, tag(15.8, 8, Tadpole.ticksToBeFrog));
            f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain();
            var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
            assertEquals(FullInitializedInputs.Availability.PUBLISHED, target(graph, y).availability());
            installGetterAndContext(f);
            assertEquals(ChunkStageAdmission.Result.APPLIED, finish(f, graph));
            assertSame(readySlot, slot(f.holder(y))); assertSame(ready, readySlot.join().orElse(null));
            assertTrue(Arrays.stream((ChunkAccess[]) field(f.world.cache, ServerChunkCache.class, "lastChunk")).anyMatch(cached -> cached == ready));
            assertEquals(1, f.world.sounds);
        } finally { close(f); }
    }

    @Test void originalAbsentNeighborResultStaysNullWithoutUnknownTicket() throws Exception {
        var f = new FullRegistrationFixture(true); var y = new ChunkPos(1, 0);
        try {
            saved(f, ChunkPos.ZERO, false, tag(15.8, 8, Tadpole.ticksToBeFrog));
            f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain();
            var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
            f.holder(y).eligibility(100, f.fixtureMap);
            f.runtime.inputs.changed(); f.drain();
            assertEquals(FullInitializedInputs.Availability.ABSENT, target(graph, y).availability());
            installGetterAndContext(f);
            int[] absent = {0};
            f.world.queryEndpoint = args -> {
                ChunkAccess value = f.world.cache.getChunk((int) args[0], (int) args[1], (ChunkStatus) args[2], (boolean) args[3]);
                if ((int) args[0] == y.x() && (int) args[1] == y.z()) { assertNull(value); absent[0]++; }
                return value;
            };
            assertEquals(ChunkStageAdmission.Result.APPLIED, finish(f, graph));
            assertTrue(absent[0] > 0); assertTrue(f.world.tickets.getTickets(y.pack()).isEmpty());
            assertEquals(1, f.world.sounds); assertNull(f.holder(y).getChunkIfPresentUnchecked(ChunkStatus.FULL));
        } finally { close(f); }
    }

    @Test void unrelatedGetterCannotBorrowPreparedNeighborFromAnActiveFinishingScope() throws Exception {
        var f = new FullRegistrationFixture(true);
        try {
            saved(f, ChunkPos.ZERO, false, tag(8, 8, Tadpole.ticksToBeFrog));
            f.start(ChunkPos.ZERO, ChunkStatus.FULL);
            f.holder(ChunkPos.ZERO).scheduleChunkGenerationTask(ChunkStatus.FULL, f.fixtureMap); f.drain();
            var graph = f.runtime.graph(f.holder(ChunkPos.ZERO)); installGetterAndContext(f);
            f.world.queryEndpoint = args -> f.world.cache.getChunk(3, 0, ChunkStatus.FULL, false);
            assertEquals(ChunkStageAdmission.Result.FAILED_BARRIER, finish(f, graph));
            assertTrue(f.barrier.record().isPresent()); assertEquals(0, f.world.sounds);
        } finally { close(f); }
    }

    @Test void retainedReceiptWithoutItsClaimedOperationCannotEnter() throws Exception {
        var f = new FullRegistrationFixture(true);
        try {
            f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain();
            var graph = f.runtime.graph(f.holder(ChunkPos.ZERO)); var receipt = graph.inputReceipt();
            var route = f.directory.resolve(graph.lifecycle.chunkKey()).orElseThrow();
            try (var lease = f.owner.leases().acquire();
                 var context = dev.phoenium.ownership.OwnershipContext.enter(f.ownerId, lease.epoch(), dev.phoenium.ownership.OperationCategory.OWNER_TASK);
                 var claim = new ChunkStageClaim(f.services.custody(), graph.finishing.stage(), route, lease, null)) {
                assertThrows(ChunkProtocolException.class, () -> FullFinishingQueries.enter(f.runtime, graph, claim, receipt));
            }
            assertFalse(graph.conversion().isDone()); assertTrue(f.world.inserted.isEmpty());
        } finally { close(f); }
    }

    @Test void originalVillagerBiomeCacheKeepsItsProtoAliasAcrossHolderPreparation() throws Exception {
        var f = new FullRegistrationFixture(true);
        try {
            saved(f, ChunkPos.ZERO, false, FullSavedVillagerInputTest.villager(8, 8, 0));
            installGetter(f);
            f.start(ChunkPos.ZERO, ChunkStatus.FULL);
            ChunkAccess before = f.world.cache.getChunk(0, 0, ChunkStatus.BIOMES, false);
            assertSame(f.input(ChunkPos.ZERO), before);
            f.drain();
            var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
            assertNotSame(before, target(graph, ChunkPos.ZERO).biomes().actual());
            assertSame(before.getSection(0).getBiomes(), target(graph, ChunkPos.ZERO).biomes().actual().getSection(0).getBiomes());
            installGetterAndContext(f);
            installOriginalVillagerBiomeLayer(f);
            assertEquals(ChunkStageAdmission.Result.APPLIED, finish(f, graph));
            var villager = assertInstanceOf(Villager.class, f.world.inserted.getFirst());
            assertEquals(8, villager.getX()); assertEquals(8, villager.getZ());
            assertTrue(villager.getBrain().getRunningBehaviors().isEmpty());
            assertSame(before, f.world.cache.getChunk(0, 0, ChunkStatus.BIOMES, false));
        } finally { close(f); }
    }

    @Test void originalVillagerCachedAbsentBiomeKeepsItsOriginalGeneratorFallback() throws Exception {
        var f = new FullRegistrationFixture(true);
        try {
            saved(f, ChunkPos.ZERO, false, FullSavedVillagerInputTest.villager(8, 8, 0));
            installGetter(f);
            f.world.visibleHolders.clear(); f.publishVisible();
            assertNull(f.world.cache.getChunk(0, 0, ChunkStatus.BIOMES, false));
            f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain();
            var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
            assertNotNull(target(graph, ChunkPos.ZERO).biomes());
            installGetterAndContext(f); installOriginalVillagerBiomeLayer(f);
            var biome = f.world.level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BIOME)
                    .getOrThrow(net.minecraft.world.level.biome.Biomes.PLAINS);
            var source = new net.minecraft.world.level.biome.FixedBiomeSource(biome);
            var generator = stub(ChunkGenerator.class, List.of(new Answer(
                    ChunkGenerator.class.getMethod("getBiomeSource"), ignored -> source)));
            set(f.fixtureMap, ChunkMap.class, "worldGenContext",
                    new WorldGenContext(f.world.level, generator, null, null, Runnable::run, ignored -> {}));
            var randomState = allocate(net.minecraft.world.level.levelgen.RandomState.class);
            set(randomState, net.minecraft.world.level.levelgen.RandomState.class, "sampler", net.minecraft.world.level.biome.Climate.empty());
            set(f.fixtureMap, ChunkMap.class, "randomState", randomState);
            int[] originalNullResults = {0};
            f.world.queryEndpoint = args -> {
                ChunkAccess result = f.world.cache.getChunk((int) args[0], (int) args[1], (ChunkStatus) args[2], (boolean) args[3]);
                if (args[2] == ChunkStatus.BIOMES && result == null) originalNullResults[0]++;
                return result;
            };
            assertEquals(ChunkStageAdmission.Result.APPLIED, finish(f, graph));
            assertInstanceOf(Villager.class, f.world.inserted.getFirst());
            assertTrue(originalNullResults[0] >= 2, "Construction and saved-position schedules retain original cached absence");
            assertNull(f.world.cache.getChunk(0, 0, ChunkStatus.BIOMES, false));
            assertTrue(f.barrier.record().isEmpty());
        } finally { close(f); }
    }

    private static void installOriginalVillagerBiomeLayer(FullRegistrationFixture f) throws Exception {
        var manager = new BiomeManager(f.world.level::getNoiseBiome, 0L);
        var builder = EnvironmentAttributeSystem.builder();
        var original = EnvironmentAttributeSystem.class.getDeclaredMethod("addBiomeLayerForAttribute",
                EnvironmentAttributeSystem.Builder.class, EnvironmentAttribute.class, BiomeManager.class);
        original.setAccessible(true);
        original.invoke(null, builder, EnvironmentAttributes.VILLAGER_ACTIVITY, manager);
        original.invoke(null, builder, EnvironmentAttributes.BABY_VILLAGER_ACTIVITY, manager);
        f.world.level.setEnvironmentAttributes(builder.build());
    }

    static void installGetterAndContext(FullRegistrationFixture f) throws Exception {
        installGetter(f);
        var data = new PrimaryLevelData(new LevelSettings("query", GameType.CREATIVE,
                LevelSettings.DifficultySettings.DEFAULT, true, WorldDataConfiguration.DEFAULT),
                PrimaryLevelData.SpecialWorldProperty.NONE, com.mojang.serialization.Lifecycle.stable());
        set(f.world.level, Level.class, "levelData", data);
        set(f.world.level, ServerLevel.class, "serverLevelData", data);
        set(f.world.level, ServerLevel.class, "environmentAttributes", EnvironmentAttributeSystem.builder().build());
    }
    private static void installGetter(FullRegistrationFixture f) throws Exception {
        set(f.world.cache, ServerChunkCache.class, "level", f.world.level);
        set(f.world.cache, ServerChunkCache.class, "mainThreadProcessor", allocate(Class.forName("net.minecraft.server.level.ServerChunkCache$MainThreadExecutor")));
        f.world.queryEndpoint = args -> f.world.cache.getChunk((int) args[0], (int) args[1], (ChunkStatus) args[2], (boolean) args[3]);
    }
    private static ChunkStageAdmission.Result finish(FullRegistrationFixture f, FullRegistrationRuntime.Graph graph) {
        graph.finishing.prepareFullServices();
        return new ChunkStageExecutor(new ChunkStageAdmission(f.services.custody(), f.directory,
                Map.of(f.ownerId, f.owner.leases()), f.barrier)).execute(graph.finishing, f.ownerId, new TopologyGeneration(1));
    }
    private static CompletableFuture<ChunkResult<ChunkAccess>> slot(GenerationChunkHolder holder) {
        return ((FullHolderEvidence) holder).phoenium$futures().get(ChunkStatus.FULL.getIndex());
    }
    private static void close(FullRegistrationFixture f) {
        f.stop(); FullFinishingServices.stopped(f.world.level.getServer());
    }
}

