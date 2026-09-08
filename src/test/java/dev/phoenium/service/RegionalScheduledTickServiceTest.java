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
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickPriority;
import org.junit.jupiter.api.Test;

final class RegionalScheduledTickServiceTest {
    @Test
    void nativeClearAndCopySeeTheActualRegionalCurrentAndRemainingCohort() throws Exception {
        assertEquals(nativeAreaTrace(false, false), nativeAreaTrace(true, false));
        // clearArea intentionally leaves vanilla's already-built query set unchanged.
        // Sharing the actual fields preserves that synchronous observation too.
        assertEquals(nativeAreaTrace(false, true), nativeAreaTrace(true, true));
    }

    @Test
    void nativeClearRefreshesAnIndexedHeadBeforeAnotherRegionalCycle() throws Exception {
        Fixture f = new Fixture(); var service = f.service(key -> true);
        var original = bindOriginalCycle(service, f);
        Object type = new Object();
        original.schedule(tick(type, new BlockPos(0, 64, 0), 1, TickPriority.NORMAL, 1));
        original.schedule(tick(type, new BlockPos(1, 64, 0), 9, TickPriority.NORMAL, 2));
        service.scheduled(f.aChunk, f.aTicks);
        var area = new net.minecraft.world.level.levelgen.structure.BoundingBox(0, 64, 0, 0, 64, 0);
        service.mutateArea(service, area, null, () -> original.clearArea(area));
        service.tick(1, 65536, (pos, ignored) -> fail("cleared indexed head executed"));
        var actual = new java.util.ArrayList<BlockPos>();
        service.tick(9, 65536, (pos, ignored) -> actual.add(pos));
        assertEquals(List.of(new BlockPos(1, 64, 0)), actual);
        assertEquals(0, service.measurements().indexedHeads());
    }

    @Test
    void activeAreaMutationRejectsForeignClosureBeforeOriginalEffects() throws Exception {
        Fixture f = new Fixture(); var service = f.service(key -> true);
        var original = bindOriginalCycle(service, f);
        var area = new net.minecraft.world.level.levelgen.structure.BoundingBox(0, 64, 0, 160, 64, 0);
        var called = new java.util.concurrent.atomic.AtomicBoolean();
        f.dispatcher.executeAndDrain(f.aKey, RegionalServiceKind.COLLECT_SCHEDULED_BLOCK_TICK, ignored ->
            assertThrows(IllegalStateException.class, () -> service.mutateArea(service, area, null, () -> called.set(true))));
        assertFalse(called.get());
    }

    private static List<String> nativeAreaTrace(boolean regional, boolean clear) throws Exception {
        Fixture f = new Fixture(); var service = f.service(key -> true);
        LevelTicks<Object> original = bindOriginalCycle(service, f);
        Object type = new Object();
        for (int x = 0; x <= 3; x++)
            original.schedule(tick(type, new BlockPos(x, 64, 0), x == 3 ? 9 : 1, TickPriority.NORMAL, x + 1));
        service.scheduled(f.aChunk, f.aTicks);
        var trace = new java.util.ArrayList<String>();
        java.util.function.BiConsumer<BlockPos, Object> callback = (pos, ignored) -> {
            trace.add("run:" + pos.getX());
            if (pos.getX() != 0) return;
            trace.add("before:" + original.willTickThisTick(new BlockPos(1, 64, 0), type));
            if (clear) {
                var area = new net.minecraft.world.level.levelgen.structure.BoundingBox(1, 64, 0, 3, 64, 0);
                if (regional) service.mutateArea(service, area, null, () -> original.clearArea(area));
                else original.clearArea(area);
            }
            trace.add("after:" + original.willTickThisTick(new BlockPos(1, 64, 0), type));
            var area = new net.minecraft.world.level.levelgen.structure.BoundingBox(0, 64, 0, 3, 64, 0);
            var offset = new net.minecraft.core.Vec3i(4, 0, 0);
            if (regional) service.mutateArea(service, area, offset, () -> original.copyAreaFrom(original, area, offset));
            else original.copyAreaFrom(original, area, offset);
        };
        if (regional) service.tick(1, 65536, callback); else original.tick(1, 65536, callback);
        f.aTicks.getAll().sorted(ScheduledTick.DRAIN_ORDER).forEach(tick ->
            trace.add("pending:" + tick.pos().getX() + ":" + tick.triggerTick() + ":" + tick.priority() + ":" + tick.subTickOrder()));
        if (regional) service.tick(9, 65536, (pos, ignored) -> trace.add("later:" + pos.getX()));
        else original.tick(9, 65536, (pos, ignored) -> trace.add("later:" + pos.getX()));
        return trace;
    }

