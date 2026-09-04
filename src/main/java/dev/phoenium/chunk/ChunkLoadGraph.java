package dev.phoenium.chunk;

import dev.phoenium.task.TaskCorrelationId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.ReentrantLock;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;

/**
 * Finite EMPTY-load graph. Producer callbacks only retain typed inputs and queue a stage; the
 * original POI, parsed-apply, recovery, and holder bodies run under separate owner claims.
 */
public final class ChunkLoadGraph {
    public record Token(ChunkCustodyModel.LifecycleId lifecycle, ChunkCustodyModel.WorkId work,
                        GenerationChunkHolder holder, Object reservation) {
        public Token {
            Objects.requireNonNull(lifecycle); Objects.requireNonNull(work);
            Objects.requireNonNull(holder); Objects.requireNonNull(reservation);
            if (!work.lifecycle().equals(lifecycle)) throw new ChunkProtocolException("load token lineage mismatch");
        }
    }
    public record PoiInput(CompletableFuture<?> source, Object packedResult) {
        public PoiInput { Objects.requireNonNull(source); }
    }
    public record HolderInput(ChunkAccess value, Throwable failure) { }

    public interface Environment {
        ChunkPrerequisiteServices services();
        void enqueue(PendingVanillaStage<?, ?> pending);
        void requireHolder(Token token);
        boolean poiAlreadyInstalled(Token token, CompletableFuture<?> source);
        void installPoi(Token token, PoiInput input, ChunkStageClaim claim) throws Throwable;
        LightInputService.BatchId prepareReload(ChunkLoadGraph graph, SerializableChunkData data);
        ChunkAccess apply(Token token, Optional<SerializableChunkData> data, LightInputService.BatchId batch,
                          ChunkStageClaim claim) throws Throwable;
        ChunkAccess recover(Token token, Throwable failure, ChunkStageClaim claim) throws Throwable;
        ChunkResult<ChunkAccess> publish(Token token, HolderInput input, ChunkStageClaim claim) throws Throwable;
        void unload(Token token);
        void terminal(ChunkLoadGraph graph, ChunkCustodyModel.StageId stage,
                      ChunkStageAdmission.Result reason, Throwable failure);
        void barrier(ChunkLoadGraph graph, ChunkCustodyModel.StageId stage, Throwable failure);
        void settled(ChunkLoadGraph graph);
    }

    /** Only this graph can issue control, authenticated by its exact recorded terminal object. */
    private static final class CustodyAborted extends RuntimeException {
        final ChunkLoadGraph graph;
        final Token token;
        final ChunkCustodyModel.StageId stage;
        final ChunkStageAdmission.Result reason;
        CustodyAborted(ChunkLoadGraph graph, ChunkCustodyModel.StageId stage,
                       ChunkStageAdmission.Result reason, Throwable cause) {
            super("chunk custody " + reason, cause, false, false);
            this.graph = graph; this.token = graph.token; this.stage = stage; this.reason = reason;
        }
    }

    private final Environment environment;
    private final Token token;
    private final ChunkCustodyModel custody;
    private final PoiInstallService.InstallId poiId;
    private final PendingVanillaStage<PoiInput, Void> poiInstall;
    private final PendingVanillaStage<Optional<SerializableChunkData>, ChunkAccess> parsed;
    private final PendingVanillaStage<Throwable, ChunkAccess> recovery;
    private final PendingVanillaStage<HolderInput, ChunkResult<ChunkAccess>> publication;
    private final CompletableFuture<Void> poiInner = new CompletableFuture<>();
    private final CompletableFuture<ChunkAccess> parsedInner = new CompletableFuture<>();
    private final CompletableFuture<ChunkAccess> recoveryInner = new CompletableFuture<>();
    private final CompletableFuture<ChunkResult<ChunkAccess>> publicationInner = new CompletableFuture<>();
    /** Graph-state gate only. It is never held across an executor, Minecraft body, or future completion. */
    private final ReentrantLock stateGate = new ReentrantLock();
    private CompletableFuture<?> poiSource;
    private CompletableFuture<Void> poiDependent;
    private CompletableFuture<ChunkAccess> dependent;
    private CompletableFuture<ChunkAccess> returned;
    private CompletableFuture<ChunkResult<ChunkAccess>> holderFuture;
    private LightInputService.BatchId batch;
    private boolean armed;
    private boolean poiReady, parsedReady, recoveryReady, publicationReady;
    private boolean poiQueued, parsedQueued, recoveryQueued, publicationQueued;
    private boolean reloadSuccess, unloaded, cleaned;
    private CustodyAborted terminal;

