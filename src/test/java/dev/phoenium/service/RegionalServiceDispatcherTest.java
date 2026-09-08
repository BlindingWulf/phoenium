package dev.phoenium.service;

import static org.junit.jupiter.api.Assertions.*;

import dev.phoenium.core.OwnerEpoch;
import dev.phoenium.core.TopologyGeneration;
import dev.phoenium.core.WorldId;
import dev.phoenium.failure.FailureBarrier;
import dev.phoenium.ownership.OwnershipContext;
import dev.phoenium.region.RegionId;
import dev.phoenium.region.RegionLifecycle;
import dev.phoenium.region.RegionRuntime;
import dev.phoenium.scheduler.OwnerGateway;
import dev.phoenium.scheduler.RegionScheduler;
import dev.phoenium.topology.RegionDirectory;
import dev.phoenium.topology.RegionRoute;
import dev.phoenium.topology.RoutingKey;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class RegionalServiceDispatcherTest {
    @Test
    void exactTypedBodyExecutesOnlyUnderCurrentOwnerLease() {
        Fixture f = new Fixture();
        var dispatcher = f.dispatcher();
        var observed = new java.util.ArrayList<String>();
        var handle = dispatcher.submit(f.aKey, RegionalServiceKind.SAVE_CAPTURE, execution -> {
            assertEquals(f.aId, execution.runtime().id());
            assertEquals(RegionalServiceKind.SAVE_CAPTURE, execution.kind());
            assertTrue(OwnershipContext.current().isPresent());
            execution.runtime().append("capture");
            observed.add(execution.serviceId() + ":" + execution.worldInstance());
        });

        assertTrue(observed.isEmpty());
        assertEquals(1, dispatcher.pendingCount());
        dispatcher.drain(handle);

        assertEquals(List.of("capture"), f.a.immutableLog());
        assertEquals(List.of("stage4-test:7"), observed);
        assertEquals(0, dispatcher.pendingCount());
        assertFalse(f.a.leases().hasActiveLease());
    }

    @Test
    void staleQueuedServiceReroutesAtDeliveryWithoutReplayingBody() {
        Fixture f = new Fixture();
        var dispatcher = f.dispatcher();
        var owners = new java.util.ArrayList<RegionId>();
        var handle = dispatcher.submit(f.aKey, RegionalServiceKind.TRACKING_UPDATE,
            execution -> owners.add(execution.runtime().id()));

        TopologyGeneration next = f.directory.snapshot().generation().next();
        f.directory.publish(next, Map.of(
            f.aKey, new RegionRoute(f.bId, next, f.b.ownerEpoch()),
            f.bKey, new RegionRoute(f.bId, next, f.b.ownerEpoch())));
        assertTrue(f.scheduler.runOne(f.a));
        assertTrue(owners.isEmpty());
        assertEquals(1, dispatcher.pendingCount());

        dispatcher.drain(handle);
        assertEquals(List.of(f.bId), owners);
        assertEquals(0, dispatcher.pendingCount());
    }

    @Test
    void ownerHeldDrainIsRejectedBeforeAnotherLeaseCanBeAcquired() {
        Fixture f = new Fixture();
        var dispatcher = f.dispatcher();
        var second = dispatcher.submit(f.bKey, RegionalServiceKind.BLOCK_EVENT, execution -> { });
        var first = dispatcher.submit(f.aKey, RegionalServiceKind.BLOCK_EVENT,
            execution -> assertThrows(IllegalStateException.class, () -> dispatcher.drain(second)));

        dispatcher.drain(first);
        dispatcher.drain(second);
        assertFalse(f.a.leases().hasActiveLease());
        assertFalse(f.b.leases().hasActiveLease());
    }

    @Test
    void callbackFailureTripsCommonBarrierAndRetainedBodyIsReleasedByDrain() {
        Fixture f = new Fixture();
        var dispatcher = f.dispatcher();
        var handle = dispatcher.submit(f.aKey, RegionalServiceKind.RUN_SCHEDULED_BLOCK_TICK,
            execution -> { throw new IllegalArgumentException("tick callback failed"); });

        var failure = assertThrows(RegionalServiceDispatcher.RegionalServiceFailure.class,
            () -> dispatcher.drain(handle));
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        assertEquals(RegionalServiceKind.RUN_SCHEDULED_BLOCK_TICK, failure.kind());
        assertTrue(f.barrier.tripped());
        assertEquals(0, dispatcher.pendingCount());
    }

    private static final class Fixture {
        final WorldId world = new WorldId("test");
        final RegionId aId = new RegionId(world, "a");
        final RegionId bId = new RegionId(world, "b");
        final RoutingKey aKey = new RoutingKey(world, 0, 0);
        final RoutingKey bKey = new RoutingKey(world, 10, 0);
        final RegionRuntime a = ready(aId);
        final RegionRuntime b = ready(bId);
        final Map<RegionId, RegionRuntime> runtimes = new LinkedHashMap<>(Map.of(aId, a, bId, b));
        final RegionDirectory directory = new RegionDirectory();
        final FailureBarrier barrier = new FailureBarrier();
        final RegionScheduler scheduler;
        final OwnerGateway gateway;
        Fixture() {
            directory.publish(new TopologyGeneration(1), Map.of(
                aKey, new RegionRoute(aId, new TopologyGeneration(1), a.ownerEpoch()),
                bKey, new RegionRoute(bId, new TopologyGeneration(1), b.ownerEpoch())));
            scheduler = new RegionScheduler(runtimes, directory, barrier);
            gateway = new OwnerGateway(directory, runtimes, scheduler);
        }
        RegionalServiceDispatcher dispatcher() {
            return new RegionalServiceDispatcher("stage4-test", 7, directory, runtimes, gateway, scheduler);
        }
        static RegionRuntime ready(RegionId id) {
            RegionRuntime runtime = new RegionRuntime(id, new OwnerEpoch(0));
            runtime.transitionTo(RegionLifecycle.READY);
            return runtime;
        }
    }
}
