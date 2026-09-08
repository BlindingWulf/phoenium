package dev.phoenium.chunk;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.server.MinecraftServer;
import org.junit.jupiter.api.Test;

import static dev.phoenium.chunk.FullDecisionWitnessSupport.Answer;
import static dev.phoenium.chunk.FullDecisionWitnessSupport.stub;
import static org.junit.jupiter.api.Assertions.*;

/** The terminal wrapper must retain every failure while it still closes its named services. */
final class FullServerShutdownFailureOrderTest {
    @Test void failedAdmittedDrainSettlesRetainedWorkBeforeNativeResourceClosureInSameNamedTurn() throws Exception {
        var events = new ArrayList<String>();
        MinecraftServer server = stub(MinecraftServer.class, List.of());
        var drainFailure = new IllegalStateException("accepted callback failed");
        var originalFailure = new IllegalArgumentException("original storage close failed");
        var field = FullServerShutdown.class.getDeclaredField("METADATA"); field.setAccessible(true);
        Object metadata = field.get(null);
        Throwable failure = assertThrows(IllegalStateException.class, () -> FullServerShutdown.run(server,
                () -> { assertFalse(Thread.holdsLock(metadata)); events.add("admission-closed"); },
                () -> {
                    assertFalse(Thread.holdsLock(metadata));
                    FullFinishingServices.requireServer(server, FullFinishingServices.Kind.CONNECTIONS);
                    events.add("owned-drain"); throw drainFailure;
                },
                () -> {
                    assertFalse(Thread.holdsLock(metadata));
                    FullFinishingServices.requireServer(server, FullFinishingServices.Kind.CONNECTIONS);
                    assertTrue(FullServerShutdown.failedStop());
                    events.add("original-stop"); throw originalFailure;
                }, () -> {
                    assertFalse(Thread.holdsLock(metadata));
                    FullFinishingServices.requireServer(server, FullFinishingServices.Kind.CONNECTIONS);
                    assertTrue(FullServerShutdown.failedStop());
                    events.add("runtime-settled");
                }));
        assertSame(drainFailure, failure);
        assertArrayEquals(new Throwable[]{originalFailure}, failure.getSuppressed());
        assertEquals(List.of("admission-closed", "owned-drain", "runtime-settled", "original-stop"), events);
        assertFalse(FullServerShutdown.failedStop(), "the terminal exception cannot leave a save-bypass scope behind");
        assertThrows(ChunkProtocolException.class, () -> FullFinishingServices.forServer(server));
    }

    @Test void failedStopKeepsRealPendingWriteUntilAcknowledgedThenClosesEveryIndependentResource() throws Exception {
        MinecraftServer server = stub(MinecraftServer.class, List.of());
        var barrier = new IllegalStateException("original failed regional callback");
        var firstClose = new IllegalArgumentException("first storage close failed");
        var secondClose = new IllegalStateException("second storage close failed");
        var write = new java.util.concurrent.CompletableFuture<Void>();
        var awaitingWrite = new java.util.concurrent.CountDownLatch(1);
        var closed = new AtomicInteger();
        var acknowledgements = new AtomicInteger();
        var stopped = java.util.concurrent.CompletableFuture.supplyAsync(() ->
                assertThrows(IllegalStateException.class, () -> FullServerShutdown.run(server,
                        () -> { }, () -> { throw barrier; }, () -> {
                            assertTrue(write.isDone());
                            FullServerShutdown.cleanup(() -> { closed.incrementAndGet(); throw firstClose; });
                            FullServerShutdown.cleanup(() -> { closed.incrementAndGet(); throw secondClose; });
                            FullServerShutdown.cleanup(closed::incrementAndGet);
                        }, () -> {
                            awaitingWrite.countDown();
                            write.join();
                            acknowledgements.incrementAndGet();
                        })));
        try {
            assertTrue(awaitingWrite.await(5, TimeUnit.SECONDS));
            assertFalse(stopped.isDone());
            assertEquals(0, closed.get(), "resource closure must not precede real write settlement");
            assertFalse(FullServerShutdown.failedStop(), "a failed stop scope cannot alter another thread's saves");
        } finally { write.complete(null); }
        assertSame(barrier, stopped.get(5, TimeUnit.SECONDS));
        assertEquals(1, acknowledgements.get());
        assertEquals(3, closed.get());
        assertArrayEquals(new Throwable[]{firstClose, secondClose}, barrier.getSuppressed());
    }

    @Test void failedAcknowledgementStillClosesResourcesOnceWithoutReplayingOrReturningSuccess() throws Exception {
        MinecraftServer server = stub(MinecraftServer.class, List.of());
        var barrier = new IllegalStateException("unreachable admitted work");
        var acknowledgementFailure = new IllegalArgumentException("terminal write acknowledgement failed");
        var acknowledgements = new AtomicInteger();
        var closes = new AtomicInteger();
        Throwable failure = assertThrows(IllegalStateException.class, () -> FullServerShutdown.run(server,
                () -> { }, () -> { throw barrier; }, () -> {
                    assertTrue(FullServerShutdown.failedStop());
                    FullServerShutdown.run(server, () -> fail("nested original replay"),
                            () -> fail("nested acknowledgement replay"));
                    FullServerShutdown.cleanup(closes::incrementAndGet);
                }, () -> { acknowledgements.incrementAndGet(); throw acknowledgementFailure; }));
        assertSame(barrier, failure);
        assertArrayEquals(new Throwable[]{acknowledgementFailure}, barrier.getSuppressed());
        assertEquals(1, acknowledgements.get());
        assertEquals(1, closes.get());
        assertFalse(FullServerShutdown.failedStop());
    }

