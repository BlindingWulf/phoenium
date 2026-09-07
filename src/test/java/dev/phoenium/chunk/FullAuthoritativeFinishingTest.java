package dev.phoenium.chunk;

import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.level.*;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.storage.PrimaryLevelData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static dev.phoenium.chunk.FullDecisionWitnessSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Installed registrar, generated original suffix and original holder handler with controlled world endpoints. */
class FullAuthoritativeFinishingTest {
    @BeforeAll static void bootstrap() { FullProductionRegistrationTest.bootstrap(); }
    static void context(FullRegistrationFixture fixture) throws Exception {
        var settings = new LevelSettings("full", GameType.CREATIVE, LevelSettings.DifficultySettings.DEFAULT, true, WorldDataConfiguration.DEFAULT);
        var data = new PrimaryLevelData(settings,
                PrimaryLevelData.SpecialWorldProperty.NONE, com.mojang.serialization.Lifecycle.stable());
        set(fixture.world.level, Level.class, "levelData", data);
        fixture.world.level.setEnvironmentAttributes(EnvironmentAttributeSystem.builder().build());
    }
    @Test void actualConflictingInputOwnersConvergeThenFinishBothOriginalSavedCallbacks() throws Exception {
        var fixture = new FullRegistrationFixture(true);
        var x = ChunkPos.ZERO; var y = new ChunkPos(1, 0);
        try {
            var generation = new dev.phoenium.core.TopologyGeneration(2);
            var xKey = new dev.phoenium.topology.RoutingKey(fixture.worldId, 0, 0);
            var yKey = new dev.phoenium.topology.RoutingKey(fixture.worldId, 1, 0);
            fixture.directory.publish(generation, java.util.Map.of(
                    xKey, new dev.phoenium.topology.RegionRoute(fixture.ownerId, generation, fixture.owner.ownerEpoch()),
                    yKey, new dev.phoenium.topology.RegionRoute(fixture.otherId, generation, fixture.other.ownerEpoch())));
            FullInitializedInputBindingTest.saved(fixture, x, false, FullInitializedInputBindingTest.tag(15.8, 8, 24000));
            FullInitializedInputBindingTest.saved(fixture, y, false, FullInitializedInputBindingTest.tag(16.2, 8, 24000));
            fixture.start(x, ChunkStatus.FULL); fixture.start(y, ChunkStatus.FULL); fixture.drain();
            var first = fixture.runtime.graph(fixture.holder(x)); var second = fixture.runtime.graph(fixture.holder(y));
            assertFalse(first.conversion().isDone()); assertFalse(second.conversion().isDone());
            FullFinishingQueriesTest.installGetterAndContext(fixture);
            fixture.runtime.inputs.changed(); fixture.drain();
            assertTrue(first.adjacent().isDone(), () -> fixture.runtime.measurements() + " first=" + first.inputEvaluation()
                    + " second=" + second.inputEvaluation() + " routes=" + fixture.directory.snapshot());
            assertTrue(second.adjacent().isDone());
            assertFalse(first.conversion().isCompletedExceptionally(), first.conversion().toString());
            assertFalse(second.conversion().isCompletedExceptionally(), second.conversion().toString());
            assertEquals(fixture.directory.resolve(xKey).orElseThrow(), fixture.directory.resolve(yKey).orElseThrow());
            assertEquals(4, fixture.world.inserted.size(), "both converted frogs and both original loaded roots execute once");
            assertEquals(2L, fixture.runtime.measurements().get("finishingCalls"));
            assertEquals(2L, fixture.runtime.measurements().get("publicationCalls"));
            assertFalse(fixture.barrier.tripped());
            assertFalse(fixture.owner.leases().hasActiveLease()); assertFalse(fixture.other.leases().hasActiveLease());
        } finally { fixture.stop(); FullFinishingServices.stopped(fixture.world.level.getServer()); }
        FullInitializedInputBindingTest.assertCleanup(fixture);
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void originalFinishingAndSeparatePublicationCompleteExactlyOnce(boolean reload) throws Exception {
        var fixture = new FullRegistrationFixture(true);
        try {
            context(fixture);
            FullInitializedInputBindingTest.saved(fixture, ChunkPos.ZERO, reload, FullInitializedInputBindingTest.tag(8, 8, 1));
            fixture.start(ChunkPos.ZERO, ChunkStatus.FULL);
            var graph = fixture.runtime.graph(fixture.holder(ChunkPos.ZERO));
            fixture.drain();
            assertTrue(graph.conversion().isDone(), graph.inputEvaluation().toString());
            assertFalse(graph.conversion().isCompletedExceptionally(), graph.conversion().toString());
            var actual = assertInstanceOf(LevelChunk.class, graph.conversion().join());
            assertEquals(true, field(actual, LevelChunk.class, "loaded"));
            assertNull(field(actual, LevelChunk.class, "postLoad"));
            assertSame(actual, graph.adjacent().join().orElse(null));
            assertSame(actual, fixture.holder(ChunkPos.ZERO).getChunkIfPresentUnchecked(ChunkStatus.FULL));
            assertInstanceOf(ChunkStageOutcome.Applied.class, graph.finishing.outcome().join());
            assertInstanceOf(ChunkStageOutcome.Applied.class, graph.publication.outcome().join());
            assertNotEquals(graph.finishing.stage(), graph.publication.stage());
            assertEquals(0, fixture.runtime.inputs.measurements().get("consumers"));
            var publication = fixture.runtime.published.observe(graph.holder);
            assertNotNull(publication);
            assertEquals(reload ? FullPublishedInputs.Origin.ADMITTED_IMPOSTER : FullPublishedInputs.Origin.ADMITTED_PROTO, publication.origin());
            assertSame(actual, publication.actual());
            fixture.runGenerationTasks(); fixture.drain();
            assertFalse(fixture.barrier.tripped());
        } finally { fixture.stop(); FullFinishingServices.stopped(fixture.world.level.getServer()); }
        FullInitializedInputBindingTest.assertCleanup(fixture);
    }

    @Test void failedOriginalPostLoadKeepsItsPrefixAndIndependentPreparedUseWithoutReplay() throws Exception {
        var f = new FullRegistrationFixture(true);
        var delayed = net.minecraft.util.thread.BlockableEventLoop.class.getDeclaredField("delayedCrash"); delayed.setAccessible(true);
        Object previous = delayed.get(null);
        AutoCloseable retained = null;
        try {
            f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain();
            var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
            var actual = graph.prepared().orElseThrow().actual();
            retained = graph.retainPrepared();
            int[] prefix = {0}; var exact = new IllegalStateException("original post-load failure after committed prefix");
            LevelChunk.PostLoadProcessor callback = chunk -> { prefix[0]++; throw exact; };
            set(actual, LevelChunk.class, "postLoad", callback);
            context(f); f.runtime.inputs.changed(); f.drain();
            assertSame(exact, assertThrows(java.util.concurrent.CompletionException.class, graph.conversion()::join).getCause());
            assertSame(exact, assertInstanceOf(ChunkStageOutcome.VanillaFailure.class, graph.finishing.outcome().join()).failure());
            assertInstanceOf(ChunkStageOutcome.Applied.class, graph.publication.outcome().join());
            assertTrue(graph.adjacent().join().isSuccess()); assertNull(graph.adjacent().join().orElse(null));
            assertNotNull(delayed.get(null), "the original adjacent handler still publishes its delayed crash");
            assertSame(callback, field(actual, LevelChunk.class, "postLoad"));
            assertEquals(false, field(actual, LevelChunk.class, "loaded"));
            assertEquals(1, graph.preparedConsumers()); assertSame(actual, graph.prepared().orElseThrow().actual());
            f.runtime.pump.deliverPublished(graph.finishing); f.drain();
            assertEquals(1, prefix[0]); assertEquals(1L, f.runtime.measurements().get("finishingCalls"));
            retained.close(); retained = null;
        } finally {
            if (retained != null) retained.close();
            f.stop(); FullFinishingServices.stopped(f.world.level.getServer()); delayed.set(null, previous);
        }
        FullInitializedInputBindingTest.assertCleanup(f);
    }

    @Test void reentrantShutdownInsideOriginalSuffixDefersInputAcknowledgementUntilItsClaimExits() throws Exception {
        var f = new FullRegistrationFixture(true);
        try {
            f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain();
            var graph = f.runtime.graph(f.holder(ChunkPos.ZERO)); var actual = graph.prepared().orElseThrow().actual();
            int[] prefix = {0};
            set(actual, LevelChunk.class, "postLoad", (LevelChunk.PostLoadProcessor) chunk -> {
                prefix[0]++; assertTrue(f.owner.leases().hasActiveLease());
                f.runtime.shutdown();
                assertFalse(graph.conversion().isDone()); assertFalse(graph.adjacent().isDone());
                assertNotNull(graph.inputReceipt(), "active context/query consumers are acknowledged only after their original callback exits");
                assertTrue(graph.preparedConsumers() > 0);
            });
            context(f); f.runtime.inputs.changed(); f.drain();
            assertEquals(1, prefix[0]);
            assertInstanceOf(ChunkStageOutcome.Applied.class, graph.finishing.outcome().join());
            assertEquals(true, field(actual, LevelChunk.class, "loaded"), "original already claimed suffix preserves its committed execution");
            assertSame(net.minecraft.server.level.GenerationChunkHolder.UNLOADED_CHUNK, graph.adjacent().join());
            assertTrue(graph.conversion().isCompletedExceptionally());
            assertFalse(f.owner.leases().hasActiveLease()); assertFalse(f.barrier.tripped());
        } finally { f.stop(); FullFinishingServices.stopped(f.world.level.getServer()); }
        FullInitializedInputBindingTest.assertCleanup(f);
    }
}
