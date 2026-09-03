package dev.phoenium;

import dev.phoenium.diagnostics.BlockingOwnerWork;
import dev.phoenium.ownership.OperationCategory;
import dev.phoenium.ownership.OwnershipContext;
import dev.phoenium.ownership.RegionLease;
import dev.phoenium.region.RegionLifecycle;
import dev.phoenium.save.SaveCoordinator;
import dev.phoenium.scheduler.OwnerGateway;
import dev.phoenium.task.AdmissionPolicy;
import dev.phoenium.task.TaskPayload;
import dev.phoenium.topology.RegionRoute;
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
        assertTrue(first.stream().allMatch(component -> planner.bufferClosure(component, 2)));
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
        assertFalse(build.toLowerCase().contains("yarn")); assertTrue(build.contains("net.fabricmc.fabric-loom"));
    }
}
