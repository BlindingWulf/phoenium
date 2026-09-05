package dev.phoenium.chunk;

import dev.phoenium.core.OwnerEpoch;
import dev.phoenium.core.TopologyGeneration;
import dev.phoenium.core.WorldId;
import dev.phoenium.failure.FailureBarrier;
import dev.phoenium.region.RegionId;
import dev.phoenium.region.RegionLifecycle;
import dev.phoenium.region.RegionRuntime;
import dev.phoenium.scheduler.OwnerGateway;
import dev.phoenium.scheduler.RegionScheduler;
import dev.phoenium.task.TaskCorrelationId;
import dev.phoenium.topology.RegionDirectory;
import dev.phoenium.topology.RegionRoute;
import dev.phoenium.topology.RoutingKey;
import dev.phoenium.topology.TopologyCoordinator;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChunkRouteClassificationTest {
    @Test void validUnroutableWorkFencesInsteadOfCancellingOrInvoking() {
        Fixture f = new Fixture();
        assertEquals(ChunkStageOwnerGateway.Delivery.REJECTED_ROUTE, f.pump.publishAndDeliver(f.pending));
        var failure = assertInstanceOf(ChunkStageOutcome.BarrierFailure.class, f.pending.outcome().join());
        assertTrue(failure.failure().getMessage().contains("permanently unroutable active chunk work"));
        assertSame(failure.failure(), f.barrier.record().orElseThrow().cause());
        assertEquals(ChunkCustodyModel.StageState.FAILED_BARRIER, f.pending.state().orElseThrow());
        assertEquals(0, f.calls); assertTrue(f.inbox.isEmpty());
        assertFalse(f.pending.retainsInputOrContinuation()); assertFalse(f.owner.leases().hasActiveLease());
        f.pump.deliverPublished(f.pending);
        assertSame(failure, f.pending.outcome().join());
    }

    @Test void actualProposalParksWithoutLeaseAndWakesSameStageAfterCommit() {
        Fixture f = new Fixture(); f.propose();
        assertEquals(ChunkStageOwnerGateway.Delivery.PARKED_TOPOLOGY, f.pump.publishAndDeliver(f.pending));
        f.pump.deliverPublished(f.pending); f.pump.retryTopology();
        assertEquals(1, f.pump.parkedCount()); assertTrue(f.inbox.isEmpty());
        assertFalse(f.pending.outcome().isDone()); assertFalse(f.owner.leases().hasActiveLease());
        assertTrue(f.topology.advance());
        f.pump.retryTopology();
        assertEquals(0, f.pump.parkedCount()); assertSame(f.pending, f.inbox.peek());
        f.execute();
        assertEquals(1, f.calls); assertEquals(7, ((ChunkStageOutcome.Applied<?>) f.pending.outcome().join()).value());
        assertFalse(f.barrier.tripped()); assertFalse(f.owner.leases().hasActiveLease());
        f.pump.retryTopology(); assertTrue(f.inbox.isEmpty());
    }

    @Test void unrelatedProposalIsNotAProducerForMissingDemand() {
        Fixture f = new Fixture();
        f.topology.request(new TopologyCoordinator.Transition(Set.of(f.id),
                Map.of(new RoutingKey(f.world, 99, 99), f.id)));
        assertEquals(ChunkStageOwnerGateway.Delivery.REJECTED_ROUTE, f.pump.publishAndDeliver(f.pending));
        assertTrue(f.barrier.tripped()); assertEquals(0, f.pump.parkedCount());
    }

    @Test void cancelledParkedWorkSettlesBeforeTopologyAndCannotResurrect() {
        Fixture f = new Fixture(); f.propose(); f.pump.publishAndDeliver(f.pending);
        f.custody.retireWork(f.work, true); f.pump.retryTopology();
        assertEquals(new ChunkStageOutcome.Control<>(ChunkStageAdmission.Result.CANCELLED), f.pending.outcome().join());
        assertEquals(0, f.pump.parkedCount()); assertFalse(f.barrier.tripped());
        f.topology.advance(); f.pump.retryTopology();
        assertTrue(f.inbox.isEmpty()); assertEquals(0, f.calls);
    }

    @Test void replacementSettlesOldParkedWorkWithoutCancellingNewLifecycle() {
        Fixture f = new Fixture(); f.propose(); f.pump.publishAndDeliver(f.pending);
        var replacement = f.custody.activate("route", 1, f.key, new Object());
        f.pump.retryTopology();
        assertEquals(new ChunkStageOutcome.Control<>(ChunkStageAdmission.Result.SUPERSEDED), f.pending.outcome().join());
        assertEquals(ChunkCustodyModel.LifecycleState.LIVE, f.custody.lifecycleState(replacement).orElseThrow());
        assertEquals(0, f.pump.parkedCount()); assertFalse(f.barrier.tripped());
    }

    @Test void shutdownAcknowledgesParkedDemandOutsideMetadataAndDoesNotWakeItLater() {
        Fixture f = new Fixture(); f.propose(); f.pump.publishAndDeliver(f.pending);
        var observed = f.pending.outcome().thenRun(() -> {
            assertFalse(f.custody.gate().isHeldByCurrentThread());
            assertEquals(0, f.topology.metadataGateHoldCount());
            assertFalse(f.owner.leases().hasActiveLease());
        });
        f.pump.shutdown(); observed.join();
        assertEquals(new ChunkStageOutcome.Control<>(ChunkStageAdmission.Result.SHUTDOWN), f.pending.outcome().join());
        assertEquals(0, f.pump.parkedCount()); assertFalse(f.pending.retainsInputOrContinuation());
        f.topology.advance(); f.pump.retryTopology(); assertTrue(f.inbox.isEmpty());
    }

    @Test void topologyCannotWakeBeforeExistingLeaseDrains() {
        Fixture f = new Fixture(); f.propose(); f.pump.publishAndDeliver(f.pending);
        try (var lease = f.owner.leases().acquire()) {
            assertFalse(f.topology.advance()); f.pump.retryTopology();
            assertEquals(1, f.pump.parkedCount()); assertTrue(f.inbox.isEmpty());
        }
        assertTrue(f.topology.advance()); f.pump.retryTopology(); f.execute(); assertEquals(1, f.calls);
    }

    private static final class Fixture {
        final WorldId world = new WorldId("route-test");
        final RoutingKey key = new RoutingKey(world, 0, 0);
        final RegionId id = new RegionId(world, "owner");
        final RegionRuntime owner = new RegionRuntime(id, new OwnerEpoch(0));
        final RegionDirectory directory = new RegionDirectory();
        final FailureBarrier barrier = new FailureBarrier();
        final ChunkCustodyModel custody = new ChunkCustodyModel();
        final ChunkCustodyModel.LifecycleId lifecycle = custody.activate("route", 1, key, new Object());
        final ChunkCustodyModel.WorkId work = custody.beginWork(lifecycle, 0);
        final ChunkStageAdmission admission = new ChunkStageAdmission(custody, directory, Map.of(id, owner.leases()), barrier);
        final ChunkStageExecutor executor = new ChunkStageExecutor(admission);
        final ArrayDeque<PendingVanillaStage<?, ?>> inbox = new ArrayDeque<>();
        final ChunkStageDeliveryPump pump = new ChunkStageDeliveryPump(directory, (route, stage) -> {
            inbox.add(stage); return ChunkStageOwnerGateway.Delivery.QUEUED;
        }, executor);
        final TopologyCoordinator topology;
        int calls;
        final PendingVanillaStage<Integer, Integer> pending = new PendingVanillaStage<>(custody, lifecycle, work,
                new ChunkCustodyModel.StageId(work, ChunkStageKind.FULL_CONVERT, "prepare", 0),
                new TaskCorrelationId(0), input -> { calls++; return input; });
        Fixture() {
            owner.transitionTo(RegionLifecycle.READY);
            var runtimes = Map.of(id, owner);
            topology = new TopologyCoordinator(directory, runtimes,
                    new OwnerGateway(directory, runtimes, new RegionScheduler(runtimes, directory, barrier)));
            pending.bindInput(7);
        }
        void propose() { topology.request(new TopologyCoordinator.Transition(Set.of(id), Map.of(key, id))); }
        void execute() {
            var stage = inbox.remove(); RegionRoute route = directory.resolve(key).orElseThrow();
            assertEquals(ChunkStageAdmission.Result.APPLIED, executor.execute(stage, route.region(), route.generation()));
        }
    }
}