    @SuppressWarnings("unchecked")
    private static LevelTicks<Object> bindOriginalCycle(RegionalScheduledTickService<Object> service, Fixture f) throws Exception {
        var original = new LevelTicks<Object>(key -> true);
        original.addContainer(new ChunkPos(0, 0), f.aTicks); original.addContainer(new ChunkPos(10, 0), f.bTicks);
        var pending = LevelTicks.class.getDeclaredField("toRunThisTick"); pending.setAccessible(true);
        var ran = LevelTicks.class.getDeclaredField("alreadyRunThisTick"); ran.setAccessible(true);
        var queries = LevelTicks.class.getDeclaredField("toRunThisTickSet"); queries.setAccessible(true);
        service.bindOriginalCycle((java.util.Queue<ScheduledTick<Object>>) pending.get(original),
            (List<ScheduledTick<Object>>) ran.get(original), (java.util.Set<ScheduledTick<?>>) queries.get(original));
        return original;
    }

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
            assertFalse(service.willTickThisTick(pos, type));
            if (type == early) assertTrue(service.willTickThisTick(b.pos(), normal));
        });

        assertEquals(List.of("a:early", "b:normal"), order);
        assertEquals(0, f.aTicks.count() + f.bTicks.count());
        assertEquals(2, service.measurements().executed());
        assertFalse(f.a.leases().hasActiveLease());
        assertFalse(f.b.leases().hasActiveLease());
    }

    @Test
    void dueSelectionMatchesPinnedLevelTicksWhenFuturePriorityWouldSortFirst() {
        Fixture f = new Fixture();
        var service = f.service(key -> true);
        var oracle = new LevelTicks<Object>(key -> true);
        var oracleA = new LevelChunkTicks<Object>();
        var oracleB = new LevelChunkTicks<Object>();
        oracle.addContainer(new ChunkPos(0, 0), oracleA);
        oracle.addContainer(new ChunkPos(10, 0), oracleB);
        Object future = new Object(), dueLow = new Object(), dueNormal = new Object();
        var ticks = List.of(
            tick(future, new BlockPos(0, 64, 0), 100, TickPriority.EXTREMELY_HIGH, 1),
            tick(dueLow, new BlockPos(160, 64, 0), 5, TickPriority.LOW, 2),
            tick(dueNormal, new BlockPos(1, 64, 0), 5, TickPriority.NORMAL, 3));
        for (ScheduledTick<Object> tick : ticks) {
            (ChunkPos.pack(tick.pos()) == f.aChunk ? f.aTicks : f.bTicks).schedule(tick);
            oracle.schedule(tick);
        }
        service.scheduled(f.aChunk, f.aTicks);
        service.scheduled(f.bChunk, f.bTicks);
        var expected = new java.util.ArrayList<BlockPos>();
        var actual = new java.util.ArrayList<BlockPos>();

        oracle.tick(5, 65536, (pos, ignored) -> expected.add(pos.immutable()));
        service.tick(5, 65536, (pos, ignored) -> actual.add(pos.immutable()));

        assertEquals(expected, actual);
        assertEquals(List.of(new BlockPos(1, 64, 0), new BlockPos(160, 64, 0)), actual);
        assertEquals(1, f.aTicks.count());
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
        Map<RoutingKey, RegionRoute> movedRoutes = new LinkedHashMap<>();
        f.directory.snapshot().routes().keySet().forEach(key ->
            movedRoutes.put(key, new RegionRoute(f.bId, next, f.b.ownerEpoch())));
        f.directory.publish(next, movedRoutes);

        var plan = service.planMigration(f.directory.snapshot());
        service.installMigration(plan, f.directory.snapshot());
        var owners = new java.util.ArrayList<RegionId>();
        service.tick(5, 65536, (pos, ignored) -> owners.add(OwnershipContext.current().orElseThrow().region()));

        assertEquals(List.of(f.bId), owners);
        assertEquals(1, service.measurements().migratedContainers());
        assertEquals(1, service.measurements().ownerPartitions());
    }

    @Test
    void producerMutationRequiresTheCurrentChunkOwnerBeforeContainerWrite() {
        Fixture f = new Fixture();
        var service = f.service(key -> true);
        var tick = tick(new Object(), new BlockPos(0, 64, 0), 1, TickPriority.NORMAL, 1);

        assertThrows(IllegalStateException.class, () -> service.requireScheduleOwner(f.aChunk, f.aTicks));
        f.dispatcher.executeAndDrain(f.aKey, RegionalServiceKind.RUN_SCHEDULED_BLOCK_TICK, ignored -> {
            service.requireScheduleOwner(f.aChunk, f.aTicks);
            f.aTicks.schedule(tick);
            assertThrows(IllegalStateException.class, () -> service.requireScheduleOwner(f.bChunk, f.bTicks));
        });

        assertEquals(1, f.aTicks.count());
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
            TopologyGeneration generation = new TopologyGeneration(1);
            Map<RoutingKey, RegionRoute> routes = new LinkedHashMap<>();
            halo(routes, aKey, new RegionRoute(aId, generation, a.ownerEpoch()));
            halo(routes, bKey, new RegionRoute(bId, generation, b.ownerEpoch()));
            directory.publish(generation, routes);
            scheduler = new RegionScheduler(runtimes, directory, barrier);
            gateway = new OwnerGateway(directory, runtimes, scheduler);
            dispatcher = new RegionalServiceDispatcher("ticks", 1, directory, runtimes, gateway, scheduler);
            dispatcher.installClosureEncloser(this::enclosed);
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
