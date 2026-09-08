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
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

final class RegionalBlockEventServiceTest {
    @Test
    void twoOwnersKeepInsertionOrderAndNestedEventRunsInSameCycle() {
        Fixture f = new Fixture();
        Event a = new Event("a", new BlockPos(0, 64, 0));
        Event b = new Event("b", new BlockPos(160, 64, 0));
        Event nested = new Event("nested", new BlockPos(1, 64, 0));
        f.enqueue(a);
        f.enqueue(b);
        assertFalse(f.enqueue(a));
        var seen = new java.util.ArrayList<String>();

        f.service.runCycle(event -> true, event -> {
            seen.add(OwnershipContext.current().orElseThrow().region().value() + ":" + event.name());
            if (event == a) assertTrue(f.service.enqueue(nested));
        });

        assertEquals(List.of("a:a", "b:b", "a:nested"), seen);
        assertEquals(0, f.service.measurements().pending());
        assertEquals(3, f.service.measurements().executed());
    }

    @Test
    void ineligibleEventsMoveBehindNestedAndPreserveDedup() {
        Fixture f = new Fixture();
        Event deferred = new Event("deferred", new BlockPos(160, 64, 0));
        Event equalNested = new Event("deferred", new BlockPos(160, 64, 0));
        Event run = new Event("run", new BlockPos(161, 64, 0));
        f.enqueue(deferred);
        f.enqueue(run);
        var seen = new java.util.ArrayList<String>();
        f.service.runCycle(event -> event != deferred, event -> {
            seen.add(event.name());
            if (event == run) assertTrue(f.service.enqueue(equalNested));
        });

        assertEquals(List.of("run", "deferred"), seen);
        assertEquals(1, f.service.measurements().pending());
        assertFalse(f.enqueue(deferred));
        f.service.runCycle(event -> true, event -> seen.add(event.name()));
        assertEquals(List.of("run", "deferred", "deferred"), seen);
    }

    @Test
    void migrationMovesQueuedEventAndExecutesOnceAtCurrentOwner() {
        Fixture f = new Fixture();
        Event event = new Event("move", new BlockPos(0, 64, 0));
        f.enqueue(event);
        TopologyGeneration next = f.directory.snapshot().generation().next();
        Map<RoutingKey, RegionRoute> movedRoutes = new LinkedHashMap<>();
        f.directory.snapshot().routes().keySet().forEach(key ->
            movedRoutes.put(key, new RegionRoute(f.bId, next, f.b.ownerEpoch())));
        f.directory.publish(next, movedRoutes);
        var plan = f.service.planMigration(f.directory.snapshot());
        f.service.installMigration(plan, f.directory.snapshot());
        var owners = new java.util.ArrayList<RegionId>();

        f.service.runCycle(ignored -> true, ignored -> owners.add(OwnershipContext.current().orElseThrow().region()));

        assertEquals(List.of(f.bId), owners);
        assertEquals(1, f.service.measurements().migrated());
        assertEquals(0, f.service.measurements().pending());
    }

    private record Event(String name, BlockPos pos) { }

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
        final RegionalServiceDispatcher dispatcher;
        final RegionalBlockEventService<Event> service;
        Fixture() {
            TopologyGeneration generation = new TopologyGeneration(1);
            Map<RoutingKey, RegionRoute> routes = new LinkedHashMap<>();
            halo(routes, aKey, new RegionRoute(aId, generation, a.ownerEpoch()));
            halo(routes, bKey, new RegionRoute(bId, generation, b.ownerEpoch()));
            directory.publish(generation, routes);
            scheduler = new RegionScheduler(runtimes, directory, barrier);
            gateway = new OwnerGateway(directory, runtimes, scheduler);
            dispatcher = new RegionalServiceDispatcher("events", 1, directory, runtimes, gateway, scheduler);
            dispatcher.installClosureEncloser(this::enclosed);
            service = new RegionalBlockEventService<>(world, directory, dispatcher, Event::pos);
        }
        boolean enclosed(java.util.Set<RoutingKey> keys) {
            RegionId owner = null;
            for (RoutingKey key : keys) {
                RegionRoute route = directory.resolve(key).orElse(null);
                if (route == null) return false;
                if (owner == null) owner = route.region(); else if (!owner.equals(route.region())) return false;
            }
            return true;
        }
        static void halo(Map<RoutingKey, RegionRoute> routes, RoutingKey center, RegionRoute route) {
            for (int x = center.x() - 1; x <= center.x() + 1; x++)
                for (int z = center.z() - 1; z <= center.z() + 1; z++)
                    routes.put(new RoutingKey(center.world(), x, z), route);
        }
        boolean enqueue(Event event) {
            boolean[] result = {false};
            RoutingKey key = new RoutingKey(world, event.pos().getX() >> 4, event.pos().getZ() >> 4);
            dispatcher.executeAndDrain(key, RegionalServiceKind.BLOCK_EVENT, execution -> result[0] = service.enqueue(event));
            return result[0];
        }
        static RegionRuntime ready(RegionId id) {
            RegionRuntime runtime = new RegionRuntime(id, new OwnerEpoch(0));
            runtime.transitionTo(RegionLifecycle.READY);
            return runtime;
        }
    }
}
