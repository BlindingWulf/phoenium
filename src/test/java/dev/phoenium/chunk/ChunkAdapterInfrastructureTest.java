package dev.phoenium.chunk;

import dev.phoenium.core.OwnerEpoch;
import dev.phoenium.core.TopologyGeneration;
import dev.phoenium.core.WorldId;
import dev.phoenium.failure.FailureBarrier;
import dev.phoenium.ownership.OperationCategory;
import dev.phoenium.ownership.OwnershipContext;
import dev.phoenium.ownership.RegionLease;
import dev.phoenium.ownership.RegionLeaseManager;
import dev.phoenium.region.RegionId;
import dev.phoenium.task.TaskCorrelationId;
import dev.phoenium.topology.RegionDirectory;
import dev.phoenium.topology.RegionRoute;
import dev.phoenium.topology.RoutingKey;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** INF-01 through INF-12 deterministic production-infrastructure witnesses; no Minecraft callback is bound. */
class ChunkAdapterInfrastructureTest {
    @Test void inf01LifecycleRegistrationSameHolderAndTrueReplacement() {
        Fixture f = new Fixture();
        assertEquals(f.lifecycle, f.registry.activateHolder(1, f.key, f.holder));
        assertTrue(f.registry.beginPendingUnload(f.lifecycle));
        assertTrue(f.registry.rescuePendingUnload(f.lifecycle, f.holder));
        Object replacement = new Object();
        ChunkCustodyModel.LifecycleId next = f.registry.activateHolder(1, f.key, replacement);
        assertNotEquals(f.lifecycle, next); assertFalse(f.registry.isCurrent(f.lifecycle)); assertTrue(f.registry.isCurrent(next));
    }

    @Test void inf02OwnerChangeBeforeDeliveryReroutesFromAToB() {
        Fixture f = new Fixture(); AtomicInteger invoked = new AtomicInteger();
        PendingVanillaStage<Integer, Integer> pending = f.pending(1, invoked::addAndGet);
        f.registration(pending).callback().apply(1); // producer only publishes A's typed inbox delivery
        f.publish(f.b, 2);
        assertEquals(ChunkStageAdmission.Result.STALE_ROUTE, f.inbox.drainOne());
        f.pump.deliverPublished(pending);
        assertEquals(ChunkStageAdmission.Result.APPLIED, f.inbox.drainOne());
        assertEquals(1, invoked.get()); assertInstanceOf(ChunkStageOutcome.Applied.class, pending.outcome().join());
    }

    @Test void inf03SameOwnerGenerationChangeKeepsStageAndAppliesOnce() {
        Fixture f = new Fixture(); AtomicInteger invoked = new AtomicInteger();
        PendingVanillaStage<Integer, Integer> pending = f.pending(1, ignored -> invoked.incrementAndGet());
        f.registration(pending).callback().apply(5); f.publish(f.a, 2);
        assertEquals(ChunkStageAdmission.Result.STALE_ROUTE, f.inbox.drainOne());
        f.pump.deliverPublished(pending);
        assertEquals(ChunkStageAdmission.Result.APPLIED, f.inbox.drainOne());
        assertEquals(1, invoked.get()); assertEquals(ChunkCustodyModel.StageState.APPLIED, pending.state().orElseThrow());
    }

    @Test void inf04RegistrationPublishesBeforeAnyDeliveryCanInvoke() {
        Fixture f = new Fixture(); AtomicInteger invoked = new AtomicInteger();
        PendingVanillaStage<Integer, Integer> pending = f.pending(1, ignored -> invoked.incrementAndGet());
        f.registration(pending).callback().apply(7);
        assertEquals(0, invoked.get()); assertEquals(ChunkCustodyModel.StageState.QUEUED, pending.state().orElseThrow());
        assertEquals(ChunkStageAdmission.Result.APPLIED, f.inbox.drainOne()); assertEquals(1, invoked.get());
    }