    public ChunkLoadGraph(Environment environment, Token token) {
        this.environment = Objects.requireNonNull(environment); this.token = Objects.requireNonNull(token);
        custody = environment.services().custody();
        poiId = new PoiInstallService.InstallId(token.lifecycle(), token.work(), "chunk", 0);
        poiInstall = pending(ChunkStageKind.POI_INSTALL, "EMPTY", 0, (input, claim) -> {
            environment.requireHolder(token); environment.installPoi(token, input, claim); return null;
        });
        parsed = pending(ChunkStageKind.PARSED_APPLY, "EMPTY", 1, (input, claim) -> {
            environment.requireHolder(token);
            ChunkAccess value = environment.apply(token, input, batch, claim);
            if (input.isPresent()) {
                environment.services().light().requireSuccess(claim, batch);
                stateGate.lock(); try { reloadSuccess = true; } finally { stateGate.unlock(); }
            }
            return value;
        });
        recovery = pending(ChunkStageKind.LOAD_RECOVERY, "EMPTY", 2, (input, claim) -> {
            environment.requireHolder(token); return environment.recover(token, input, claim);
        });
        publication = pending(ChunkStageKind.STATUS_PUBLISH, "EMPTY", 3, (input, claim) -> {
            environment.requireHolder(token); return environment.publish(token, input, claim);
        });
        poiInstall.outcome().thenAccept(this::finishPoi);
        parsed.outcome().thenAccept(outcome -> finish(parsed, outcome, parsedInner));
        recovery.outcome().thenAccept(outcome -> finish(recovery, outcome, recoveryInner));
        publication.outcome().thenAccept(outcome -> {
            finish(publication, outcome, publicationInner);
            cleanupIfSettled();
        });
    }

    private <I, O> PendingVanillaStage<I, O> pending(ChunkStageKind kind, String key, long sequence,
                                                      VanillaChunkStage.Admitted<I, O> body) {
        return new PendingVanillaStage<>(custody, token.lifecycle(), token.work(),
                new ChunkCustodyModel.StageId(token.work(), kind, key, sequence),
                new TaskCorrelationId(sequence), body);
    }

    public Token token() { return token; }
    public ChunkCustodyModel.StageId poiStage() { return poiInstall.stage(); }
    public ChunkCustodyModel.StageId parsedStage() { return parsed.stage(); }
    public ChunkCustodyModel.StageId recoveryStage() { return recovery.stage(); }
    public ChunkCustodyModel.StageId publicationStage() { return publication.stage(); }
    public PoiInstallService.InstallId poiId() { return poiId; }
    public LightInputService.BatchId batch() { return batch; }

    /** Preserves Q as the producer and flattens its separately admitted packed-data installation. */
    public CompletableFuture<Void> registerPoi(CompletableFuture<?> source, Executor mainExecutor) {
        Objects.requireNonNull(source); Objects.requireNonNull(mainExecutor);
        stateGate.lock();
        try {
            if (poiSource != null) throw new ChunkProtocolException("POI source registered twice");
            poiSource = source;
            if (terminal != null) {
                poiDependent = failed(terminal);
                return poiDependent;
            }
            poiDependent = source.thenComposeAsync(value -> poiReady(source, value), submission(mainExecutor, poiInstall.stage()));
            poiDependent.whenComplete((ignored, failure) -> cleanupIfSettled());
            return poiDependent;
        } finally { stateGate.unlock(); }
    }

    private CompletableFuture<Void> poiReady(CompletableFuture<?> source, Object packedResult) {
        if (terminalSnapshot() != null) return poiInner;
        PoiInstallService.Claim claim = environment.services().poi().claim(poiId);
        if (claim == PoiInstallService.Claim.STALE) {
            abort(ChunkStageAdmission.Result.SUPERSEDED, poiInstall.stage(), null); return poiInner;
        }
        if (claim == PoiInstallService.Claim.SHUTDOWN) {
            abort(ChunkStageAdmission.Result.SHUTDOWN, poiInstall.stage(), null); return poiInner;
        }
        if (claim == PoiInstallService.Claim.INSTALLED) {
            requirePoiReceipt(); poiInner.complete(null); return poiInner;
        }
        if (claim == PoiInstallService.Claim.INSTALLING) return poiInner;
        if (packedResult == null) {
            try {
                if (!environment.poiAlreadyInstalled(token, source))
                    throw new ChunkProtocolException("completed-null POI prefetch lacks installed storage state");
                requirePoiSettlement(); poiInner.complete(null);
            } catch (ChunkProtocolException failure) {
                abort(ChunkStageAdmission.Result.FAILED_BARRIER, poiInstall.stage(), failure);
            }
            return poiInner;
        }
        if (poiInstall.bindInput(new PoiInput(source, packedResult))) markReady(poiInstall);
        return poiInner;
    }

