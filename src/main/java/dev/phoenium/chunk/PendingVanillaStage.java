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
    private final VanillaChunkStage<I, O> continuation;
    private final CompletableFuture<ChunkStageOutcome<O>> outcome = new CompletableFuture<>();
    private I input;
    private boolean inputBound;
    private boolean published;

    public PendingVanillaStage(ChunkCustodyModel custody, ChunkCustodyModel.LifecycleId lifecycle,
                               ChunkCustodyModel.WorkId work, ChunkCustodyModel.StageId stage,
                               TaskCorrelationId correlationId, VanillaChunkStage<I, O> continuation) {
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

    /** Registration callback binds its immutable/exclusively-retained upstream input exactly once. */
    public synchronized void bindInput(I readyInput) {
        if (inputBound) throw new IllegalStateException("vanilla stage input already bound: " + stage);
        input = readyInput; inputBound = true;
    }
    synchronized void publishReady() {
        if (!inputBound) throw new IllegalStateException("cannot publish stage before its vanilla input is retained: " + stage);
        if (published) return;
        custody.ready(stage);
        if (!custody.queue(stage)) throw new IllegalStateException("failed to publish queued stage " + stage);
        published = true;
    }
    synchronized boolean published() { return published; }
    synchronized O invokeOriginal() throws Throwable {
        if (!inputBound) throw new IllegalStateException("unbound stage invoked: " + stage);
        return continuation.apply(input);
    }
    void applied(O value) { outcome.complete(new ChunkStageOutcome.Applied<>(value)); }
    void control(ChunkStageAdmission.Result result) { outcome.complete(new ChunkStageOutcome.Control<>(result)); }
    void vanillaFailure(Throwable failure) { outcome.complete(new ChunkStageOutcome.VanillaFailure<>(failure)); }
    void barrierFailure(Throwable failure) { outcome.complete(new ChunkStageOutcome.BarrierFailure<>(failure)); }
}