    @Test void inf05DuplicateCallbackDeliveryInvokesTypedContinuationOnce() {
        Fixture f = new Fixture(); AtomicInteger invoked = new AtomicInteger();
        PendingVanillaStage<Integer, Integer> pending = f.pending(1, ignored -> invoked.incrementAndGet());
        f.registration(pending).callback().apply(1); f.pump.deliverPublished(pending);
        assertEquals(ChunkStageAdmission.Result.APPLIED, f.inbox.drainOne());
        assertEquals(ChunkStageAdmission.Result.DUPLICATE, f.inbox.drainOne()); assertEquals(1, invoked.get());
    }

    @Test void inf06LifecycleReplacementPreventsOldPendingInvocation() {
        Fixture f = new Fixture(); AtomicInteger invoked = new AtomicInteger();
        PendingVanillaStage<Integer, Integer> pending = f.pending(1, ignored -> invoked.incrementAndGet());
        f.registration(pending).callback().apply(1);
        f.registry.activateHolder(1, f.key, new Object());
        assertEquals(ChunkStageAdmission.Result.SUPERSEDED, f.inbox.drainOne()); assertEquals(0, invoked.get());
    }

    @Test void inf07RemovingDemandDoesNotRetireSharedWork() {
        Fixture f = new Fixture();
        ChunkCustodyModel.DemandId first = f.workRegistry.addDemand(f.work), second = f.workRegistry.addDemand(f.work);
        f.workRegistry.removeDemand(first);
        assertTrue(f.model.demandActive(second)); assertTrue(f.workRegistry.isCurrentWork(f.work));
        f.workRegistry.removeDemand(second); assertTrue(f.workRegistry.isCurrentWork(f.work));
    }

    @Test void inf08ShutdownBeforeClaimDoesNotInvoke() {
        Fixture f = new Fixture(); AtomicInteger invoked = new AtomicInteger();
        PendingVanillaStage<Integer, Integer> pending = f.pending(1, ignored -> invoked.incrementAndGet());
        f.registration(pending).callback().apply(1); f.admission.closeAdmissions();
        assertEquals(ChunkStageAdmission.Result.SHUTDOWN, f.inbox.drainOne()); assertEquals(0, invoked.get());
        assertInstanceOf(ChunkStageOutcome.Control.class, pending.outcome().join());
    }

    @Test void inf09ShutdownAfterClaimMayFinishSynchronously() {
        Fixture f = new Fixture(); AtomicInteger invoked = new AtomicInteger();
        PendingVanillaStage<Integer, Integer> pending = f.pending(1, ignored -> { f.admission.closeAdmissions(); return invoked.incrementAndGet(); });
        f.registration(pending).callback().apply(1);
        assertEquals(ChunkStageAdmission.Result.APPLIED, f.inbox.drainOne()); assertEquals(1, invoked.get());
    }

    @Test void inf10ProducerAmbientOwnerDoesNotGrantTargetAuthority() {
        Fixture f = new Fixture(); AtomicInteger invoked = new AtomicInteger();
        f.publish(f.b, 2); PendingVanillaStage<Integer, Integer> pending = f.pending(1, ignored -> invoked.incrementAndGet());
        try (RegionLease lease = f.leaseA.acquire(); OwnershipContext.Scope ignored = OwnershipContext.enter(f.a, lease.epoch(), OperationCategory.OWNER_TASK)) {
            f.registration(pending).callback().apply(1);
        }
        assertEquals(ChunkStageAdmission.Result.APPLIED, f.inbox.drainOne()); assertEquals(1, invoked.get());
    }

    @Test void inf11StaleRouteIsPrivateControlNotVanillaFailure() {
        Fixture f = new Fixture(); PendingVanillaStage<Integer, Integer> pending = f.pending(1, value -> value);
        f.registration(pending).callback().apply(1); f.publish(f.b, 2);
        assertEquals(ChunkStageAdmission.Result.STALE_ROUTE, f.inbox.drainOne());
        assertFalse(pending.outcome().isDone()); assertEquals(ChunkCustodyModel.StageState.QUEUED, pending.state().orElseThrow());
        f.pump.deliverPublished(pending); assertEquals(ChunkStageAdmission.Result.APPLIED, f.inbox.drainOne());
        assertInstanceOf(ChunkStageOutcome.Applied.class, pending.outcome().join());
    }