    /** U is supplied by the lexical registrar after the unchanged P/Q producer operations. */
    public CompletableFuture<ChunkAccess> register(CompletableFuture<Optional<SerializableChunkData>> upstream,
                                                    Executor mainExecutor) {
        Objects.requireNonNull(upstream); Objects.requireNonNull(mainExecutor);
        stateGate.lock();
        try {
            if (dependent != null) throw new ChunkProtocolException("load graph registered twice");
            if (terminal != null) {
                dependent = failed(terminal); returned = failed(terminal); return returned;
            }
            dependent = upstream.thenComposeAsync(this::parsedReady, submission(mainExecutor, parsed.stage()));
            dependent.whenComplete((ignored, failure) -> cleanupIfSettled());
            returned = dependent.exceptionallyComposeAsync(this::recoveryReady, submission(mainExecutor, recovery.stage()));
            return returned;
        } finally { stateGate.unlock(); }
    }

    /** Exact-R lookup calls this adjacent adapter once; no coordinate or ambient holder lookup occurs. */
    public CompletableFuture<ChunkResult<ChunkAccess>> attachHolder(CompletableFuture<ChunkAccess> exact,
                                                                    GenerationChunkHolder holder) {
        Objects.requireNonNull(exact); Objects.requireNonNull(holder);
        stateGate.lock();
        try {
            if (exact != returned || holder != token.holder() || holderFuture != null)
                throw new ChunkProtocolException("holder/R binding mismatch");
            environment.requireHolder(token);
        } finally { stateGate.unlock(); }

        CompletableFuture<ChunkResult<ChunkAccess>> attached = exact.handle(this::holderReady).thenCompose(value -> value);
        List<PendingVanillaStage<?, ?>> ready;
        stateGate.lock();
        try {
            holderFuture = attached; armed = true; ready = readyToQueueLocked();
        } finally { stateGate.unlock(); }
        ready.forEach(environment::enqueue);
        if (terminalSnapshot() != null) settleTerminal();
        return attached;
    }

    private CompletableFuture<ChunkAccess> parsedReady(Optional<SerializableChunkData> input) {
        Objects.requireNonNull(input);
        if (terminalSnapshot() != null) return parsedInner;
        try {
            if (input.isPresent()) {
                LightInputService.BatchId declared = environment.prepareReload(this, input.orElseThrow());
                bindReload(declared);
                parsed.requireLoadPrerequisites(environment.services().poi(), poiId, environment.services().metadata());
            } else {
                parsed.requireLoadPrerequisites(null, null, environment.services().metadata());
            }
            if (parsed.bindInput(input)) markReady(parsed);
        } catch (ChunkProtocolException failure) {
            abort(ChunkStageAdmission.Result.FAILED_BARRIER, parsed.stage(), failure);
        }
        return parsedInner;
    }

    private CompletableFuture<ChunkAccess> recoveryReady(Throwable failure) {
        CustodyAborted control = authenticate(failure);
        if (control != null) {
            recoveryInner.completeExceptionally(control); return recoveryInner;
        }
        if (terminalSnapshot() != null) return recoveryInner;
        if (recovery.bindInput(failure)) markReady(recovery);
        return recoveryInner;
    }

    private CompletableFuture<ChunkResult<ChunkAccess>> holderReady(ChunkAccess value, Throwable failure) {
        CustodyAborted control = failure == null ? null : authenticate(failure);
        if (control != null) {
            settleTerminal(); return publicationInner;
        }
        if (terminalSnapshot() != null) return publicationInner;
        try {
            stateGate.lock();
            try {
                if (reloadSuccess) {
                    LightInputReceipt receipt = environment.services().light().receipt(batch).orElseThrow(
                            () -> new ChunkProtocolException("reload publication lacks exact light receipt"));
                    publication.requireReloadPublication(environment.services().light(), receipt);
                }
            } finally { stateGate.unlock(); }
            if (publication.bindInput(new HolderInput(value, failure))) markReady(publication);
        } catch (ChunkProtocolException breach) {
            abort(ChunkStageAdmission.Result.FAILED_BARRIER, publication.stage(), breach);
        }
        return publicationInner;
    }

