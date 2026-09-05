package dev.phoenium.chunk;

import dev.phoenium.core.OwnerEpoch;
import dev.phoenium.core.TopologyGeneration;
import dev.phoenium.core.WorldId;
import dev.phoenium.failure.FailureBarrier;
import dev.phoenium.ownership.RegionLeaseManager;
import dev.phoenium.region.RegionId;
import dev.phoenium.topology.RegionDirectory;
import dev.phoenium.topology.RegionRoute;
import dev.phoenium.topology.RoutingKey;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Deterministic C-A01 through C-A10 custody/admission witnesses; no Minecraft callback is bound. */
class ChunkCustodyModelTest {
    @Test void ca01SameOwnerPipelineAppliesEveryDistinctStageOnce() {
        Fixture f = new Fixture();
        AtomicInteger invocations = new AtomicInteger();
        long sequence = 0;
        for (ChunkStageKind kind : ChunkStageKind.values()) {
            ChunkCustodyModel.StageId stage = f.stage(kind, kind.name().toLowerCase(), ++sequence);
            f.readyAndQueue(stage);
            if (kind == ChunkStageKind.FULL_PREPARE || kind == ChunkStageKind.FULL_FINISH) {
                assertEquals(ChunkStageAdmission.Result.NOT_READY, f.deliver(stage, f.a, invocations::incrementAndGet),
                        "production FULL stages cannot claim without their actual bindings");
                continue;
            }
            assertEquals(ChunkStageAdmission.Result.APPLIED, f.deliver(stage, f.a, invocations::incrementAndGet));
            assertEquals(ChunkCustodyModel.StageState.APPLIED, f.model.stageState(stage).orElseThrow());
        }
        assertEquals(ChunkStageKind.values().length - 2, invocations.get());
    }

    @Test void ca02OwnerChangesBeforeClaimReroutesSameStageToB() {
        Fixture f = new Fixture(); ChunkCustodyModel.StageId stage = f.stage(ChunkStageKind.PARSED_APPLY, "load", 1); f.readyAndQueue(stage);
        f.publish(f.b, 2); AtomicInteger mutations = new AtomicInteger();
        assertEquals(ChunkStageAdmission.Result.STALE_ROUTE, f.deliver(stage, f.a, mutations::incrementAndGet));
        assertEquals(ChunkStageAdmission.Result.APPLIED, f.deliver(stage, f.b, mutations::incrementAndGet));
        assertEquals(1, mutations.get()); assertEquals(1, f.admission.staleRoutes());
    }

    @Test void ca03OwnerChangesBetweenStagesNeverReplaysAppliedStage() {
        Fixture f = new Fixture(); ChunkCustodyModel.StageId first = f.stage(ChunkStageKind.PARSED_APPLY, "load", 1); f.readyAndQueue(first);
        AtomicInteger firstRuns = new AtomicInteger(); assertEquals(ChunkStageAdmission.Result.APPLIED, f.deliver(first, f.a, firstRuns::incrementAndGet));
        f.publish(f.b, 2); ChunkCustodyModel.StageId second = f.stage(ChunkStageKind.FULL_CONVERT, "full", 2); f.readyAndQueue(second);
        AtomicInteger secondRuns = new AtomicInteger(); assertEquals(ChunkStageAdmission.Result.APPLIED, f.deliver(second, f.b, secondRuns::incrementAndGet));
        assertEquals(ChunkStageAdmission.Result.DUPLICATE, f.deliver(first, f.b, firstRuns::incrementAndGet));
        assertEquals(1, firstRuns.get()); assertEquals(1, secondRuns.get());
    }

    @Test void ca04DuplicateDeliveryClaimsAndInvokesExactlyOnce() {
        Fixture f = new Fixture(); ChunkCustodyModel.StageId stage = f.stage(ChunkStageKind.STATUS_PUBLISH, "status-full", 1); f.readyAndQueue(stage);
        AtomicInteger calls = new AtomicInteger(); assertEquals(ChunkStageAdmission.Result.APPLIED, f.deliver(stage, f.a, calls::incrementAndGet));
        assertEquals(ChunkStageAdmission.Result.DUPLICATE, f.deliver(stage, f.a, calls::incrementAndGet));
        assertEquals(1, calls.get());
    }

    @Test void ca05RetiredSharedWorkCannotMutateAndReplacementWorkCan() {
        Fixture f = new Fixture(); ChunkCustodyModel.StageId old = f.stage(ChunkStageKind.PARSED_APPLY, "old", 1); f.readyAndQueue(old);
        f.model.retireWork(f.work, false);
        assertEquals(ChunkStageAdmission.Result.SUPERSEDED, f.deliver(old, f.a, () -> fail("retired work ran")));
        ChunkCustodyModel.WorkId replacement = f.model.beginWork(f.lifecycle, 2);
        ChunkCustodyModel.StageId fresh = new ChunkCustodyModel.StageId(replacement, ChunkStageKind.PARSED_APPLY, "new", 1);
        f.model.ready(fresh); f.model.queue(fresh); assertEquals(ChunkStageAdmission.Result.APPLIED, f.deliver(fresh, f.a, () -> {}));
    }

