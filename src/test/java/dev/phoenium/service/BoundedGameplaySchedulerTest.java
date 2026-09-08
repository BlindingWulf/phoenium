package dev.phoenium.service;

import dev.phoenium.core.*;
import dev.phoenium.failure.FailureBarrier;
import dev.phoenium.ownership.OwnershipContext;
import dev.phoenium.region.*;
import dev.phoenium.scheduler.*;
import dev.phoenium.topology.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BoundedGameplaySchedulerTest {
    @Test void allPreflightsRetainTheSameClaimsThroughOriginalBodiesDespiteConcurrentIngress() {
        try (Fixture f = new Fixture(2)) {
            long[] leases = new long[2]; AtomicInteger state = new AtomicInteger();
            var queued = new AtomicReference<RegionalServiceDispatcher.Handle>();
            var first = new RegionalServiceDispatcher.LocalWork(f.keys.get(0), execution -> {
                leases[0] = OwnershipContext.current().orElseThrow().lease().value(); return true;
            }, execution -> { assertEquals(leases[0], OwnershipContext.current().orElseThrow().lease().value()); assertEquals(0,state.get()); });
            var second = new RegionalServiceDispatcher.LocalWork(f.keys.get(1), execution -> {
                leases[1] = OwnershipContext.current().orElseThrow().lease().value();
                queued.set(f.dispatcher.submit(f.keys.get(0), RegionalServiceKind.BLOCK_ENTITY_TICK, ignored -> state.incrementAndGet()));
                f.scheduler.advanceOne();
                assertFalse(queued.get().completed()); assertTrue(f.regions.get(0).leases().hasActiveLease()); return true;
            }, execution -> assertEquals(leases[1], OwnershipContext.current().orElseThrow().lease().value()));
            assertTrue(f.dispatcher.executePreparedLocalBatch(List.of(first, second)));
            assertEquals(0,state.get()); f.dispatcher.drain(queued.get()); assertEquals(1,state.get());
            assertEquals(0,f.dispatcher.pendingCount()); assertFalse(f.barrier.tripped());
        }
    }

    @Test void changedNativeEligibilityDeclinesEveryPeerBeforeAnyOriginalBody() {
        try (Fixture f = new Fixture(2)) {
            AtomicBoolean eligible = new AtomicBoolean(true);
            f.dispatcher.submit(f.keys.get(0), RegionalServiceKind.BLOCK_ENTITY_TICK, ignored -> eligible.set(false));
            var a = new RegionalServiceDispatcher.LocalWork(f.keys.get(0), ignored -> eligible.get(), ignored -> fail("stale native branch executed"));
            var b = new RegionalServiceDispatcher.LocalWork(f.keys.get(1), ignored -> true, ignored -> fail("peer committed before common preflight"));
            assertFalse(f.dispatcher.executePreparedLocalBatch(List.of(a,b)));
            assertEquals(0,f.dispatcher.pendingCount()); assertFalse(f.barrier.tripped());
            assertTrue(f.regions.stream().noneMatch(region -> region.leases().hasClaimOrSelection()));
            AtomicInteger serial = new AtomicInteger();
            f.dispatcher.executeAndDrain(f.keys.get(0), RegionalServiceKind.BLOCK_ENTITY_TICK, ignored -> serial.incrementAndGet());
            assertEquals(1,serial.get());
        }
    }

    @Test void preflightFailureReleasesClaimsButRetainsUnexecutedInputsUntilTerminalAcknowledgement() {
        try (Fixture f = new Fixture(2)) {
            var original = new IllegalArgumentException("retained preflight failed");
            var a = new RegionalServiceDispatcher.LocalWork(f.keys.get(0), ignored -> true, ignored -> fail("body after failed preflight"));
            var b = new RegionalServiceDispatcher.LocalWork(f.keys.get(1), ignored -> { throw original; }, ignored -> fail("body after failed preflight"));
            assertSame(original, assertThrows(IllegalArgumentException.class, () -> f.dispatcher.executePreparedLocalBatch(List.of(a,b))));
            assertTrue(f.regions.stream().noneMatch(region -> region.leases().hasClaimOrSelection()));
            assertEquals(2,f.dispatcher.pendingCount()); f.scheduler.stop();
            assertEquals(2,f.scheduler.acknowledgeStoppedEnvelopes()); assertEquals(2,f.dispatcher.abandonPending());
        }
    }

    @Test void originalFailureDoesNotReleaseAnotherLegitimateSaveCut() throws Exception {
        try (Fixture f = new Fixture(2); var saved = f.directory.tryRetainSaveCut()) {
            CountDownLatch peer = new CountDownLatch(1), release = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>(); var original = new IllegalStateException("original failure with saved input");
            Thread coordinator = launch(failure, () -> f.dispatcher.executeLocalBatch(List.of(
                f.work(0, () -> { await(peer); throw original; }), f.work(1, () -> { peer.countDown(); await(release); }))));
            try {
                assertTrue(peer.await(5,TimeUnit.SECONDS)); assertTrue(f.directory.hasRetainedSaveCuts());
                var next = f.directory.snapshot().generation().next(); Map<RoutingKey,RegionRoute> routes = new LinkedHashMap<>();
                f.directory.snapshot().routes().forEach((key,route) -> routes.put(key,new RegionRoute(route.region(),next,route.ownerEpoch())));
                assertThrows(IllegalStateException.class, () -> f.directory.publish(next,routes));
                assertThrows(IllegalStateException.class, f.dispatcher::abandonPending);
            } finally { release.countDown(); coordinator.join(5000); }
            assertSame(original,failure.get()); assertTrue(f.directory.hasRetainedSaveCuts()); assertNotNull(saved.view());
        }
    }

    @Test void distinctOriginalBodiesOverlapWithCurrentClaims() throws Exception {
        try (Fixture f = new Fixture(2)) {
            CountDownLatch entered = new CountDownLatch(2), release = new CountDownLatch(1);
            Set<Long> threads = ConcurrentHashMap.newKeySet();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread coordinator = launch(failure, () -> f.dispatcher.executeLocalBatch(List.of(
                f.work(0, () -> { threads.add(Thread.currentThread().threadId()); entered.countDown(); await(release); }),
                f.work(1, () -> { threads.add(Thread.currentThread().threadId()); entered.countDown(); await(release); }))));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertTrue(f.regions.get(0).leases().hasActiveLease());
                assertTrue(f.regions.get(1).leases().hasActiveLease());
                assertEquals(2, threads.size());
                assertTrue(f.scheduler.queuedWorkerTasks() <= 2);
                assertThrows(IllegalStateException.class, f.dispatcher::abandonPending);
            } finally { release.countDown(); coordinator.join(5000); }
            assertFalse(coordinator.isAlive()); assertNull(failure.get());
            assertEquals(2, f.scheduler.advances()); assertEquals(0, f.dispatcher.pendingCount());
            assertTrue(f.regions.stream().noneMatch(region -> region.leases().hasActiveLease()));
        }
    }

    @Test void failureJoinsLegitimatePeerBeforeReturningAndPreservesOriginalCause() throws Exception {
        try (Fixture f = new Fixture(2)) {
            CountDownLatch peerEntered = new CountDownLatch(1), releasePeer = new CountDownLatch(1);
            RuntimeException initiating = new IllegalArgumentException("original native callback failure");
            AtomicBoolean peerCompleted = new AtomicBoolean(); AtomicReference<Throwable> result = new AtomicReference<>();
            Thread coordinator = launch(result, () -> f.dispatcher.executeLocalBatch(List.of(
                f.work(0, () -> { await(peerEntered); throw initiating; }),
                f.work(1, () -> { peerEntered.countDown(); await(releasePeer); peerCompleted.set(true); }))));
            try {
                assertTrue(peerEntered.await(5, TimeUnit.SECONDS));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!f.barrier.tripped() && System.nanoTime() < deadline) Thread.onSpinWait();
                assertTrue(f.barrier.tripped()); assertSame(initiating, f.barrier.record().orElseThrow().cause());
                assertTrue(coordinator.isAlive()); assertFalse(peerCompleted.get());
                assertTrue(f.regions.get(1).leases().hasActiveLease());
                assertThrows(IllegalStateException.class, () -> f.dispatcher.submit(f.keys.get(2),
                    RegionalServiceKind.BLOCK_ENTITY_TICK, ignored -> fail("new admission past failure")));
            } finally { releasePeer.countDown(); coordinator.join(5000); }
            assertFalse(coordinator.isAlive()); assertSame(initiating, result.get()); assertTrue(peerCompleted.get());
            assertEquals(0, f.dispatcher.pendingCount());
            assertTrue(f.regions.stream().noneMatch(region -> region.leases().hasActiveLease()));
        }
    }

    @Test void topologyCannotPublishAcrossHeldBatchClaimsAndResolvesAfterRelease() throws Exception {
        try (Fixture f = new Fixture(2)) {
            CountDownLatch entered = new CountDownLatch(2), release = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread coordinator = launch(failure, () -> f.dispatcher.executeLocalBatch(List.of(
                f.work(0, () -> { entered.countDown(); await(release); }),
                f.work(1, () -> { entered.countDown(); await(release); }))));
            var topology = new TopologyCoordinator(f.directory, f.runtimes, f.gateway);
            var before = f.directory.snapshot();
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                Map<RoutingKey, RegionId> owners = new LinkedHashMap<>();
                before.routes().forEach((key, route) -> owners.put(key, route.region()));
                owners.put(f.keys.get(1), f.regions.get(0).id());
                topology.request(new TopologyCoordinator.Transition(Set.of(f.regions.get(0).id(), f.regions.get(1).id()), owners));
                assertFalse(topology.advance()); assertSame(before, f.directory.snapshot());
                assertTrue(f.regions.get(0).leases().tryAcquire().isEmpty());
            } finally { release.countDown(); coordinator.join(5000); }
            assertNull(failure.get()); assertTrue(topology.advance());
            assertEquals(before.generation().next(), f.directory.snapshot().generation());
            assertEquals(f.regions.get(0).id(), f.directory.resolve(f.keys.get(1)).orElseThrow().region());
            assertFalse(f.barrier.tripped());
        }
    }

    @Test void oneTwoAndFourWorkersUseSameBodyPathAndPreservePerRegionOrder() {
        for (int workers : new int[]{1, 2, 4}) try (Fixture f = new Fixture(workers)) {
            for (int round = 0; round < 12; round++) {
                List<RegionalServiceDispatcher.LocalWork> batch = new ArrayList<>();
                for (int region = 0; region < workers; region++) {
                    int owner = region, value = round;
                    batch.add(f.work(owner, () -> f.regions.get(owner).append("native:" + value)));
                }
                f.dispatcher.executeLocalBatch(batch);
            }
            List<String> expected = java.util.stream.IntStream.range(0, 12).mapToObj(n -> "native:" + n).toList();
            for (int region = 0; region < workers; region++) assertEquals(expected, f.regions.get(region).immutableLog());
            assertEquals(workers * 12, f.scheduler.advances()); assertEquals(0, f.dispatcher.pendingCount());
            assertFalse(f.barrier.tripped()); assertEquals(0, f.scheduler.queuedWorkerTasks());
        }
    }

    @Test void capacityAndDuplicateOwnerAreRejectedBeforeAnyBodyOrClaim() {
        try (Fixture f = new Fixture(2)) {
            var a = f.work(0, () -> fail("rejected batch executed"));
            assertThrows(IllegalArgumentException.class, () -> f.dispatcher.executeLocalBatch(List.of(a, a)));
            assertThrows(IllegalArgumentException.class, () -> f.dispatcher.executeLocalBatch(List.of(a, a, a)));
            assertEquals(0, f.dispatcher.pendingCount()); assertEquals(0, f.scheduler.advances());
            assertTrue(f.regions.stream().noneMatch(region -> region.leases().hasActiveLease()));
        }
        assertThrows(IllegalArgumentException.class, () -> new GameplayWorkerConfig(0, 64));
        assertThrows(IllegalArgumentException.class, () -> new GameplayWorkerConfig(9, 64));
        assertThrows(IllegalArgumentException.class, () -> new GameplayWorkerConfig(2, 257));
    }

    @Test void nativeSharedWorkIsNeverSelectedByTheParallelBatch() {
        try (Fixture f = new Fixture(2)) {
            AtomicLong thread = new AtomicLong();
            var handle = f.dispatcher.submit(f.keys.get(0), RegionalServiceKind.TRACKING_UPDATE,
                ignored -> thread.set(Thread.currentThread().threadId()));
            assertEquals(0, f.scheduler.advanceLocalBatch(List.of(f.regions.get(0))));
            assertEquals(0, thread.get()); assertFalse(handle.completed());
            f.dispatcher.drain(handle); assertEquals(Thread.currentThread().threadId(), thread.get());
        }
    }

    @Test void aBusyFirstRegionDoesNotStallIndependentQueuedWork() {
        try (Fixture f = new Fixture(2); var held = f.regions.get(0).leases().acquire()) {
            f.dispatcher.submit(f.keys.get(0), RegionalServiceKind.BLOCK_EVENT, ignored -> fail("busy owner entered twice"));
            AtomicBoolean ran = new AtomicBoolean();
            var independent = f.dispatcher.submit(f.keys.get(1), RegionalServiceKind.BLOCK_EVENT, ignored -> ran.set(true));
            f.scheduler.advanceOne(); assertTrue(ran.get()); assertTrue(independent.completed());
        }
    }

    @Test void terminalCleanupCannotEraseAnUnpromotedDeliverySelection() {
        try (Fixture f = new Fixture(2)) {
            try (var selected = f.regions.get(0).leases().trySelect().orElseThrow()) {
                assertFalse(f.regions.get(0).leases().hasActiveLease());
                assertTrue(f.regions.get(0).leases().hasClaimOrSelection());
                f.scheduler.stop();
                assertThrows(IllegalStateException.class, f.scheduler::acknowledgeStoppedEnvelopes);
                assertThrows(IllegalStateException.class, f.dispatcher::abandonPending);
            }
            assertEquals(0, f.scheduler.acknowledgeStoppedEnvelopes());
        }
    }

    @Test void stopWaitsForAcceptedOriginalBodiesAndRejectsNewInsertion() throws Exception {
        try (Fixture f = new Fixture(2)) {
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            AtomicReference<Throwable> result = new AtomicReference<>(), stopFailure = new AtomicReference<>();
            Thread coordinator = launch(result, () -> f.dispatcher.executeLocalBatch(List.of(
                f.work(0, () -> { entered.countDown(); await(release); }))));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            Thread stopper = launch(stopFailure, f.scheduler::stop);
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (f.scheduler.acceptsSimulationWork() && System.nanoTime() < deadline) Thread.onSpinWait();
                assertFalse(f.scheduler.acceptsSimulationWork()); assertTrue(stopper.isAlive());
                assertThrows(IllegalStateException.class, () -> f.dispatcher.submit(f.keys.get(1), RegionalServiceKind.BLOCK_EVENT,
                    ignored -> fail("admission reopened during stop")));
            } finally { release.countDown(); coordinator.join(5000); stopper.join(5000); }
            assertFalse(coordinator.isAlive()); assertFalse(stopper.isAlive());
            assertNull(result.get()); assertNull(stopFailure.get());
            assertEquals(0, f.scheduler.acknowledgeStoppedEnvelopes()); assertEquals(0, f.dispatcher.pendingCount());
        }
    }

    @Test void genericLocalKindCannotMintWorkerEligibility() {
        try (Fixture f = new Fixture(2)) {
            assertThrows(IllegalArgumentException.class, () -> f.dispatcher.submit(f.keys.get(0),
                RegionalServiceKind.LOCAL_BLOCK_ENTITY_TICK, ignored -> fail("unreviewed parallel body")));
            assertEquals(0, f.dispatcher.pendingCount()); assertEquals(0, f.regions.get(0).inboxSize());
        }
    }

    @Test void topologyContentionDefersTickAndSnapshotWithoutGlobalFailure() {
        try (Fixture f = new Fixture(2)) {
            f.regions.get(0).beginTransition();
            f.scheduler.runTick(f.regions.get(0));
            f.scheduler.snapshot(f.regions.get(0), () -> fail("snapshot entered a closed owner"));
            assertFalse(f.barrier.tripped()); assertEquals(0, f.regions.get(0).tickCount());
            f.regions.get(0).finishTransition(new OwnerEpoch(2));
            f.scheduler.runTick(f.regions.get(0)); assertEquals(1, f.regions.get(0).tickCount());
        }
    }

    private static Thread launch(AtomicReference<Throwable> failure, Runnable body) {
        Thread thread = new Thread(() -> { try { body.run(); } catch (Throwable problem) { failure.set(problem); } });
        thread.start(); return thread;
    }
    // Controlled interleaving probes, never installed gameplay/benchmark pauses.
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("interleaving probe timed out"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
    private static class Fixture implements AutoCloseable {
        final RegionDirectory directory = new RegionDirectory(); final FailureBarrier barrier = new FailureBarrier();
        final Map<RegionId, RegionRuntime> runtimes = new ConcurrentHashMap<>();
        final List<RegionRuntime> regions = new ArrayList<>(); final List<RoutingKey> keys = new ArrayList<>();
        final RegionScheduler scheduler; final OwnerGateway gateway; final RegionalServiceDispatcher dispatcher;
        Fixture(int workers) {
            Map<RoutingKey, RegionRoute> routes = new LinkedHashMap<>();
            for (int i = 0; i < 4; i++) {
                RegionRuntime runtime = new RegionRuntime(new RegionId(new WorldId("test"), "region-" + i), new OwnerEpoch(1));
                runtime.transitionTo(RegionLifecycle.READY); regions.add(runtime); runtimes.put(runtime.id(), runtime);
                RoutingKey key = new RoutingKey(new WorldId("test"), i * 64, 0); keys.add(key);
                routes.put(key, new RegionRoute(runtime.id(), new TopologyGeneration(1), runtime.ownerEpoch()));
            }
            directory.publish(new TopologyGeneration(1), routes);
            scheduler = new RegionScheduler(runtimes, directory, barrier, null, null, new GameplayWorkerConfig(workers, 64));
            gateway = new OwnerGateway(directory, runtimes, scheduler);
            dispatcher = new RegionalServiceDispatcher("bounded-test", 1, directory, runtimes, gateway, scheduler);
        }
        RegionalServiceDispatcher.LocalWork work(int region, Runnable body) {
            return new RegionalServiceDispatcher.LocalWork(keys.get(region), execution -> {
                assertEquals(regions.get(region).id(), OwnershipContext.current().orElseThrow().region());
                execution.runtime().leases().requireCurrent(OwnershipContext.current().orElseThrow().lease());
                body.run();
            });
        }
        @Override public void close() { scheduler.stop(); assertEquals(0, scheduler.liveWorkerThreads()); }
    }
}