    @Test void inf12ThrownTypedContinuationTripsBarrierWithoutRetry() {
        Fixture f = new Fixture(); AtomicInteger invoked = new AtomicInteger();
        PendingVanillaStage<Integer, Integer> pending = f.pending(1, ignored -> { invoked.incrementAndGet(); throw new IllegalStateException("boom"); });
        f.registration(pending).callback().apply(1);
        assertEquals(ChunkStageAdmission.Result.FAILED_BARRIER, f.inbox.drainOne());
        assertTrue(f.barrier.tripped()); assertEquals(1, invoked.get()); assertEquals(ChunkCustodyModel.StageState.FAILED_BARRIER, pending.state().orElseThrow());
        assertEquals(ChunkStageAdmission.Result.FAILED_BARRIER, f.inbox.replayLast()); assertEquals(1, invoked.get());
    }

    private static final class Fixture {
        final WorldId world = new WorldId("adapter-test"); final RoutingKey key = new RoutingKey(world, 4, -4);
        final RegionId a = new RegionId(world, "A"), b = new RegionId(world, "B"); final Object holder = new Object();
        final ChunkCustodyModel model = new ChunkCustodyModel(); final FailureBarrier barrier = new FailureBarrier(); final RegionDirectory directory = new RegionDirectory();
        final RegionLeaseManager leaseA = new RegionLeaseManager(a), leaseB = new RegionLeaseManager(b);
        final ChunkLifecycleRegistry registry = new ChunkLifecycleRegistry(model, "server"); final ChunkWorkRegistry workRegistry = new ChunkWorkRegistry(model);
        final ChunkCustodyModel.LifecycleId lifecycle; final ChunkCustodyModel.WorkId work; final ChunkStageAdmission admission;
        final Inbox inbox; final ChunkStageDeliveryPump pump;
        Fixture() {
            publish(a, 1); lifecycle = registry.activateHolder(1, key, holder);
            work = workRegistry.beginOrResolveSharedWork(lifecycle, new ChunkWorkRegistry.WorkReservation("generation", "parsed", 0));
            admission = new ChunkStageAdmission(model, directory, Map.of(a, leaseA, b, leaseB), barrier);
            inbox = new Inbox(admission); pump = new ChunkStageDeliveryPump(directory, inbox);
        }
        void publish(RegionId owner, long generation) { TopologyGeneration g = new TopologyGeneration(generation); directory.publish(g, Map.of(key, new RegionRoute(owner, g, new OwnerEpoch(0)))); }
        PendingVanillaStage<Integer, Integer> pending(long sequence, VanillaChunkStage<Integer, Integer> continuation) {
            ChunkCustodyModel.StageId stage = new ChunkCustodyModel.StageId(work, ChunkStageKind.PARSED_APPLY, "load", sequence);
            return new PendingVanillaStage<>(model, lifecycle, work, stage, new TaskCorrelationId(sequence), continuation);
        }
        ChunkStageRegistration<Integer, Integer> registration(PendingVanillaStage<Integer, Integer> pending) { return new ChunkStageRegistration<>(pending, pump); }
    }

    /** Test owner inbox: producer submits only; an explicit later drain is the owner execution boundary. */
    private static final class Inbox implements ChunkStageOwnerGateway {
        private final ChunkStageExecutor executor; private final ArrayDeque<Item> items = new ArrayDeque<>(); private Item last;
        Inbox(ChunkStageAdmission admission) { executor = new ChunkStageExecutor(admission); }
        @Override public Delivery submit(RegionRoute route, PendingVanillaStage<?, ?> stage) { items.add(new Item(route, stage)); return Delivery.QUEUED; }
        ChunkStageAdmission.Result drainOne() { last = items.remove(); return execute(last); }
        ChunkStageAdmission.Result replayLast() { return execute(last); }
        @SuppressWarnings({"unchecked", "rawtypes"}) private ChunkStageAdmission.Result execute(Item item) {
            return executor.execute((PendingVanillaStage) item.stage, item.route.region(), item.route.generation());
        }
        private record Item(RegionRoute route, PendingVanillaStage<?, ?> stage) { }
    }
}
