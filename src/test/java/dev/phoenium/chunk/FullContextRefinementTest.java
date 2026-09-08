package dev.phoenium.chunk;

import dev.phoenium.core.TopologyGeneration;
import dev.phoenium.ownership.OwnershipContext;
import dev.phoenium.topology.RegionRoute;
import dev.phoenium.topology.RoutingKey;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static dev.phoenium.chunk.FullDecisionWitnessSupport.set;
import static org.junit.jupiter.api.Assertions.*;

/** Exact original registrar/holder inputs remain pending through a distinct owner-read phase. */
final class FullContextRefinementTest {
    @BeforeAll static void bootstrap() { FullProductionRegistrationTest.bootstrap(); }

    @Test void observationPinsSourceButDoesNotClaimOrInvokeOriginalFullThenFinishingRunsOnce() throws Exception {
        var f = prepared();
        try {
            var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
            var actual = graph.prepared().orElseThrow().actual();
            var stage = graph.finishing.stage();
            assertNull(f.holder(ChunkPos.ZERO).getChunkIfPresent(ChunkStatus.FULL),
                    "context discovery must be allowed while the prepared FULL result is still unpublished");
            assertFalse(graph.finishing.outcome().isDone());
            var originalCalls = new AtomicInteger(); var reads = new AtomicInteger();
            var retained = new AtomicReference<FullContextReadClaim>();
            set(actual, LevelChunk.class, "postLoad", (LevelChunk.PostLoadProcessor) chunk -> originalCalls.incrementAndGet());
            var executor = new ChunkStageExecutor(new ChunkStageAdmission(f.services.custody(), f.directory,
                    Map.of(f.ownerId, f.owner.leases()), f.barrier));
            var before = f.services.custody().stageState(stage);
            var detached = new Object();
            var result = FullContextRefinement.read(f.runtime, graph, route(f), Set.of(stage.work().lifecycle().chunkKey()), claim -> {
                claim.require(graph, stage.work().lifecycle().chunkKey()); retained.set(claim); reads.incrementAndGet();
                assertFalse(f.services.custody().gate().isHeldByCurrentThread());
                assertTrue(f.owner.leases().hasActiveLease());
                assertTrue(f.services.custody().hasActiveUse(graph.work));
                assertFalse(f.services.custody().hasActiveClaim(graph.work));
                assertEquals(before, f.services.custody().stageState(stage));
                assertFalse(FullFinishingServices.finishing());
                assertEquals(ChunkStageAdmission.Result.NOT_READY,
                        executor.execute(graph.finishing, f.ownerId, route(f).generation()));
                assertFalse(graph.finishing.outcome().isDone());
                assertTrue(graph.finishing.retainsInputOrContinuation());
                return detached;
            });
            assertEquals(FullContextRefinement.State.OBSERVED, result.state()); assertSame(detached, result.value());
            assertEquals(1, reads.get()); assertEquals(0, originalCalls.get());
            assertEquals(before, f.services.custody().stageState(stage));
            assertFalse(f.services.custody().hasActiveUse(graph.work));
            assertTrue(OwnershipContext.current().isEmpty()); assertFalse(f.owner.leases().hasActiveLease());
            assertThrows(ChunkProtocolException.class, () -> retained.get().require(graph, graph.lifecycle.chunkKey()));
            FullAuthoritativeFinishingTest.context(f); f.runtime.inputs.changed(); f.drain();
            assertSame(actual, graph.adjacent().join().orElse(null));
            assertEquals(1, originalCalls.get()); assertEquals(1L, f.runtime.measurements().get("finishingCalls"));
            assertEquals(1L, f.runtime.measurements().get("publicationCalls")); assertFalse(f.barrier.tripped());
        } finally { stop(f); }
    }

