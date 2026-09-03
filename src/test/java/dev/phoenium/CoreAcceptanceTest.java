package dev.phoenium;

import dev.phoenium.core.TopologyGeneration;
import dev.phoenium.core.WorldId;
import dev.phoenium.diagnostics.BlockingOwnerWork;
import dev.phoenium.ownership.OperationCategory;
import dev.phoenium.ownership.OwnershipContext;
import dev.phoenium.ownership.RegionLease;
import dev.phoenium.region.RegionId;
import dev.phoenium.region.RegionLifecycle;
import dev.phoenium.save.SaveCoordinator;
import dev.phoenium.scheduler.OwnerGateway;
import dev.phoenium.task.AdmissionPolicy;
import dev.phoenium.task.TaskPayload;
import dev.phoenium.topology.RegionRoute;
import dev.phoenium.topology.RoutingKey;
import dev.phoenium.topology.TopologyCoordinator;
import dev.phoenium.topology.TopologyPlanner;
import dev.phoenium.transfer.TransferGroup;
import dev.phoenium.transfer.TransferRecord;
import dev.phoenium.transfer.TransferState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CoreAcceptanceTest {
    @Test void c01_oneRegionOrdersTasksAndRequiresLeases() {
        CoreFixture f = new CoreFixture();
        f.scheduler.runTick(f.a);
        assertEquals(OwnerGateway.Delivery.QUEUED, f.gateway.submit(f.task(f.aKey, new TaskPayload.Append("first"), AdmissionPolicy.REROUTE, 1)));
        f.gateway.submit(f.task(f.aKey, new TaskPayload.Add(2), AdmissionPolicy.REROUTE, 2));
        f.scheduler.advanceOne(); f.scheduler.advanceOne();
        assertEquals(java.util.List.of("tick:1", "first", "add:2"), f.a.immutableLog());
        assertEquals(3, f.scheduler.advances());
        assertThrows(IllegalStateException.class, () -> f.a.append("unleased"));
    }

    @Test void c02_staleEnvelopeReroutesToCurrentOwner() {
        CoreFixture f = new CoreFixture();
        var stale = f.task(f.aKey, new TaskPayload.Append("rerouted"), AdmissionPolicy.REROUTE, 1);
        TopologyCoordinator topology = new TopologyCoordinator(f.directory, f.runtimes, f.gateway);
        topology.request(new TopologyCoordinator.Transition(Set.of(f.aId, f.bId), Map.of(f.aKey, f.bId, f.bKey, f.bId)));
        assertTrue(topology.advance());
        assertEquals(OwnerGateway.Delivery.REROUTED, f.gateway.submit(stale));
        f.scheduler.advanceOne();
        assertTrue(f.a.immutableLog().isEmpty());
        assertEquals(java.util.List.of("rerouted"), f.b.immutableLog());
    }

    @Test void c03_deterministicTopologyGenerationAndUniqueness() {
        CoreFixture f = new CoreFixture();
        TopologyCoordinator topology = new TopologyCoordinator(f.directory, f.runtimes, f.gateway);
        topology.request(new TopologyCoordinator.Transition(Set.of(f.aId, f.bId), Map.of(f.aKey, f.aId, f.bKey, f.bId)));
        assertTrue(topology.advance());
        assertEquals(2, f.directory.snapshot().generation().value());
        assertEquals(2, f.directory.snapshot().routes().size());
        assertEquals(f.aId, f.directory.resolve(f.aKey).orElseThrow().region());
        assertThrows(IllegalStateException.class, () -> f.directory.publish(f.directory.snapshot().generation(), Map.of()));
    }

    @Test void c03_randomizedPlansAreReproducibleWithRecordedSeed() {
        long seed = 0x50E11A5EL;
        Set<dev.phoenium.topology.RoutingKey> holders = new java.util.TreeSet<>();
        Random random = new Random(seed);
        CoreFixture f = new CoreFixture();
        for (int index = 0; index < 64; index++) holders.add(new dev.phoenium.topology.RoutingKey(f.world, random.nextInt(41) - 20, random.nextInt(41) - 20));
        TopologyPlanner planner = new TopologyPlanner();
        var first = planner.mergePlan(holders, 2);
        assertEquals(first, planner.mergePlan(holders, 2), "seed=" + seed);
        assertEquals(holders, first.stream().flatMap(Set::stream).collect(java.util.stream.Collectors.toSet()));
        // Components are maximal: no two keys in different components are within the merge radius.
        for (int i = 0; i < first.size(); i++) {
            for (int j = i + 1; j < first.size(); j++) {
                for (dev.phoenium.topology.RoutingKey a : first.get(i)) {
                    for (dev.phoenium.topology.RoutingKey b : first.get(j)) {
                        assertFalse(TopologyPlanner.adjacentWithin(a, b, 2), "seed=" + seed);
                    }
                }
            }
        }
    }

    /** D03-A: the exact 3x3 radius-1 authority square contains the required closure. */
    @Test void d03aExactAuthoritySquarePassesContainment() {
        TopologyPlanner planner = new TopologyPlanner();
        WorldId world = new WorldId("test");
        RoutingKey center = new RoutingKey(world, 0, 0);
        Set<RoutingKey> authority = square(world, 0, 0, 1);
        assertEquals(authority, planner.requiredClosure(Set.of(center), 1));
        assertTrue(planner.bufferClosure(Set.of(center), authority, 1));
    }

    /** D03-B: a larger authority (5x5 and 7x7) owns MORE than the closure and still passes. */
    @Test void d03bLargerAuthoritySquarePassesContainment() {
        TopologyPlanner planner = new TopologyPlanner();
        WorldId world = new WorldId("test");
        RoutingKey center = new RoutingKey(world, 0, 0);
        assertTrue(planner.bufferClosure(Set.of(center), square(world, 0, 0, 2), 1));
        assertTrue(planner.bufferClosure(Set.of(center), square(world, 0, 0, 3), 1));
        assertTrue(planner.requiredClosure(Set.of(center), 1).size() < planner.requiredClosure(Set.of(center), 2).size());
    }

    /** D03-C: a 3x3 square missing one neighbor is no longer a containing authority. */
    @Test void d03cMissingNeighborFailsContainment() {
        TopologyPlanner planner = new TopologyPlanner();
        WorldId world = new WorldId("test");
        RoutingKey center = new RoutingKey(world, 0, 0);
        Set<RoutingKey> authority = new java.util.LinkedHashSet<>(square(world, 0, 0, 1));
        authority.remove(new RoutingKey(world, 1, 0));
        assertFalse(planner.bufferClosure(Set.of(center), authority, 1));
        assertFalse(planner.bufferClosure(Set.of(center), Set.of(), 1));
    }

    /** D03-D: multi-seed required closure is the union of the per-seed balls; missing one union member fails. */
    @Test void d03dMultiSeedUnionContainment() {
        TopologyPlanner planner = new TopologyPlanner();
        WorldId world = new WorldId("test");
        RoutingKey left = new RoutingKey(world, -1, 0);
        RoutingKey right = new RoutingKey(world, 1, 0);
        Set<RoutingKey> union = new java.util.LinkedHashSet<>();
        union.addAll(planner.requiredClosure(Set.of(left), 1));
        union.addAll(planner.requiredClosure(Set.of(right), 1));
        assertEquals(union, planner.requiredClosure(Set.of(left, right), 1));
        assertTrue(planner.bufferClosure(Set.of(left, right), union, 1));
        Set<RoutingKey> missingOne = new java.util.LinkedHashSet<>(union);
        missingOne.remove(new RoutingKey(world, 0, 1));
        assertFalse(planner.bufferClosure(Set.of(left, right), missingOne, 1));
    }

    /** D03-E: authority in another world never substitutes for own-world neighbors. */
    @Test void d03eCrossWorldAuthorityNeverSatisfiesContainment() {
        TopologyPlanner planner = new TopologyPlanner();
        WorldId world = new WorldId("test");
        WorldId other = new WorldId("other");
        RoutingKey center = new RoutingKey(world, 0, 0);
        Set<RoutingKey> foreignSquare = square(other, 0, 0, 1);
        assertFalse(planner.bufferClosure(Set.of(center), foreignSquare, 1));
        // Even a full own-world square plus the complete foreign square still fails when one
        // own-world neighbor is absent: cross-world keys are exact-identity distinct.
        Set<RoutingKey> nearlyFull = new java.util.LinkedHashSet<>(square(world, 0, 0, 1));
        nearlyFull.remove(new RoutingKey(world, 0, -1));
        nearlyFull.addAll(foreignSquare);
        assertFalse(planner.bufferClosure(Set.of(center), nearlyFull, 1));
    }

    /** D03-F: radius 0 requires exactly the seed; the authority may own more. */
    @Test void d03fZeroRadiusRequiresOnlyTheSeed() {
        TopologyPlanner planner = new TopologyPlanner();
        WorldId world = new WorldId("test");
        RoutingKey center = new RoutingKey(world, 0, 0);
        assertEquals(Set.of(center), planner.requiredClosure(Set.of(center), 0));
        assertTrue(planner.bufferClosure(Set.of(center), Set.of(center), 0));
        Set<RoutingKey> extra = new java.util.LinkedHashSet<>(Set.of(center));
        extra.add(new RoutingKey(world, 5, 5));
        assertTrue(planner.bufferClosure(Set.of(center), extra, 0));
        assertTrue(planner.requiredClosure(Set.of(), 1).isEmpty());
    }

    private static Set<RoutingKey> square(WorldId world, int cx, int cz, int radius) {
        Set<RoutingKey> cells = new java.util.LinkedHashSet<>();
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) cells.add(new RoutingKey(world, x, z));
        }
        return cells;
    }

    /** A world carrying explicit routes fails closed on missing keys; a default-only world keeps resolving. */
    @Test void regressionExplicitTopologyWorldFailsClosedOnMissingExplicitRoute() {
        CoreFixture f = new CoreFixture();
        // The fixture world already carries explicit routes: an unrouted key resolves to nothing.
        assertTrue(f.directory.resolve(new RoutingKey(f.world, 7, 7)).isEmpty());
        // Publishing a world default for an explicit-topology world grants no hidden authority.
        TopologyGeneration next = f.directory.snapshot().generation().next();
        f.directory.publish(next, Map.of(
            f.aKey, new RegionRoute(f.aId, next, f.a.ownerEpoch()),
            f.bKey, new RegionRoute(f.bId, next, f.b.ownerEpoch())),
            Map.of(f.world, new RegionRoute(f.aId, next, f.a.ownerEpoch())));
        assertTrue(f.directory.resolve(new RoutingKey(f.world, 7, 7)).isEmpty());
        assertEquals(f.aId, f.directory.resolve(f.aKey).orElseThrow().region());
        // A world without explicit routes still resolves every key through its world default.
        WorldId plain = new WorldId("plain");
        RegionId whole = new RegionId(plain, "whole");
        TopologyGeneration later = next.next();
        f.directory.publish(later, Map.of(
            f.aKey, new RegionRoute(f.aId, later, f.a.ownerEpoch()),
            f.bKey, new RegionRoute(f.bId, later, f.b.ownerEpoch())),
            Map.of(plain, new RegionRoute(whole, later, f.a.ownerEpoch())));
        assertEquals(whole, f.directory.resolve(new RoutingKey(plain, 12, -5)).orElseThrow().region());
        assertTrue(f.directory.resolve(new RoutingKey(f.world, 7, 7)).isEmpty());
    }

    @Test void c04_transitionWaitsForLeaseBoundary() {
        CoreFixture f = new CoreFixture();
        TopologyCoordinator topology = new TopologyCoordinator(f.directory, f.runtimes, f.gateway);
        try (RegionLease lease = f.a.leases().acquire(); OwnershipContext.Scope ignored = OwnershipContext.enter(f.aId, lease.epoch(), OperationCategory.OWNER_TASK)) {
            f.a.append("active-task");
            topology.request(new TopologyCoordinator.Transition(Set.of(f.aId), Map.of(f.aKey, f.aId, f.bKey, f.bId)));
            assertFalse(topology.advance(), "an active mutable task makes the transition pending");
        }
        assertTrue(topology.advance());
    }

    @Test void transitionMailboxReresolvesAfterCommit() {
        CoreFixture f = new CoreFixture();
        TopologyCoordinator topology = new TopologyCoordinator(f.directory, f.runtimes, f.gateway);
        topology.request(new TopologyCoordinator.Transition(Set.of(f.aId, f.bId), Map.of(f.aKey, f.bId, f.bKey, f.bId)));
        assertEquals(OwnerGateway.Delivery.DEFERRED, f.gateway.submit(f.task(f.aKey, new TaskPayload.Append("mailbox"), AdmissionPolicy.DEFER_DURING_TRANSITION, 1)));
        assertTrue(topology.advance());
        f.scheduler.advanceOne();
        assertTrue(f.a.immutableLog().isEmpty());
        assertEquals(java.util.List.of("mailbox"), f.b.immutableLog());
    }

    @Test void c05_reciprocalPressureUsesMessagesNotPeerLocks() {
        CoreFixture f = new CoreFixture();
        f.gateway.submit(f.task(f.aKey, new TaskPayload.Append("from-b"), AdmissionPolicy.REROUTE, 1));
        f.gateway.submit(f.task(f.bKey, new TaskPayload.Append("from-a"), AdmissionPolicy.REROUTE, 2));
        f.scheduler.advanceOne(); f.scheduler.advanceOne();
        assertEquals(2, f.scheduler.advances());
        assertEquals(0, new TopologyCoordinator(f.directory, f.runtimes, f.gateway).metadataGateHoldCount());
    }

    @Test void c06_completePassengerGraphOrNothing() {
        TransferGroup group = new TransferGroup("vehicle", Map.of("vehicle", Set.of("p1", "p2"), "p1", Set.of("p3")));
        assertEquals(Set.of("vehicle", "p1", "p2", "p3"), group.completeMembers());
        assertDoesNotThrow(() -> group.requireComplete(Set.of("vehicle", "p1", "p2", "p3")));
        assertThrows(IllegalArgumentException.class, () -> group.requireComplete(Set.of("vehicle", "p1")));
    }

    @Test void c07_exceptionTripsHardBarrierAndStopsAdvancement() {
        CoreFixture f = new CoreFixture();
        f.gateway.submit(f.task(f.aKey, new TaskPayload.Fail("injected"), AdmissionPolicy.REROUTE, 1));
        f.scheduler.advanceOne();
        assertTrue(f.barrier.tripped());
        assertEquals(dev.phoenium.scheduler.SchedulerLifecycle.FAILED, f.scheduler.lifecycle());
        long before = f.scheduler.advances();
        assertEquals(OwnerGateway.Delivery.REJECTED_SCHEDULER_STOPPED, f.gateway.submit(f.task(f.aKey, new TaskPayload.Append("forbidden"), AdmissionPolicy.REROUTE, 2)));
        f.scheduler.advanceOne();
        assertEquals(before, f.scheduler.advances());
    }

    @Test void c08_saveCutIsImmutableAndHasNoDuplicateOwner() {
        CoreFixture f = new CoreFixture();
        f.gateway.submit(f.task(f.aKey, new TaskPayload.Append("saved"), AdmissionPolicy.REROUTE, 1)); f.scheduler.advanceOne();
        var cut = new SaveCoordinator().cut(f.directory, f.scheduler, java.util.List.of(f.a, f.b), Map.of("object-a", f.aId));
        assertEquals(f.directory.snapshot().generation(), cut.topologyGeneration());
        assertEquals(java.util.List.of("saved"), cut.ownerData().get(f.aId));
        assertThrows(UnsupportedOperationException.class, () -> cut.ownerData().put(f.bId, java.util.List.of()));
    }

    @Test void regressionLeaseQueuedAuthorityIsNotQuiescent() { c04_transitionWaitsForLeaseBoundary(); }
    @Test void regressionOwnerIdentityDoesNotGrantAuthority() { CoreFixture f = new CoreFixture(); assertThrows(IllegalStateException.class, () -> f.a.add(1)); }
    @Test void rLiveOwner01LivePayloadNeedsTheSameLeaseAndContextAsAnyOtherOwnerOperation() {
        CoreFixture f = new CoreFixture();
        assertThrows(IllegalStateException.class, f.a::tick, "the caller thread is not Phoenium authority");
        var scheduler = new dev.phoenium.scheduler.RegionScheduler(f.runtimes, f.directory, f.barrier,
            (runtime, tick) -> runtime.tick());
        var gateway = new OwnerGateway(f.directory, f.runtimes, scheduler);
        assertEquals(OwnerGateway.Delivery.QUEUED, gateway.submit(f.task(f.aKey,
            new TaskPayload.LiveLevelTick(f.world.value()), AdmissionPolicy.REJECT, 1)));
        scheduler.runOne(f.a);
        assertEquals(1, f.a.tickCount());
        assertFalse(f.a.leases().hasActiveLease());
        assertTrue(OwnershipContext.current().isEmpty());
    }
    @Test void regressionNoBlockingOwnerWait() {
        CoreFixture f = new CoreFixture();
        try (RegionLease lease = f.a.leases().acquire(); OwnershipContext.Scope ignored = OwnershipContext.enter(f.aId, lease.epoch(), OperationCategory.OWNER_TASK)) {
            assertThrows(IllegalStateException.class, () -> BlockingOwnerWork.reject("another region"));
        }
    }
    @Test void regressionNoBroadWorldGameplayLockAndNoPeerLockOrder() throws Exception {
        String allCore = Files.walk(Path.of("src/main/java/dev/phoenium"))
            .filter(path -> path.toString().endsWith(".java"))
            .map(path -> { try { return Files.readString(path); } catch (Exception e) { throw new RuntimeException(e); } })
            .collect(java.util.stream.Collectors.joining("\n"));
        assertFalse(allCore.contains("ReentrantReadWriteLock"));
        assertFalse(allCore.contains("synchronized ("));
        assertTrue(allCore.contains("ReentrantLock metadataGate"));
    }
    @Test void regressionShutdownDoesNotExecutePendingWorkFromCallerThread() {
        CoreFixture f = new CoreFixture(); f.gateway.submit(f.task(f.aKey, new TaskPayload.Append("pending"), AdmissionPolicy.REROUTE, 1));
        f.scheduler.stop(); f.scheduler.advanceOne();
        assertTrue(f.a.immutableLog().isEmpty()); assertEquals(1, f.a.inboxSize());
    }
    @Test void transferStateMachineRejectsPostCommitRollbackAndUsesBarrier() {
        CoreFixture f = new CoreFixture(); TransferRecord record = new TransferRecord("x", f.aId, f.bId, f.b.ownerEpoch());
        record.sourcePrepared(); record.destinationReserved(f.b.ownerEpoch()); record.commit();
        assertThrows(IllegalStateException.class, record::rollback); record.postCommitFailure(f.barrier);
        assertEquals(TransferState.FAILED_BARRIER, record.state()); assertTrue(f.barrier.tripped());
    }
    @Test void lifecycleRejectsIllegalTransitions() {
        CoreFixture f = new CoreFixture(); assertThrows(IllegalStateException.class, () -> f.a.transitionTo(RegionLifecycle.TRANSIENT));
        f.a.transitionTo(RegionLifecycle.TICKING); f.a.transitionTo(RegionLifecycle.READY); f.a.transitionTo(RegionLifecycle.DEAD);
        assertThrows(IllegalStateException.class, () -> f.a.transitionTo(RegionLifecycle.READY));
    }
    @Test void buildSkeletonIsServerOnlyAndUsesNoYarn() throws Exception {
        String metadata = Files.readString(Path.of("src/main/resources/fabric.mod.json"));
        String build = Files.readString(Path.of("build.gradle"));
        assertTrue(metadata.contains("\"environment\": \"server\"")); assertFalse(metadata.contains("client"));
        assertTrue(metadata.contains("fabric-lifecycle-events-v1")); assertTrue(metadata.contains("phoenium.mixins.json"));
        assertFalse(build.toLowerCase().contains("yarn")); assertTrue(build.contains("net.fabricmc.fabric-loom"));
    }
}