    @Test void cleanupOutsideFailedStopPreservesImmediateOriginalException() {
        var failure = new IllegalArgumentException("ordinary close failure");
        assertFalse(FullServerShutdown.failedStop());
        assertSame(failure, assertThrows(IllegalArgumentException.class,
                () -> FullServerShutdown.cleanup(() -> { throw failure; })));
    }
    @Test
    void reentrantStopRetainsTheOuterDrainAndCannotTerminateServicesEarly() throws Exception {
        var events = new ArrayList<String>();
        MinecraftServer server = stub(MinecraftServer.class, List.of());
        var service = FullFinishingServices.forServer(server);
        FullServerShutdown.run(server, () -> events.add("admission-closed"), () -> {
            events.add("drain-start");
            FullServerShutdown.run(server, () -> fail("nested stop must not replay vanilla"),
                    () -> fail("nested stop must not acknowledge an active outer drain"));
            assertSame(service, FullFinishingServices.forServer(server));
            events.add("drain-end");
        }, () -> events.add("runtime-acknowledgement"));
        FullServerShutdown.run(server, () -> fail("settled stop must not replay vanilla"),
                () -> fail("settled stop must not acknowledge twice"));
        assertEquals(List.of("admission-closed", "drain-start", "drain-end", "runtime-acknowledgement"), events);
        service.legacyOwners.termination().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertThrows(ChunkProtocolException.class, () -> FullFinishingServices.forServer(server));
    }

    @Test
    void anOwnedStopRequestCannotRunCleanupUnderTheInterruptedMutation() throws Exception {
        MinecraftServer server = stub(MinecraftServer.class, List.of());
        var region = new dev.phoenium.region.RegionId(new dev.phoenium.core.WorldId("stop-test"), "owner");
        var leases = new dev.phoenium.ownership.RegionLeaseManager(region);
        try (var lease = leases.acquire(); var owner = dev.phoenium.ownership.OwnershipContext.enter(
                region, lease.epoch(), dev.phoenium.ownership.OperationCategory.OWNER_TASK)) {
            assertThrows(ChunkProtocolException.class, () -> FullServerShutdown.run(server,
                    () -> fail("owned caller cannot close admission"), () -> fail("owned caller cannot drain"),
                    () -> fail("owned caller cannot clean up")));
            lease.requireCurrent();
        }
        var calls = new AtomicInteger();
        FullServerShutdown.run(server, calls::incrementAndGet, calls::incrementAndGet);
        assertEquals(2, calls.get(), "refused nested mutation must not mark the real stop complete");
    }

    @Test
    void originalAndAcknowledgementFailuresStillRunNamedCleanupExactlyOnce() throws Exception {
        var events = new ArrayList<String>();
        var serviceLookups = new AtomicInteger();
        var originalFailure = new IllegalStateException("original stop failure");
        var acknowledgementFailure = new IllegalArgumentException("runtime acknowledgement failure");
        var namedServiceFailure = new IllegalStateException("named service failure");
        MinecraftServer server = stub(MinecraftServer.class, List.of(new Answer(
                MinecraftServer.class.getMethod("services"), ignored -> {
                    serviceLookups.incrementAndGet();
                    events.add("named-service");
                    throw namedServiceFailure;
                })));
        var service = FullFinishingServices.forServer(server);

        Throwable failure = assertThrows(IllegalStateException.class, () -> FullServerShutdown.run(server,
                () -> {
                    events.add("original-stop");
                    throw originalFailure;
                },
                () -> {
                    events.add("runtime-acknowledgement");
                    throw acknowledgementFailure;
                }));

        assertSame(originalFailure, failure, "the first stop failure must remain the thrown identity");
        assertEquals(List.of("original-stop", "runtime-acknowledgement", "named-service"), events,
                "acknowledgement and named cleanup must follow the original stop exactly once");
        assertEquals(1, serviceLookups.get(), "named service cleanup must not be retried");
        assertArrayEquals(new Throwable[]{acknowledgementFailure, namedServiceFailure}, failure.getSuppressed(),
                "later acknowledgement and cleanup failures must remain in execution order");

        service.legacyOwners.termination().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(1L, service.legacyOwners.measurements().get("producerTerminated"),
                "the named legacy-owner producer must terminate after a prior failure");
        assertThrows(ChunkProtocolException.class, () -> FullFinishingServices.forServer(server),
                "terminal cleanup must detach the service and prevent resurrection");

        // A repeated terminal acknowledgement is a no-op and cannot invoke the named service again.
        FullFinishingServices.stopped(server);
        assertEquals(1, serviceLookups.get(), "terminal cleanup must be exactly once");
        FullServerShutdown.run(server, () -> fail("failed original stop cannot be replayed"),
                () -> fail("failed acknowledgement cannot be replayed"));
    }
}
