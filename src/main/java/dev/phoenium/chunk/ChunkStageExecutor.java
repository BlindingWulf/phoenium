package dev.phoenium.chunk;

import dev.phoenium.core.TopologyGeneration;
import dev.phoenium.region.RegionId;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** The sole standard invocation adapter from an owner inbox to ChunkStageAdmission. */
public final class ChunkStageExecutor {
    private final ChunkStageAdmission admission;
    public ChunkStageExecutor(ChunkStageAdmission admission) { this.admission = Objects.requireNonNull(admission, "admission"); }

    public <I, O> ChunkStageAdmission.Result execute(PendingVanillaStage<I, O> pending, RegionId deliveredOwner,
                                                       TopologyGeneration deliveredGeneration) {
        Objects.requireNonNull(pending, "pending");
        AtomicReference<O> result = new AtomicReference<>();
        ChunkStageAdmission.Result admissionResult = admission.deliver(pending.stage(), deliveredOwner, deliveredGeneration, () -> {
            try { result.set(pending.invokeOriginal()); }
            catch (Throwable failure) { throw new VanillaInvocationException(failure); }
        });
        if (admissionResult == ChunkStageAdmission.Result.APPLIED) pending.applied(result.get());
        // STALE_ROUTE deliberately leaves the private outcome open: the same queued StageId is
        // rerouted by the owner gateway/pump and may still apply.  It is not a vanilla failure.
        else if (admissionResult == ChunkStageAdmission.Result.SUPERSEDED || admissionResult == ChunkStageAdmission.Result.CANCELLED
                || admissionResult == ChunkStageAdmission.Result.SHUTDOWN) pending.control(admissionResult);
        else if (admissionResult == ChunkStageAdmission.Result.FAILED_BARRIER) pending.barrierFailure(new IllegalStateException("admitted vanilla stage failed barrier"));
        return admissionResult;
    }
    private static final class VanillaInvocationException extends RuntimeException {
        private VanillaInvocationException(Throwable cause) { super(cause); }
    }
}
