package dev.phoenium.save;

import dev.phoenium.core.*;
import dev.phoenium.failure.FailureBarrier;
import dev.phoenium.ownership.OwnershipContext;
import dev.phoenium.region.*;
import dev.phoenium.scheduler.*;
import dev.phoenium.service.RegionalServiceDispatcher;
import dev.phoenium.topology.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RegionalSaveCoordinatorTest {
    @Test void hardBarrierRetainsUnfinishedWritesAndNeverRestoresDirtyStateFromTheStopCaller() {
        Fixture f = new Fixture(); Source source = f.source();
        var attempt = f.capture(source); var disk = new CompletableFuture<Void>();
        attempt.submitted(disk); attempt.finishBody();
        var barrierFailure = new IllegalStateException("failed gameplay callback");
        f.barrier.trip(barrierFailure, f.directory.snapshot().generation(), f.a.ownerEpoch(), null);
        assertFalse(f.saves.acknowledgeAfterBarrier(f.barrier));
        assertEquals(1, f.saves.pending()); assertEquals(0, source.releases);
        disk.completeExceptionally(new IllegalArgumentException("original disk failure"));
        assertTrue(f.saves.acknowledgeAfterBarrier(f.barrier));
        assertEquals(0, f.saves.pending()); assertEquals(1, source.releases);
        assertEquals(0, source.restored, "a hard barrier cannot be bypassed to mutate dirty state");
        assertEquals(0L, f.saves.measurements().get("acknowledgements"));
        assertEquals(1L, f.saves.measurements().get("failedSettlements"));
        assertSame(barrierFailure, assertThrows(IllegalStateException.class, f.saves::requireFlushed));
        assertFalse(f.saves.pumpOne(), "terminal failure must leave no queued source reference");
    }

    @Test void reentrantFailedSettlementReleasesEachSourceOnceAndPreservesReleaseFailure() {
        Fixture f = new Fixture(); Source first = f.source(), second = f.source();
        f.capture(first).finishBody(); f.capture(second).finishBody();
        var failure = new IllegalStateException("stop barrier");
        var releaseFailure = new IllegalArgumentException("source release observer");
        f.barrier.trip(failure, f.directory.snapshot().generation(), f.a.ownerEpoch(), null);
        first.afterRelease = () -> f.saves.acknowledgeAfterBarrier(f.barrier);
        second.afterRelease = () -> { throw releaseFailure; };
        assertTrue(f.saves.acknowledgeAfterBarrier(f.barrier));
        assertEquals(1, first.releases); assertEquals(1, second.releases);
        assertEquals(0, f.saves.pending());
        assertSame(failure, assertThrows(IllegalStateException.class, f.saves::requireFlushed));
        assertArrayEquals(new Throwable[]{releaseFailure}, failure.getSuppressed());
    }
    @Test void aWriteIsNotSavedUntilTheOriginalFutureAndOwnedAcknowledgementFinish() {
        Fixture f = new Fixture(); Source source = f.source();
        var attempt = f.capture(source); var disk = new CompletableFuture<Void>();
        assertSame(disk, attempt.submitted(disk)); attempt.finishBody();
        assertThrows(IllegalStateException.class, f.saves::requireFlushed);
        assertFalse(f.saves.pumpOne()); assertEquals(0, source.releases);
        disk.complete(null);
        assertEquals(0, source.releases, "I/O callback must not acknowledge an owner operation");
        assertEquals(1, f.saves.pending());
        assertTrue(f.saves.pumpOne()); f.saves.requireFlushed();
        assertEquals(1, source.releases); assertEquals(0, source.restored);
        assertEquals(0, f.saves.pending());
    }

    @Test void migrationMovesAcknowledgementAuthorityWithoutChangingTheCapturedCut() {
        Fixture f = new Fixture(); Source source = f.source();
        var attempt = f.capture(source); var originalCut = attempt.cut();
        var disk = new CompletableFuture<Void>(); attempt.submitted(disk); attempt.finishBody();
        var next = f.directory.snapshot().generation().next();
        f.directory.publish(next, Map.of(f.key, new RegionRoute(f.b.id(), next, f.b.ownerEpoch())));
        var failure = new IllegalArgumentException("write failed"); disk.completeExceptionally(failure);
        assertTrue(f.saves.pumpOne());
        assertEquals(f.b.id(), source.restoredBy);
        assertEquals(f.a.id(), originalCut.owner().region());
        assertSame(originalCut, attempt.cut());
        assertSame(failure, assertThrows(IllegalArgumentException.class, f.saves::requireFlushed));
        assertEquals(1, source.releases); assertEquals(0, f.saves.pending());
    }

    @Test void replacingTheInputCannotRedirectFailureRestorationToTheNewHolder() {
        Fixture f = new Fixture(); Source source = f.source();
        var attempt = f.capture(source); var disk = new CompletableFuture<Void>();
        attempt.submitted(disk); attempt.finishBody(); source.current = false;
        var failure = new IllegalStateException("retired source write failed"); disk.completeExceptionally(failure);
        assertTrue(f.saves.pumpOne()); assertEquals(0, source.restored);
        assertEquals(1L, f.saves.measurements().get("retiredFailedSources"));
        assertSame(failure, assertThrows(IllegalStateException.class, f.saves::requireFlushed));
        assertEquals(1, source.releases);
    }

    @Test void anInlineWriteCompletionCannotReleaseTheStillActiveOriginalSaveBody() {
        Fixture f = new Fixture(); Source source = f.source(); var attempt = f.capture(source);
        attempt.submitted(CompletableFuture.completedFuture(null));
        assertFalse(f.saves.pumpOne()); assertEquals(0, source.releases);
        attempt.finishBody(); assertTrue(f.saves.pumpOne()); f.saves.requireFlushed();
        assertEquals(1, source.releases);
        assertThrows(IllegalStateException.class, attempt::finishBody);
    }

    @Test void captureFailureRestoresDirtyStateWithoutRepeatingTheOriginalSerializer() {
        Fixture f = new Fixture(); Source source = f.source(); var attempt = f.saves.begin(source);
        assertTrue(attempt.markSaved(() -> true));
        var calls = new int[1]; var failure = new IllegalStateException("serializer failed");
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> attempt.capture(() -> { calls[0]++; throw failure; })));
        attempt.finishBody();
        assertTrue(f.saves.pumpOne()); assertEquals(1, source.restored);
        assertSame(failure, assertThrows(IllegalStateException.class, f.saves::requireFlushed));
        assertEquals(1, source.releases);
        assertEquals(1, calls[0]);
    }

    @Test void reentrantFailingReleaseCannotRetainCompletedRecordsOrSkipAnotherAcknowledgement() {
        Fixture f = new Fixture(); Source first = f.source(); Source second = f.source();
        var a = f.capture(first); var b = f.capture(second);
        a.submitted(CompletableFuture.completedFuture(null)); a.finishBody();
        b.submitted(CompletableFuture.completedFuture(null)); b.finishBody();
        var failure = new IllegalStateException("notification failed");
        first.afterRelease = () -> { assertTrue(f.saves.pumpOne()); throw failure; };
        assertTrue(f.saves.pumpOne());
        assertEquals(1, first.releases); assertEquals(1, second.releases); assertEquals(0, f.saves.pending());
        assertSame(failure, assertThrows(IllegalStateException.class, f.saves::requireFlushed));
    }

    @Test void repeatedFinitePressureReleasesProtocolStateAfterEachAcknowledgement() {
        Fixture f = new Fixture();
        for (int round = 0; round < 4; round++) {
            var writes = new ArrayList<CompletableFuture<Void>>();
            for (int i = 0; i < 256; i++) {
                var attempt = f.capture(f.source()); var write = new CompletableFuture<Void>();
                attempt.submitted(write); attempt.finishBody(); writes.add(write);
            }
            assertEquals(256, f.saves.pending());
            writes.forEach(write -> write.complete(null));
            while (f.saves.pumpOne()) { }
            f.saves.requireFlushed(); assertEquals(0, f.saves.pending());
        }
        assertEquals(1024L, f.saves.measurements().get("acknowledgements"));
    }

    static class Source implements RegionalSaveCoordinator.Source {
        final Fixture f; boolean current = true; int restored, releases; RegionId restoredBy;
        Runnable afterRelease = () -> { };
        Source(Fixture f) { this.f = f; }
        public RoutingKey key() { return f.key; }
        public boolean current() { assertTrue(OwnershipContext.current().isPresent()); return current; }
        public void restoreDirty() { restoredBy = OwnershipContext.current().orElseThrow().region(); restored++; }
        public void release() { assertTrue(OwnershipContext.current().isEmpty()); releases++; afterRelease.run(); }
    }
    static class Fixture {
        final WorldId world = new WorldId("save-test");
        final RoutingKey key = new RoutingKey(world, 4, 0);
        final RegionRuntime a = ready(new RegionId(world, "a")), b = ready(new RegionId(world, "b"));
        final Map<RegionId, RegionRuntime> runtimes = new LinkedHashMap<>(Map.of(a.id(), a, b.id(), b));
        final RegionDirectory directory = new RegionDirectory();
        final FailureBarrier barrier = new FailureBarrier();
        final RegionalSaveCoordinator saves;
        Fixture() {
            var generation = new TopologyGeneration(1);
            directory.publish(generation, Map.of(key, new RegionRoute(a.id(), generation, a.ownerEpoch())));
            var scheduler = new RegionScheduler(runtimes, directory, barrier);
            var gateway = new OwnerGateway(directory, runtimes, scheduler);
            saves = new RegionalSaveCoordinator(directory, runtimes,
                    new RegionalServiceDispatcher("save-test", 1, directory, runtimes, gateway, scheduler));
        }
        Source source() { return new Source(this); }
        RegionalSaveCoordinator.Attempt capture(Source source) {
            var attempt = saves.begin(source); assertTrue(attempt.markSaved(() -> true));
            assertEquals("original-snapshot", attempt.capture(() -> "original-snapshot")); return attempt;
        }
        static RegionRuntime ready(RegionId id) {
            var runtime = new RegionRuntime(id, new OwnerEpoch(0)); runtime.transitionTo(RegionLifecycle.READY); return runtime;
        }
    }
}