    @Test void aGraphFromAnotherRuntimeCannotBeReadOrRetained() throws Exception {
        var source = prepared();
        var foreign = prepared();
        try {
            var graph = source.runtime.graph(source.holder(ChunkPos.ZERO));
            var calls = new AtomicInteger();
            assertThrows(ChunkProtocolException.class, () -> FullContextRefinement.read(foreign.runtime, graph,
                    route(source), Set.of(graph.lifecycle.chunkKey()), claim -> {
                        calls.incrementAndGet(); return "foreign";
                    }));
            assertEquals(0, calls.get());
            assertFalse(source.services.custody().hasActiveUse(graph.work));
            assertFalse(foreign.services.custody().hasActiveUse(graph.work));
            assertFalse(source.owner.leases().hasActiveLease());
            assertFalse(foreign.owner.leases().hasActiveLease());
            assertFalse(graph.finishing.outcome().isDone());
        } finally {
            stop(foreign); stop(source);
        }
    }

    @Test void observationFailureReleasesItsUseAndDoesNotConsumeTheOriginalContinuation() throws Exception {
        var f = prepared();
        try {
            var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
            var exact = new AssertionError("dependency producer failed");
            assertSame(exact, assertThrows(AssertionError.class, () -> FullContextRefinement.read(f.runtime, graph,
                    route(f), Set.of(graph.lifecycle.chunkKey()), claim -> { throw exact; })));
            assertFalse(f.services.custody().hasActiveUse(graph.work)); assertFalse(f.owner.leases().hasActiveLease());
            assertTrue(OwnershipContext.current().isEmpty()); assertFalse(graph.conversion().isDone());
            assertTrue(graph.finishing.retainsInputOrContinuation());
            assertEquals(FullContextRefinement.State.OBSERVED, FullContextRefinement.read(f.runtime, graph,
                    route(f), Set.of(graph.lifecycle.chunkKey()), claim -> "retry").state());
            FullAuthoritativeFinishingTest.context(f); f.runtime.inputs.changed(); f.drain();
            assertFalse(graph.conversion().isCompletedExceptionally()); assertTrue(graph.adjacent().isDone());
        } finally { stop(f); }
    }

    @Test void swallowedUnfinishedWaitCannotBeReportedAsAnObservation() throws Exception {
        var f = prepared();
        try {
            var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
            var caught = new AtomicReference<ChunkProtocolException>();
            var failure = assertThrows(ChunkProtocolException.class, () -> FullContextRefinement.read(f.runtime, graph,
                    route(f), Set.of(graph.lifecycle.chunkKey()), claim -> {
                        try { ChunkSynchronousAccessGuard.beforeWait(() -> false); }
                        catch (ChunkProtocolException problem) { caught.set(problem); }
                        return "must not escape";
                    }));
            assertSame(caught.get(), failure);
            assertFalse(f.services.custody().hasActiveUse(graph.work)); assertFalse(f.owner.leases().hasActiveLease());
            assertFalse(graph.finishing.outcome().isDone()); assertEquals(0L, f.runtime.measurements().get("finishingCalls"));
        } finally { stop(f); }
    }

    @Test void reentrantShutdownDefersSourceAcknowledgementUntilTheReadLeaseExits() throws Exception {
        var f = prepared();
        try {
            var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
            var result = FullContextRefinement.read(f.runtime, graph, route(f), Set.of(graph.lifecycle.chunkKey()), claim -> {
                f.runtime.shutdown();
                claim.require(graph, graph.lifecycle.chunkKey());
                assertNotNull(graph.inputReceipt()); assertTrue(graph.preparedConsumers() > 0);
                assertFalse(graph.finishing.outcome().isDone()); assertFalse(graph.conversion().isDone());
                assertThrows(ChunkProtocolException.class, () -> f.services.custody().releaseRetiredWork(graph.work));
                return "retired result must not be installed";
            });
            assertEquals(FullContextRefinement.State.TERMINAL, result.state()); assertNull(result.value());
            assertTrue(graph.conversion().isCompletedExceptionally()); assertNull(graph.inputReceipt());
            assertInstanceOf(ChunkStageOutcome.Control.class, graph.finishing.outcome().join());
            assertSame(net.minecraft.server.level.GenerationChunkHolder.UNLOADED_CHUNK, graph.adjacent().join());
            assertFalse(f.services.custody().hasActiveUse(graph.work)); assertFalse(f.owner.leases().hasActiveLease());
            assertEquals(0L, f.runtime.measurements().get("finishingCalls")); assertFalse(f.barrier.tripped());
        } finally { stop(f); }
    }

