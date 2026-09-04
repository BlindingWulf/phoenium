package dev.phoenium.chunk;

import dev.phoenium.core.TopologyGeneration;
import dev.phoenium.region.RegionId;
import java.util.Objects;

/** The sole standard invocation adapter from an owner inbox to ChunkStageAdmission. */
public final class ChunkStageExecutor {
    private final ChunkStageAdmission admission;
    public ChunkStageExecutor(ChunkStageAdmission admission) { this.admission = Objects.requireNonNull(admission, "admission"); }

    public <I, O> ChunkStageAdmission.Result execute(PendingVanillaStage<I, O> pending, RegionId deliveredOwner,
                                                       TopologyGeneration deliveredGeneration) {
        Objects.requireNonNull(pending, "pending");
        ChunkStageAdmission.Execution<O> execution = admission.execute(pending, deliveredOwner, deliveredGeneration);
        ChunkStageAdmission.Result admissionResult = execution.result();
        pending.stageExited();
        if (admissionResult == ChunkStageAdmission.Result.APPLIED) pending.applied(execution.value());
        // STALE_ROUTE deliberately leaves the private outcome open: the same queued StageId is
        // rerouted by the owner gateway/pump and may still apply.  It is not a vanilla failure.
        else if (admissionResult == ChunkStageAdmission.Result.SUPERSEDED || admissionResult == ChunkStageAdmission.Result.CANCELLED
                || admissionResult == ChunkStageAdmission.Result.SHUTDOWN) pending.control(admissionResult);
        else if (admissionResult == ChunkStageAdmission.Result.FAILED_VANILLA) pending.vanillaFailure(execution.failure());
        else if (admissionResult == ChunkStageAdmission.Result.FAILED_BARRIER) pending.barrierFailure(execution.failure());
        return admissionResult;
    }
}
