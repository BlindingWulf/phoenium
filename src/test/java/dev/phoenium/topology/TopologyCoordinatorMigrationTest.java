package dev.phoenium.topology;

import static org.junit.jupiter.api.Assertions.*;

import dev.phoenium.core.OwnerEpoch;
import dev.phoenium.core.TopologyGeneration;
import dev.phoenium.core.WorldId;
import dev.phoenium.failure.FailureBarrier;
import dev.phoenium.region.RegionId;
import dev.phoenium.region.RegionLifecycle;
import dev.phoenium.region.RegionRuntime;
import dev.phoenium.scheduler.OwnerGateway;
import dev.phoenium.scheduler.RegionScheduler;
import dev.phoenium.task.AdmissionPolicy;
import dev.phoenium.task.RegionTaskEnvelope;
import dev.phoenium.task.TaskCorrelationId;
import dev.phoenium.task.TaskPayload;
import dev.phoenium.task.TaskSequence;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class TopologyCoordinatorMigrationTest {
    @Test
    void lateInstalledMigrationRunsOutsideGateAcrossRepeatedSuccessfulTransitions() {
        Fixture f = new Fixture();
        AtomicReference<TopologyCoordinator> reference = new AtomicReference<>();
        AtomicInteger prepares = new AtomicInteger(), installs = new AtomicInteger(), notices = new AtomicInteger();
        RegionalStateMigration migration = proposed -> {
            assertEquals(0, reference.get().metadataGateHoldCount());
            prepares.incrementAndGet();
            return published -> {
                assertEquals(0, reference.get().metadataGateHoldCount());
                assertEquals(proposed, published);
                installs.incrementAndGet();
            };
        };
        TopologyCoordinator coordinator = new TopologyCoordinator(f.directory, f.runtimes, f.gateway);
        reference.set(coordinator);
        coordinator.installStateMigration(migration);
        f.directory.observeChanges(() -> {
            assertEquals(0, coordinator.metadataGateHoldCount());
            notices.incrementAndGet();
        });

        coordinator.request(f.transition(f.bId));
        assertTrue(coordinator.advance());
        coordinator.request(f.transition(f.aId));
        assertTrue(coordinator.advance());

        assertEquals(2, prepares.get());
        assertEquals(2, installs.get());
        assertTrue(notices.get() >= 6); // close, publish, and reopen for each transition
        assertFalse(f.a.transitioning());
        assertFalse(f.b.transitioning());
        assertFalse(coordinator.hasPendingTransition());
        assertFalse(f.barrier.tripped());
    }

    @Test
    void retainedSaveCutDefersAdmissionClosureUntilARealRetryBoundary() {
        Fixture f = new Fixture();
        TopologyCoordinator coordinator = new TopologyCoordinator(f.directory, f.runtimes, f.gateway);
        RegionDirectory.SaveCut cut = f.directory.tryRetainSaveCut();

        coordinator.request(f.transition(f.bId));
        assertTrue(coordinator.hasPendingTransition());
        assertFalse(f.a.transitioning());
        assertFalse(f.b.transitioning());
        assertFalse(coordinator.advance());
        cut.close();
        assertTrue(coordinator.advance());

        assertEquals(f.bId, f.directory.resolve(f.aKey).orElseThrow().region());
        assertFalse(f.a.transitioning());
        assertFalse(f.b.transitioning());
    }

    @Test
    void failedInstallRetainsClosedTransitionAndCommonBarrierPreservesQueuedBody() {
        Fixture f = new Fixture();
        RuntimeException failure = new RuntimeException("migration install");
        TopologyCoordinator coordinator = new TopologyCoordinator(f.directory, f.runtimes, f.gateway,
            null, proposed -> published -> { throw failure; });
        assertEquals(OwnerGateway.Delivery.QUEUED, f.gateway.submit(f.append("must-stay-queued")));

        coordinator.request(f.transition(f.bId));
        assertSame(failure, assertThrows(RuntimeException.class, coordinator::advance));
        assertTrue(f.barrier.tripped());
        assertTrue(coordinator.hasPendingTransition());
        assertTrue(f.a.transitioning());
        assertTrue(f.b.transitioning());
        assertFalse(coordinator.advance());
        int queued = f.a.inboxSize() + f.b.inboxSize();
        f.scheduler.advanceOne();
        assertEquals(queued, f.a.inboxSize() + f.b.inboxSize());
        assertTrue(f.a.immutableLog().isEmpty());
        assertEquals(OwnerGateway.Delivery.REJECTED_SCHEDULER_STOPPED,
            f.gateway.submit(f.append("rejected-after-common-barrier")));
    }

    private static final class Fixture {
        final WorldId world = new WorldId("migration");
        final RegionId aId = new RegionId(world, "a"), bId = new RegionId(world, "b");
        final RoutingKey aKey = new RoutingKey(world, 0, 0), bKey = new RoutingKey(world, 10, 0);
        final RegionRuntime a = ready(aId), b = ready(bId);
        final Map<RegionId, RegionRuntime> runtimes = new LinkedHashMap<>(Map.of(aId, a, bId, b));
        final RegionDirectory directory = new RegionDirectory();
        final FailureBarrier barrier = new FailureBarrier();
        final RegionScheduler scheduler = new RegionScheduler(runtimes, directory, barrier);
        final OwnerGateway gateway = new OwnerGateway(directory, runtimes, scheduler);
        long sequence;
        Fixture() {
            TopologyGeneration generation = new TopologyGeneration(1);
            directory.publish(generation, Map.of(
                aKey, new RegionRoute(aId, generation, a.ownerEpoch()),
                bKey, new RegionRoute(bId, generation, b.ownerEpoch())));
        }
        TopologyCoordinator.Transition transition(RegionId aOwner) {
            return new TopologyCoordinator.Transition(Set.of(aId, bId), Map.of(aKey, aOwner, bKey, bId));
        }
        RegionTaskEnvelope append(String value) {
            RegionRoute route = directory.resolve(aKey).orElseThrow();
            long current = ++sequence;
            return new RegionTaskEnvelope("migration-test", new TaskPayload.Append(value), aKey,
                route.generation(), route.ownerEpoch(), "test", new TaskCorrelationId(current),
                new TaskSequence(current), AdmissionPolicy.REROUTE);
        }
        static RegionRuntime ready(RegionId id) {
            RegionRuntime runtime = new RegionRuntime(id, new OwnerEpoch(0));
            runtime.transitionTo(RegionLifecycle.READY);
            return runtime;
        }
    }
}