    @Test void ca06HolderReplacementRejectsOldLifecycleAndPendingUnloadRescuePreservesIt() {
        Fixture f = new Fixture(); ChunkCustodyModel.StageId old = f.stage(ChunkStageKind.PARSED_APPLY, "old", 1); f.readyAndQueue(old);
        Object replacementHolder = new Object(); f.model.retire(f.lifecycle);
        ChunkCustodyModel.LifecycleId replacement = f.model.activate("server", 1, f.key, replacementHolder);
        assertNotEquals(f.lifecycle, replacement); assertEquals(ChunkStageAdmission.Result.SUPERSEDED, f.deliver(old, f.a, () -> fail("old lifecycle ran")));
        ChunkCustodyModel.WorkId nextWork = f.model.beginWork(replacement, 1);
        ChunkCustodyModel.StageId next = new ChunkCustodyModel.StageId(nextWork, ChunkStageKind.PARSED_APPLY, "new", 1); f.model.ready(next); f.model.queue(next);
        assertEquals(ChunkStageAdmission.Result.APPLIED, f.deliver(next, f.a, () -> {}));
        Fixture rescue = new Fixture(); rescue.model.pendingUnload(rescue.lifecycle); assertTrue(rescue.model.rescue(rescue.lifecycle, rescue.holder));
        assertEquals(ChunkCustodyModel.LifecycleState.LIVE, rescue.model.lifecycleState(rescue.lifecycle).orElseThrow());
    }

    @Test void ca07ShutdownAfterReadySettlesWithoutMutationAndClaimedStageMayFinish() {
        Fixture f = new Fixture(); ChunkCustodyModel.StageId ready = f.stage(ChunkStageKind.PARSED_APPLY, "ready", 1); f.readyAndQueue(ready); f.admission.closeAdmissions();
        assertEquals(ChunkStageAdmission.Result.SHUTDOWN, f.deliver(ready, f.a, () -> fail("shutdown admitted a new stage")));
        Fixture claimed = new Fixture(); ChunkCustodyModel.StageId stage = claimed.stage(ChunkStageKind.FULL_CONVERT, "claimed", 1); claimed.readyAndQueue(stage);
        assertEquals(ChunkStageAdmission.Result.APPLIED, claimed.deliver(stage, claimed.a, claimed.admission::closeAdmissions));
        assertEquals(ChunkCustodyModel.StageState.APPLIED, claimed.model.stageState(stage).orElseThrow());
    }

    @Test void ca08VanillaFailureIsDistinctAndNeverTurnsIntoStaleness() {
        Fixture f = new Fixture(); ChunkCustodyModel.StageId stage = f.stage(ChunkStageKind.PARSED_APPLY, "io", 1); f.model.ready(stage); f.model.failVanilla(stage);
        assertEquals(ChunkCustodyModel.StageState.FAILED_VANILLA, f.model.stageState(stage).orElseThrow());
        assertFalse(f.barrier.tripped());
    }

    @Test void ca09ForeignOrUnleasedExecutionTripsFailureBarrier() {
        Fixture f = new Fixture(); ChunkCustodyModel.StageId stage = f.stage(ChunkStageKind.LIGHT_POST_UPDATE, "section-0", 1); f.readyAndQueue(stage);
        assertEquals(ChunkStageAdmission.Result.FAILED_BARRIER, f.admission.reportUnleasedOrForeignAttempt(stage, f.b));
        assertTrue(f.barrier.tripped()); assertEquals(ChunkCustodyModel.StageState.FAILED_BARRIER, f.model.stageState(stage).orElseThrow());
    }

    @Test void ca10TopologyGenerationChangeSameOwnerKeepsStageIdentityAndAppliesOnce() {
        Fixture f = new Fixture(); ChunkCustodyModel.StageId stage = f.stage(ChunkStageKind.MAP_METADATA, "marker", 1); f.readyAndQueue(stage);
        f.publish(f.a, 2); AtomicInteger calls = new AtomicInteger();
        // A stale G1 delivery cannot claim; the same StageId is then delivered using current G2.
        assertEquals(ChunkStageAdmission.Result.STALE_ROUTE, f.deliver(stage, f.a, new TopologyGeneration(1), calls::incrementAndGet));
        assertEquals(ChunkStageAdmission.Result.APPLIED, f.deliver(stage, f.a, new TopologyGeneration(2), calls::incrementAndGet));
        assertEquals(1, calls.get()); assertEquals(ChunkCustodyModel.StageState.APPLIED, f.model.stageState(stage).orElseThrow());
    }