    @Test void staleAndBusyOwnersDoNotReadOrRetainAndAnUndeclaredPositionHasNoAuthority() throws Exception {
        var f = prepared();
        try {
            var graph = f.runtime.graph(f.holder(ChunkPos.ZERO)); var key = graph.lifecycle.chunkKey();
            var calls = new AtomicInteger(); var current = route(f);
            var stale = new RegionRoute(current.region(), new TopologyGeneration(999), current.ownerEpoch());
            assertEquals(FullContextRefinement.State.STALE, FullContextRefinement.read(f.runtime, graph, stale,
                    Set.of(key), claim -> calls.incrementAndGet()).state());
            try (var occupied = f.owner.leases().acquire()) {
                assertEquals(FullContextRefinement.State.PENDING, FullContextRefinement.read(f.runtime, graph, current,
                        Set.of(key), claim -> calls.incrementAndGet()).state());
            }
            assertEquals(0, calls.get()); assertFalse(f.services.custody().hasActiveUse(graph.work));
            FullContextRefinement.read(f.runtime, graph, current, Set.of(key), claim -> {
                assertThrows(ChunkProtocolException.class, () -> claim.require(graph, new RoutingKey(f.worldId, 9, 9)));
                assertThrows(ChunkProtocolException.class, () -> FullContextRefinement.read(f.runtime, graph, current,
                        Set.of(key), nested -> "nested admission must not enter"));
                return null;
            });
        } finally { stop(f); }
    }

    @Test void holderReplacementDuringObservationCannotPublishTheOldResultOrReclaimItsLiveRead() throws Exception {
        var f = prepared();
        try {
            var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
            var result = FullContextRefinement.read(f.runtime, graph, route(f), Set.of(graph.lifecycle.chunkKey()), claim -> {
                f.runtime.retire(graph.holder);
                assertFalse(f.services.custody().canReleaseRetiredLifecycle(graph.lifecycle));
                assertFalse(graph.conversion().isDone());
                claim.require(graph, graph.lifecycle.chunkKey());
                return "old";
            });
            assertEquals(FullContextRefinement.State.TERMINAL, result.state()); assertNull(result.value());
            assertTrue(graph.conversion().isCompletedExceptionally());
            assertEquals(FullContextRefinement.State.TERMINAL, FullContextRefinement.read(f.runtime, graph, route(f),
                    Set.of(graph.lifecycle.chunkKey()), claim -> fail("retired source read")).state());
            assertFalse(f.owner.leases().hasActiveLease()); assertFalse(f.services.custody().hasActiveUse(graph.work));
        } finally { stop(f); }
    }

    private static FullRegistrationFixture prepared() throws Exception {
        var fixture = new FullRegistrationFixture(true);
        fixture.start(ChunkPos.ZERO, ChunkStatus.FULL); fixture.drain();
        assertTrue(fixture.runtime.graph(fixture.holder(ChunkPos.ZERO)).prepared().isPresent());
        return fixture;
    }
    private static RegionRoute route(FullRegistrationFixture f) {
        return f.directory.resolve(new RoutingKey(f.worldId, 0, 0)).orElseThrow();
    }
    private static void stop(FullRegistrationFixture f) {
        f.stop(); FullFinishingServices.stopped(f.world.level.getServer());
        FullInitializedInputBindingTest.assertCleanup(f);
    }
}
