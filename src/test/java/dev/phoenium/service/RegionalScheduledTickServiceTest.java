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
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickPriority;
import org.junit.jupiter.api.Test;

final class RegionalScheduledTickServiceTest {
    @Test
    void twoOwnersPreserveTimingPrioritySubtickDedupAndCohortObservation() {
        Fixture f = new Fixture();
        var service = f.service(key -> true);
        Object early = new Object();
        Object normal = new Object();
        ScheduledTick<Object> b = tick(normal, new BlockPos(160, 70, 0), 8, TickPriority.NORMAL, 30);
        ScheduledTick<Object> a = tick(early, new BlockPos(0, 70, 0), 8, TickPriority.HIGH, 90);
        f.aTicks.schedule(a);
        f.aTicks.schedule(tick(early, a.pos(), 9, TickPriority.EXTREMELY_LOW, 999)); // same type/pos is deduplicated
        f.bTicks.schedule(b);
        service.scheduled(f.aChunk, f.aTicks);
        service.scheduled(f.bChunk, f.bTicks);
        var order = new java.util.ArrayList<String>();

        service.tick(7, 65536, (pos, type) -> fail("tick ran before trigger"));
        assertEquals(2, f.aTicks.count() + f.bTicks.count());
        service.tick(8, 65536, (pos, type) -> {
            var owner = OwnershipContext.current().orElseThrow().region();
            order.add(owner.value() + ":" + (type == early ? "early" : "normal"));
            if (type == early) assertTrue(service.willTickThisTick(b.pos(), normal));
        });

        assertEquals(List.of("a:early", "b:normal"), order);
        assertEquals(0, f.aTicks.count() + f.bTicks.count());
        assertEquals(2, service.measurements().executed());
        assertFalse(f.a.leases().hasActiveLease());
        assertFalse(f.b.leases().hasActiveLease());
    }

    @Test
    void callbackScheduledTickWaitsForNextCycle() {
        Fixture f = new Fixture();
        var service = f.service(key -> true);
        Object type = new Object();
        ScheduledTick<Object> first = tick(type, new BlockPos(0, 70, 0), 3, TickPriority.NORMAL, 1);
        ScheduledTick<Object> nested = tick(type, new BlockPos(1, 70, 0), 3, TickPriority.EXTREMELY_HIGH, 2);
        f.aTicks.schedule(first);
        service.scheduled(f.aChunk, f.aTicks);
        var seen = new java.util.ArrayList<BlockPos>();

        service.tick(3, 65536, (pos, ignored) -> {
            seen.add(pos.immutable());
            f.aTicks.schedule(nested);
            service.scheduled(f.aChunk, f.aTicks);
        });
        assertEquals(List.of(first.pos()), seen);
        assertEquals(1, f.aTicks.count());
        service.tick(3, 65536, (pos, ignored) -> seen.add(pos.immutable()));
        assertEquals(List.of(first.pos(), nested.pos()), seen);
    }

    @Test
    void ineligibleContainerRetainsOriginalPersistentTick() {
        Fixture f = new Fixture();
        var service = f.service(key -> key != f.bChunk);
        Object type = new Object();
        f.aTicks.schedule(tick(type, new BlockPos(0, 64, 0), 1, TickPriority.NORMAL, 1));
        f.bTicks.schedule(tick(type, new BlockPos(160, 64, 0), 1, TickPriority.HIGH, 2));
        service.scheduled(f.aChunk, f.aTicks);
        service.scheduled(f.bChunk, f.bTicks);
        var seen = new java.util.ArrayList<BlockPos>();

        service.tick(1, 65536, (pos, ignored) -> seen.add(pos.immutable()));

        assertEquals(List.of(new BlockPos(0, 64, 0)), seen);
        assertEquals(1, f.bTicks.count());
        assertEquals(1, f.bTicks.pack(1).size());
    }

    @Test
    void explicitMigrationMovesCompletePendingContainerBeforeNewAdmission() {
        Fixture f = new Fixture();
        var service = f.service(key -> true);
        Object type = new Object();
        f.aTicks.schedule(tick(type, new BlockPos(0, 64, 0), 5, TickPriority.NORMAL, 1));
        service.scheduled(f.aChunk, f.aTicks);
        TopologyGeneration next = f.directory.snapshot().generation().next();
        f.directory.publish(next, Map.of(
            f.aKey, new RegionRoute(f.bId, next, f.b.ownerEpoch()),
            f.bKey, new RegionRoute(f.bId, next, f.b.ownerEpoch())));

        var plan = service.planMigration(f.directory.snapshot());
        service.installMigration(plan, f.directory.snapshot());
        var owners = new java.util.ArrayList<RegionId>();
        service.tick(5, 65536, (pos, ignored) -> owners.add(OwnershipContext.current().orElseThrow().region()));

        assertEquals(List.of(f.bId), owners);
        assertEquals(1, service.measurements().migratedContainers());
        assertEquals(1, service.measurements().ownerPartitions());
    }

    private static ScheduledTick<Object> tick(Object type, BlockPos pos, long time, TickPriority priority, long subTick) {
        return new ScheduledTick<>(type, pos, time, priority, subTick);
    }

    private static final class Fixture {
        final WorldId world = new WorldId("test");
        final RegionId aId = new RegionId(world, "a");
        final RegionId bId = new RegionId(world, "b");
        final RoutingKey aKey = new RoutingKey(world, 0, 0);
        final RoutingKey bKey = new RoutingKey(world, 10, 0);
        final long aChunk = ChunkPos.pack(0, 0);
        final long bChunk = ChunkPos.pack(10, 0);
        final LevelChunkTicks<Object> aTicks = new LevelChunkTicks<>();
        final LevelChunkTicks<Object> bTicks = new LevelChunkTicks<>();
        final RegionRuntime a = ready(aId);
        final RegionRuntime b = ready(bId);
        final Map<RegionId, RegionRuntime> runtimes = new LinkedHashMap<>(Map.of(aId, a, bId, b));
        final RegionDirectory directory = new RegionDirectory();
        final FailureBarrier barrier = new FailureBarrier();
        final RegionScheduler scheduler;
        final OwnerGateway gateway;
        final RegionalServiceDispatcher dispatcher;
        Fixture() {
            directory.publish(new TopologyGeneration(1), Map.of(
                aKey, new RegionRoute(aId, new TopologyGeneration(1), a.ownerEpoch()),
                bKey, new RegionRoute(bId, new TopologyGeneration(1), b.ownerEpoch())));
            scheduler = new RegionScheduler(runtimes, directory, barrier);
            gateway = new OwnerGateway(directory, runtimes, scheduler);
            dispatcher = new RegionalServiceDispatcher("ticks", 1, directory, runtimes, gateway, scheduler);
        }
        RegionalScheduledTickService<Object> service(java.util.function.LongPredicate eligibility) {
            var service = new RegionalScheduledTickService<Object>(world, directory, dispatcher, eligibility, true);
            service.bootstrap(Map.of(aChunk, aTicks, bChunk, bTicks));
            return service;
        }
        static RegionRuntime ready(RegionId id) {
            RegionRuntime runtime = new RegionRuntime(id, new OwnerEpoch(0));
            runtime.transitionTo(RegionLifecycle.READY);
            return runtime;
        }
    }
}