    private void bindReload(LightInputService.BatchId declared) {
        stateGate.lock();
        try {
            if (batch != null || !declared.stage().equals(parsed.stage()))
                throw new ChunkProtocolException("duplicate/wrong reload declaration");
            batch = declared; parsed.requireReloadLight(environment.services().light(), declared);
        } finally { stateGate.unlock(); }
    }

    private void markReady(PendingVanillaStage<?, ?> stage) {
        List<PendingVanillaStage<?, ?>> ready;
        stateGate.lock();
        try {
            if (stage == poiInstall) poiReady = true;
            else if (stage == parsed) parsedReady = true;
            else if (stage == recovery) recoveryReady = true;
            else if (stage == publication) publicationReady = true;
            else throw new ChunkProtocolException("foreign load stage readiness");
            ready = readyToQueueLocked();
        } finally { stateGate.unlock(); }
        ready.forEach(environment::enqueue);
    }

    private List<PendingVanillaStage<?, ?>> readyToQueueLocked() {
        if (!armed || terminal != null) return List.of();
        java.util.ArrayList<PendingVanillaStage<?, ?>> result = new java.util.ArrayList<>(1);
        if (poiReady && !poiQueued) { poiQueued = true; result.add(poiInstall); }
        if (parsedReady && !parsedQueued) { parsedQueued = true; result.add(parsed); }
        if (recoveryReady && !recoveryQueued) { recoveryQueued = true; result.add(recovery); }
        if (publicationReady && !publicationQueued) { publicationQueued = true; result.add(publication); }
        return List.copyOf(result);
    }

    private void finishPoi(ChunkStageOutcome<Void> outcome) {
        switch (outcome) {
            case ChunkStageOutcome.Applied<Void> ignored -> {
                if (terminalSnapshot() == null) {
                    try { requirePoiSettlement(); poiInner.complete(null); }
                    catch (ChunkProtocolException failure) {
                        abort(ChunkStageAdmission.Result.FAILED_BARRIER, poiInstall.stage(), failure);
                    }
                } else settleTerminal();
            }
            case ChunkStageOutcome.VanillaFailure<Void> failed -> poiInner.completeExceptionally(failed.failure());
            case ChunkStageOutcome.BarrierFailure<Void> failed ->
                    abort(ChunkStageAdmission.Result.FAILED_BARRIER, poiInstall.stage(), failed.failure());
            case ChunkStageOutcome.Control<Void> control -> abort(control.result(), poiInstall.stage(), null);
        }
    }

    private void requirePoiSettlement() {
        if (environment.services().poi().installed(poiId) != PoiInstallService.Settlement.INSTALLED)
            throw new ChunkProtocolException("POI install receipt settlement mismatch");
        requirePoiReceipt();
    }

    private void requirePoiReceipt() {
        if (environment.services().poi().receipt(poiId).isEmpty())
            throw new ChunkProtocolException("POI install completed without exact receipt");
    }

    private <O> void finish(PendingVanillaStage<?, O> stage, ChunkStageOutcome<O> outcome,
                            CompletableFuture<O> inner) {
        if (terminalSnapshot() != null) {
            if (stage == publication && outcome instanceof ChunkStageOutcome.Applied<O> applied) {
                inner.complete(applied.value()); cleanupIfSettled();
            } else settleTerminal();
            return;
        }
        switch (outcome) {
            case ChunkStageOutcome.Applied<O> applied -> inner.complete(applied.value());
            case ChunkStageOutcome.VanillaFailure<O> failed -> inner.completeExceptionally(failed.failure());
            case ChunkStageOutcome.BarrierFailure<O> failed ->
                    abort(ChunkStageAdmission.Result.FAILED_BARRIER, stage.stage(), failed.failure());
            case ChunkStageOutcome.Control<O> control -> abort(control.result(), stage.stage(), null);
        }
    }

    /** Accounts exact callback submission rejection as infrastructure failure, never vanilla data failure. */
    private Executor submission(Executor original, ChunkCustodyModel.StageId stage) {
        return task -> {
            try { original.execute(task); }
            catch (RuntimeException failure) {
                environment.barrier(this, stage, failure);
                abort(ChunkStageAdmission.Result.FAILED_BARRIER, stage, failure);
                throw failure;
            }
        };
    }

    public void shutdown() { abort(ChunkStageAdmission.Result.SHUTDOWN, parsed.stage(), null); }
    public void superseded() { abort(ChunkStageAdmission.Result.SUPERSEDED, parsed.stage(), null); }