    @Test void registrationCompletionRaceAndMultipleDemandCancellationRemainDeterministic() {
        Fixture f = new Fixture(); ChunkCustodyModel.DemandId d1 = f.model.subscribe(f.work, 1), d2 = f.model.subscribe(f.work, 2);
        f.model.cancelDemand(d1); assertFalse(f.model.demandActive(d1)); assertTrue(f.model.demandActive(d2)); assertEquals(ChunkCustodyModel.WorkState.ACTIVE, f.model.workState(f.work).orElseThrow());
        ChunkCustodyModel.StageId stage = f.stage(ChunkStageKind.POI_INSTALL, "section-1", 1);
        f.model.ready(stage); assertTrue(f.model.queue(stage)); assertFalse(f.model.queue(stage));
        assertEquals(ChunkStageAdmission.Result.APPLIED, f.deliver(stage, f.a, () -> {}));
        f.model.cancelDemand(d2); assertFalse(f.model.hasActiveDemand(f.work)); assertEquals(ChunkCustodyModel.WorkState.ACTIVE, f.model.workState(f.work).orElseThrow(), "zero demands does not silently kill committed shared work");
        f.model.retireWork(f.work, true); assertEquals(ChunkCustodyModel.WorkState.CANCELLED, f.model.workState(f.work).orElseThrow());
    }

    @Test void repeatedOwnerAndGenerationChangesAndThrownOperationNeverReplay() {
        Fixture f = new Fixture(); ChunkCustodyModel.StageId stage = f.stage(ChunkStageKind.FULL_CONVERT, "full", 1); f.readyAndQueue(stage);
        f.publish(f.a, 2); f.publish(f.b, 3); f.publish(f.a, 4); AtomicInteger calls = new AtomicInteger();
        assertEquals(ChunkStageAdmission.Result.APPLIED, f.deliver(stage, f.a, calls::incrementAndGet)); assertEquals(1, calls.get());
        Fixture failed = new Fixture(); ChunkCustodyModel.StageId throwing = failed.stage(ChunkStageKind.PARSED_APPLY, "throws", 1); failed.readyAndQueue(throwing);
        assertEquals(ChunkStageAdmission.Result.FAILED_BARRIER, failed.deliver(throwing, failed.a, () -> { throw new IllegalStateException("after mutation started"); }));
        assertEquals(ChunkCustodyModel.StageState.FAILED_BARRIER, failed.model.stageState(throwing).orElseThrow());
    }

    private static final class Fixture {
        final WorldId world = new WorldId("custody-test"); final RoutingKey key = new RoutingKey(world, 4, 0);
        final RegionId a = new RegionId(world, "A"), b = new RegionId(world, "B"); final Object holder = new Object();
        final RegionDirectory directory = new RegionDirectory(); final FailureBarrier barrier = new FailureBarrier(); final ChunkCustodyModel model = new ChunkCustodyModel();
        final ChunkCustodyModel.LifecycleId lifecycle; final ChunkCustodyModel.WorkId work; final ChunkStageAdmission admission;
        ChunkStageAdmission.Result deliver(ChunkCustodyModel.StageId stage, RegionId owner, Runnable operation) {
            var route = directory.resolve(stage.work().lifecycle().chunkKey()).orElseThrow();
            return deliver(stage,owner,route.generation(),operation);
        }
        ChunkStageAdmission.Result deliver(ChunkCustodyModel.StageId stage, RegionId owner, TopologyGeneration generation, Runnable operation) {
            var pending = new PendingVanillaStage<Void,Void>(model,stage.work().lifecycle(),stage.work(),stage,
                    new dev.phoenium.task.TaskCorrelationId(0),input -> {
                        try { operation.run(); return null; }
                        catch (Throwable failure) { throw new ChunkProtocolException("model invariant",failure); }
                    });
            pending.bindInput(null);
            return new ChunkStageExecutor(admission).execute(pending,owner,generation);
        }
        Fixture() { publish(a, 1); lifecycle = model.activate("server", 1, key, holder); work = model.beginWork(lifecycle, 1); admission = new ChunkStageAdmission(model, directory, Map.of(a, new RegionLeaseManager(a), b, new RegionLeaseManager(b)), barrier); }
        void publish(RegionId owner, long generation) { TopologyGeneration g = new TopologyGeneration(generation); directory.publish(g, Map.of(key, new RegionRoute(owner, g, new OwnerEpoch(0)))); }
        ChunkCustodyModel.StageId stage(ChunkStageKind kind, String subKey, long sequence) { return new ChunkCustodyModel.StageId(work, kind, subKey, sequence); }
        void readyAndQueue(ChunkCustodyModel.StageId stage) { model.ready(stage); assertTrue(model.queue(stage)); }
    }
}
