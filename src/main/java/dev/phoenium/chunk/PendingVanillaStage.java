package dev.phoenium.chunk;

import dev.phoenium.core.WorldId;
import dev.phoenium.task.TaskCorrelationId;
import dev.phoenium.topology.RoutingKey;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * A single retained, stage-specific vanilla continuation.  It is published READY then QUEUED
 * before any owner delivery is visible.  It cannot represent an arbitrary gameplay Runnable.
 */
public final class PendingVanillaStage<I, O> {
    private final ChunkCustodyModel custody;
    private final ChunkCustodyModel.LifecycleId lifecycle;
    private final ChunkCustodyModel.WorkId work;
    private final ChunkCustodyModel.StageId stage;
    private final WorldId world;
    private final RoutingKey chunk;
    private final ChunkStageKind kind;
    private final TaskCorrelationId correlationId;
    private VanillaChunkStage.Admitted<I, O> continuation;
    private final CompletableFuture<ChunkStageOutcome<O>> outcome = new CompletableFuture<>();
    private I input;
    private boolean inputBound;
    private boolean published;
    private boolean invoked;
    private LightInputService light;
    private LightInputService.BatchId lightBatch;
    private LightInputService publicationLight;
    private LightInputReceipt publicationReceipt;

    public PendingVanillaStage(ChunkCustodyModel custody, ChunkCustodyModel.LifecycleId lifecycle,
                               ChunkCustodyModel.WorkId work, ChunkCustodyModel.StageId stage,
                               TaskCorrelationId correlationId, VanillaChunkStage<I, O> continuation) {
        this(custody, lifecycle, work, stage, correlationId, (input, claim) -> continuation.apply(input));
    }

    public PendingVanillaStage(ChunkCustodyModel custody, ChunkCustodyModel.LifecycleId lifecycle,
                               ChunkCustodyModel.WorkId work, ChunkCustodyModel.StageId stage,
                               TaskCorrelationId correlationId, VanillaChunkStage.Admitted<I, O> continuation) {
        this.custody = Objects.requireNonNull(custody, "custody"); this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.work = Objects.requireNonNull(work, "work"); this.stage = Objects.requireNonNull(stage, "stage");
        this.correlationId = Objects.requireNonNull(correlationId, "correlationId"); this.continuation = Objects.requireNonNull(continuation, "continuation");
        if (!work.lifecycle().equals(lifecycle) || !stage.work().equals(work)) throw new IllegalArgumentException("stage identity does not match lifecycle/work");
        this.world = lifecycle.world(); this.chunk = lifecycle.chunkKey(); this.kind = stage.kind();
    }

    public ChunkCustodyModel.LifecycleId lifecycle() { return lifecycle; }
    public ChunkCustodyModel.WorkId work() { return work; }
    public ChunkCustodyModel.StageId stage() { return stage; }
    public WorldId world() { return world; }
    public RoutingKey chunk() { return chunk; }
    public ChunkStageKind kind() { return kind; }
    public TaskCorrelationId correlationId() { return correlationId; }
    public CompletableFuture<ChunkStageOutcome<O>> outcome() { return outcome; }
    public Optional<ChunkCustodyModel.StageState> state() { return custody.stageState(stage); }
    boolean belongsTo(ChunkCustodyModel expected) { return custody == expected; }

    /** Registration callback binds its immutable/exclusively-retained upstream input exactly once. */
    public boolean bindInput(I readyInput) {
        custody.gate().lock();
        try {
            if (inputBound || outcome.isDone()) return false;
            input = readyInput; inputBound = true;
            return true;
        } finally { custody.gate().unlock(); }
    }
    void publishReady() {
        custody.gate().lock();
        try {
            if (!inputBound) throw new ChunkProtocolException("cannot publish before retaining vanilla input: " + stage);
            if (published) return;
            custody.ready(stage);
            if (!custody.queue(stage)) throw new ChunkProtocolException("failed to publish queued stage " + stage);
            published = true;
        } finally { custody.gate().unlock(); }
    }
    boolean published() {
        custody.gate().lock(); try { return published; } finally { custody.gate().unlock(); }
    }

    /** Bound once before readiness. Registration gates claim; the real receipt gates return. */
    public void requireReloadLight(LightInputService service, LightInputService.BatchId batch) {
        custody.gate().lock();
        try {
            if (inputBound || light != null || !stage.equals(batch.stage()) || !service.belongsTo(custody))
                throw new ChunkProtocolException("wrong/duplicate reload light stage binding");
            light = Objects.requireNonNull(service); lightBatch = batch;
        } finally { custody.gate().unlock(); }
    }
    LightInputService.Readiness lightReadinessLocked() {
        if (publicationLight != null) {
            return publicationLight.validates(publicationReceipt, publicationReceipt.batch())
                    && custody.stageState(publicationReceipt.batch().stage()).orElse(null) == ChunkCustodyModel.StageState.APPLIED
                    ? LightInputService.Readiness.REGISTERED : LightInputService.Readiness.FAILED;
        }
        return light == null ? LightInputService.Readiness.REGISTERED : light.registeredForParsedApply(lightBatch, stage);
    }
    Object lightRegistrationLocked() { return light == null ? null : light.registrationLocked(lightBatch); }

    /** EMPTY is a separate claim and consumes the original reload's authenticated ingress evidence. */
    public void requireReloadPublication(LightInputService service, LightInputReceipt receipt) {
        custody.gate().lock();
        try {
            if (inputBound || publicationLight != null || !service.belongsTo(custody) || stage.kind() != ChunkStageKind.STATUS_PUBLISH
                    || !stage.subKey().equals("EMPTY") || !work.equals(receipt.batch().work()))
                throw new ChunkProtocolException("mismatched reload EMPTY publication evidence");
            publicationLight = Objects.requireNonNull(service); publicationReceipt = Objects.requireNonNull(receipt);
        } finally { custody.gate().unlock(); }
    }

    O invokeOriginal(ChunkStageClaim claim) throws Throwable {
        claim.require(stage);
        final I retained;
        final VanillaChunkStage.Admitted<I, O> original;
        custody.gate().lock();
        try {
            if (!inputBound || invoked || continuation == null) throw new ChunkProtocolException("unbound or repeated vanilla stage invocation");
            invoked = true; retained = input; original = continuation;
        } finally { custody.gate().unlock(); }
        try {
            O value = original.apply(retained, claim);
            if (light != null) light.requireSuccess(claim, lightBatch);
            return value;
        } catch (Throwable failure) {
            if (light != null) light.failedRead(claim, lightBatch, failure);
            throw failure;
        } finally { releaseInput(); }
    }
    void stageExited() { if (light != null) light.stageExited(lightBatch); }
    private void releaseInput() {
        custody.gate().lock();
        try { input = null; continuation = null; publicationLight = null; publicationReceipt = null; }
        finally { custody.gate().unlock(); }
    }
    boolean retainsInputOrContinuation() {
        custody.gate().lock(); try { return input != null || continuation != null; } finally { custody.gate().unlock(); }
    }
    void applied(O value) { outcome.complete(new ChunkStageOutcome.Applied<>(value)); }
    void control(ChunkStageAdmission.Result result) {
        releaseInput();
        if (light != null) light.forget(lightBatch);
        outcome.complete(new ChunkStageOutcome.Control<>(result));
    }
    void vanillaFailure(Throwable failure) { releaseInput(); outcome.complete(new ChunkStageOutcome.VanillaFailure<>(failure)); }
    void barrierFailure(Throwable failure) { releaseInput(); outcome.complete(new ChunkStageOutcome.BarrierFailure<>(failure)); }
}