    private void abort(ChunkStageAdmission.Result reason, ChunkCustodyModel.StageId stage, Throwable failure) {
        if (reason == ChunkStageAdmission.Result.STALE_ROUTE || reason == ChunkStageAdmission.Result.DUPLICATE
                || reason == ChunkStageAdmission.Result.NOT_READY || reason == ChunkStageAdmission.Result.APPLIED
                || reason == ChunkStageAdmission.Result.FAILED_VANILLA)
            throw new ChunkProtocolException("nonterminal disposition cannot abort graph");
        boolean first;
        stateGate.lock();
        try {
            first = terminal == null;
            if (first) {
                // Fence the existing work authority before any outward completion can
                // reenter this graph. A winning claim and its holder/receipts survive
                // until synchronous exit; only unclaimed work loses admission here.
                custody.retireWork(token.work(), reason != ChunkStageAdmission.Result.SUPERSEDED);
                terminal = new CustodyAborted(this, stage, reason, failure);
            }
        } finally { stateGate.unlock(); }
        if (first) environment.terminal(this, stage, reason, failure);
        if (first && reason == ChunkStageAdmission.Result.FAILED_BARRIER) custody.closeAdmissions();
        if (first) for (var pending : List.of(poiInstall, parsed, recovery, publication)) pending.control(reason);
        settleTerminal();
    }

    private void settleTerminal() {
        CustodyAborted stopped = terminalSnapshot();
        // APPLIED/FAILED can already be recorded while the claim's finally block
        // still owns the lease. Reclaim only after the claim itself is released.
        if (stopped == null || custody.hasActiveClaim(token.work())) return;

        CompletableFuture<ChunkResult<ChunkAccess>> attached;
        boolean shouldUnload;
        stateGate.lock();
        try { attached = holderFuture; shouldUnload = armed && !unloaded; if (shouldUnload) unloaded = true; }
        finally { stateGate.unlock(); }
        if (shouldUnload) environment.unload(token);
        if (attached != null) attached.complete(GenerationChunkHolder.UNLOADED_CHUNK);
        publicationInner.complete(GenerationChunkHolder.UNLOADED_CHUNK);
        if (returned != null) returned.completeExceptionally(stopped);
        if (dependent != null) dependent.completeExceptionally(stopped);
        if (poiDependent != null) poiDependent.completeExceptionally(stopped);
        recoveryInner.completeExceptionally(stopped);
        parsedInner.completeExceptionally(stopped);
        poiInner.completeExceptionally(stopped);
        cleanupIfSettled();
    }

    private CustodyAborted terminalSnapshot() {
        stateGate.lock(); try { return terminal; } finally { stateGate.unlock(); }
    }

    private CustodyAborted authenticate(Throwable failure) {
        Throwable candidate = failure instanceof CompletionException ? failure.getCause() : failure;
        if (!(candidate instanceof CustodyAborted control)) return null;
        CustodyAborted stopped = terminalSnapshot();
        if (control.graph != this || control.token != token || control != stopped)
            throw new ChunkProtocolException("foreign or unrecorded private chunk control");
        return control;
    }

    private void cleanupIfSettled() {
        boolean settle;
        stateGate.lock();
        try {
            settle = !cleaned && armed && !custody.hasActiveClaim(token.work()) && publicationInner.isDone()
                    && dependent != null && dependent.isDone()
                    && poiDependent != null && poiDependent.isDone();
            if (settle) cleaned = true;
        } finally { stateGate.unlock(); }
        if (!settle) return;
        for (var pending : List.of(poiInstall, parsed, recovery, publication)) pending.discardUnused();
        if (batch != null) environment.services().light().forget(batch);
        environment.services().poi().forget(poiId);
        environment.settled(this);
    }

    private static <T> CompletableFuture<T> failed(Throwable failure) {
        CompletableFuture<T> result = new CompletableFuture<>(); result.completeExceptionally(failure); return result;
    }

    public CompletableFuture<ChunkAccess> dependent() { return dependent; }
    public CompletableFuture<ChunkAccess> returned() { return returned; }
    public CompletableFuture<ChunkResult<ChunkAccess>> holderFuture() { return holderFuture; }
    public CompletableFuture<Void> poiInner() { return poiInner; }
    public CompletableFuture<ChunkAccess> parsedInner() { return parsedInner; }
    public CompletableFuture<ChunkAccess> recoveryInner() { return recoveryInner; }
    public CompletableFuture<ChunkResult<ChunkAccess>> publicationInner() { return publicationInner; }
    public boolean retainsInputs() {
        return List.of(poiInstall, parsed, recovery, publication).stream()
                .anyMatch(PendingVanillaStage::retainsInputOrContinuation);
    }
}
