package dev.phoenium.chunk;

import dev.phoenium.core.WorldId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Exact, ordered reload ingress. The custody fence protects scalar registration/attempt state;
 * it is never held across a physical call, a callback, or light computation. No payload, holder,
 * engine, callback or RegionLease is retained here. A successful ingress is not propagation. A
 * provider that owns persisted light storage may explicitly acknowledge the same synchronous
 * occurrence as delegated; that path remains distinct from a vanilla mailbox insertion and is
 * only reachable through a provider-specific compatibility adapter.
 */
public final class LightInputService implements ChunkLightingApplicationBridge {
    public enum Layer { BLOCK, SKY }
    public enum Kind { RETAIN, BLOCK, SKY }
    public enum State { REGISTERED, INVOKING, ACCEPTED, FAILED_VANILLA, FAILED_BARRIER, SUPERSEDED, SHUTDOWN }
    public enum Readiness { REGISTERED, NOT_REGISTERED, STALE, FAILED, SHUTDOWN }

    public record BatchId(ChunkCustodyModel.LifecycleId lifecycle, ChunkCustodyModel.WorkId work,
                          ChunkCustodyModel.StageId stage, long inputSequence) {
        public BatchId {
            Objects.requireNonNull(lifecycle); Objects.requireNonNull(work); Objects.requireNonNull(stage);
            if (!work.lifecycle().equals(lifecycle) || !stage.work().equals(work)
                    || stage.kind() != ChunkStageKind.PARSED_APPLY || inputSequence < 0)
                throw new IllegalArgumentException("invalid reload light batch identity");
        }
    }

    public record SectionInput(Layer layer, String sectionKey, long inputSequence) {
        public SectionInput {
            Objects.requireNonNull(layer); Objects.requireNonNull(sectionKey);
            if (sectionKey.isBlank() || inputSequence < 0) throw new IllegalArgumentException("invalid light section input");
        }
    }

    /** Batch contains world/target/lifecycle/work/stage; sequence distinguishes repeated coordinates. */
    public record OccurrenceId(BatchId batch, Kind kind, String sectionKey, long occurrenceSequence) {
        public OccurrenceId {
            Objects.requireNonNull(batch); Objects.requireNonNull(kind); Objects.requireNonNull(sectionKey);
            if (sectionKey.isBlank() || occurrenceSequence < 0) throw new IllegalArgumentException("invalid light occurrence");
        }
    }

    /**
     * Synchronous same-site adapter: acknowledge actual mailbox insertion, not engine method
     * entry/return. Report a swallowed dispatcher rejection through the insertion witness. A
     * provider-specific bridge may use the package-private delegated acknowledgment when that
     * provider deliberately owns the persisted-light read. This operation is invoked immediately;
     * it is never stored or dispatched by the service.
     */
    @FunctionalInterface public interface PhysicalIngress {
        void submit(Insertion insertion) throws Throwable;
    }

    public static final class Insertion {
        private boolean inserted;
        private boolean delegated;
        private boolean closed;
        private ChunkProtocolException rejection;
        private Insertion() { }
        public void inserted() {
            if (closed || inserted || delegated) throw new ChunkProtocolException("duplicate or late ingress acknowledgment");
            inserted = true;
        }
        /** A known external light owner consumed this exact call without using the vanilla mailbox. */
        void delegated() {
            if (closed || inserted || delegated) throw new ChunkProtocolException("duplicate or late ingress acknowledgment");
            delegated = true;
        }
        public void rejected(Throwable failure) {
            if (closed) throw new ChunkProtocolException("late synchronous ingress rejection", failure);
            rejection = new ChunkProtocolException("light dispatcher rejected the original ingress", Objects.requireNonNull(failure));
        }
    }

    public record OccurrenceSnapshot(OccurrenceId id, State state, boolean inserted, boolean delegated) { }
    public record Snapshot(BatchId id, List<OccurrenceSnapshot> occurrences, boolean sealed,
                           State failure, boolean receiptPresent) { }

    private static final class Occurrence {
        final OccurrenceId id;
        State state = State.REGISTERED;
        boolean inserted;
        boolean delegated;
        Occurrence(OccurrenceId id) { this.id = id; }
    }
    private static final class Batch {
        final Object registration = new Object();
        final List<Occurrence> occurrences;
        int cursor;
        boolean sealed;
        boolean cleanupRequested;
        State failure;
        LightInputReceipt receipt;
        Batch(List<OccurrenceId> ids) { occurrences = ids.stream().map(Occurrence::new).toList(); }
    }

    private final ChunkCustodyModel custody;
    private final ChunkWorkRegistry works;
    private final WorldId world;
    private final String serverSession;
    private final long worldInstanceSequence;
    private final Map<BatchId, Batch> batches = new HashMap<>();
    private final Map<ChunkCustodyModel.StageId, BatchId> byStage = new HashMap<>();
    private boolean open = true;

    public LightInputService(ChunkCustodyModel custody, ChunkWorkRegistry works, WorldId world,
                             String serverSession, long worldInstanceSequence) {
        this.custody = Objects.requireNonNull(custody); this.works = Objects.requireNonNull(works);
        this.world = Objects.requireNonNull(world); this.serverSession = Objects.requireNonNull(serverSession);
        if (serverSession.isBlank() || worldInstanceSequence < 0) throw new IllegalArgumentException("invalid light service instance");
        this.worldInstanceSequence = worldInstanceSequence;
    }
    boolean belongsTo(ChunkCustodyModel expected) { return custody == expected; }

    /** Ordered scalar declaration only; caller retains the original parsed input exclusively. */
    public boolean register(BatchId id, boolean retainRequired, List<SectionInput> inputs) {
        Objects.requireNonNull(id); Objects.requireNonNull(inputs);
        List<OccurrenceId> ids = new ArrayList<>(inputs.size() + (retainRequired ? 1 : 0));
        if (retainRequired) ids.add(new OccurrenceId(id, Kind.RETAIN, "chunk", 0));
        for (SectionInput input : inputs)
            ids.add(new OccurrenceId(id, input.layer() == Layer.BLOCK ? Kind.BLOCK : Kind.SKY,
                    input.sectionKey(), input.inputSequence()));
        if (new HashSet<>(ids).size() != ids.size()) throw new ChunkProtocolException("duplicate declared physical occurrence");
        custody.gate().lock();
        try {
            if (!open || !custody.admissionsOpenLocked() || !currentLocked(id)) return false;
            if (byStage.containsKey(id.stage()) || custody.stageState(id.stage()).isPresent())
                throw new ChunkProtocolException("reload batch/stage was already registered or terminal");
            batches.put(id, new Batch(ids)); byStage.put(id.stage(), id);
            return true;
        } finally { custody.gate().unlock(); }
    }

    public Readiness registeredForParsedApply(BatchId id, ChunkCustodyModel.StageId stage) {
        custody.gate().lock();
        try {
            if (!id.stage().equals(stage) || !currentLocked(id)) return Readiness.STALE;
            if (!open) return Readiness.SHUTDOWN;
            Batch batch = batches.get(id);
            if (batch == null) return Readiness.NOT_REGISTERED;
            if (batch.failure != null) return Readiness.FAILED;
            return Readiness.REGISTERED;
        } finally { custody.gate().unlock(); }
    }

    /** Used by the admission fence to pin this exact service registration into its claim. */
    Object registrationLocked(BatchId id) {
        if (!custody.gate().isHeldByCurrentThread()) throw new ChunkProtocolException("registration pin outside custody fence");
        Batch batch = batches.get(id);
        if (batch == null) throw new ChunkProtocolException("missing registration at claim");
        return batch.registration;
    }

    /** INVOKING is committed before the original call. No attempted occurrence can be reset. */
    public void invoke(ChunkStageClaim claim, OccurrenceId id, PhysicalIngress original) throws Throwable {
        Objects.requireNonNull(original);
        claim.require(id.batch().stage());
        final Occurrence occurrence;
        custody.gate().lock();
        try {
            Batch batch = claimedBatchLocked(claim, id.batch());
            if (batch.failure != null || batch.sealed || batch.cursor >= batch.occurrences.size())
                throw breach(batch, "physical ingress after batch completion/failure");
            occurrence = batch.occurrences.get(batch.cursor);
            if (!occurrence.id.equals(id) || occurrence.state != State.REGISTERED)
                throw breach(batch, "duplicate, unexpected or reordered physical ingress");
            occurrence.state = State.INVOKING;
        } finally { custody.gate().unlock(); }

        Insertion insertion = new Insertion();
        Throwable failure = null;
        try {
            original.submit(insertion);
            if (insertion.rejection != null) throw insertion.rejection;
            if (!insertion.inserted && !insertion.delegated)
                throw new ChunkProtocolException("physical light call returned without witnessed insertion or external ownership");
        } catch (Throwable thrown) { failure = thrown; }
        finally { insertion.closed = true; }

        custody.gate().lock();
        try {
            Batch batch = claimedBatchLocked(claim, id.batch());
            occurrence.inserted = insertion.inserted;
            occurrence.delegated = insertion.delegated;
            if (failure == null && batch.failure == null) {
                occurrence.state = State.ACCEPTED; batch.cursor++;
            } else {
                if (failure == null) failure = new ChunkProtocolException("ingress was invalidated by a nested protocol breach");
                occurrence.state = failure instanceof ChunkProtocolException ? State.FAILED_BARRIER : State.FAILED_VANILLA;
                batch.failure = occurrence.state;
            }
        } finally { custody.gate().unlock(); }
        if (failure != null) throw failure;
    }

    /**
     * Exact C2ME async persisted-read ingress.  The private invocation is a worker-local,
     * one-shot proof for this batch only; it is deliberately not a replacement for a
     * {@link ChunkStageClaim} and cannot authorize a regional mutation.
     */
    void invokePrivateC2meRead(PrivateC2meReadInvocation invocation, OccurrenceId id,
                               PhysicalIngress original) throws Throwable {
        Objects.requireNonNull(invocation); Objects.requireNonNull(id); Objects.requireNonNull(original);
        if (!invocation.authorizes(this, id.batch()))
            throw new ChunkProtocolException("foreign private C2ME light capability");
        final Occurrence occurrence;
        custody.gate().lock();
        try {
            Batch batch = privateC2meBatchLocked(id.batch());
            if (batch.failure != null || batch.sealed || batch.cursor >= batch.occurrences.size())
                throw breach(batch, "private C2ME physical ingress after batch completion/failure");
            occurrence = batch.occurrences.get(batch.cursor);
            if (!occurrence.id.equals(id) || occurrence.state != State.REGISTERED)
                throw breach(batch, "duplicate, unexpected or reordered private C2ME physical ingress");
            occurrence.state = State.INVOKING;
        } finally { custody.gate().unlock(); }

        Insertion insertion = new Insertion();
        Throwable failure = null;
        try {
            original.submit(insertion);
            if (insertion.rejection != null) throw insertion.rejection;
            if (!insertion.inserted && !insertion.delegated)
                throw new ChunkProtocolException("private C2ME light call returned without witnessed insertion or external ownership");
            if (!invocation.authorizes(this, id.batch()))
                throw new ChunkProtocolException("private C2ME light capability changed during ingress");
        } catch (Throwable thrown) { failure = thrown; }
        finally { insertion.closed = true; }

        custody.gate().lock();
        try {
            Batch batch = batches.get(id.batch());
            if (batch == null) {
                if (failure != null) throw failure;
                throw new ChunkProtocolException("private C2ME light batch disappeared during ingress");
            }
            occurrence.inserted = insertion.inserted;
            occurrence.delegated = insertion.delegated;
            if (failure == null && open && custody.admissionsOpenLocked() && currentLocked(id.batch())
                    && batch.failure == null) {
                occurrence.state = State.ACCEPTED;
                batch.cursor++;
            } else {
                if (failure == null) failure = new ChunkProtocolException("private C2ME ingress became stale during physical call");
                occurrence.state = failure instanceof ChunkProtocolException ? State.FAILED_BARRIER : State.FAILED_VANILLA;
                batch.failure = occurrence.state;
            }
        } finally { custody.gate().unlock(); }
        if (failure != null) throw failure;
    }

    /** Called only at normal SerializableChunkData.read return, including a genuinely empty read. */
    public LightInputReceipt sealRead(ChunkStageClaim claim, BatchId id) {
        claim.require(id.stage());
        custody.gate().lock();
        try {
            Batch batch = claimedBatchLocked(claim, id);
            if (batch.sealed || batch.failure != null || batch.cursor != batch.occurrences.size())
                throw breach(batch, "normal read completion has an incomplete or already sealed light batch");
            batch.sealed = true; batch.receipt = new LightInputReceipt(id);
            return batch.receipt;
        } finally { custody.gate().unlock(); }
    }

    LightInputReceipt sealPrivateC2meRead(PrivateC2meReadInvocation invocation, BatchId id) {
        Objects.requireNonNull(invocation); Objects.requireNonNull(id);
        if (!invocation.authorizes(this, id))
            throw new ChunkProtocolException("foreign private C2ME light seal capability");
        custody.gate().lock();
        try {
            Batch batch = privateC2meBatchLocked(id);
            if (batch.sealed || batch.failure != null || batch.cursor != batch.occurrences.size())
                throw breach(batch, "normal private C2ME read completion has an incomplete or already sealed light batch");
            batch.sealed = true;
            batch.receipt = new LightInputReceipt(id);
            return batch.receipt;
        } finally { custody.gate().unlock(); }
    }

    /** No wait: normal reload success with missing/forged evidence is an invariant failure. */
    public LightInputReceipt requireSuccess(ChunkStageClaim claim, BatchId id) {
        claim.require(id.stage());
        custody.gate().lock();
        try {
            Batch batch = claimedBatchLocked(claim, id);
            if (!batch.sealed || batch.receipt == null || batch.failure != null)
                throw breach(batch, "PARSED_APPLY returned without its actual sealed light receipt");
            return batch.receipt;
        } finally { custody.gate().unlock(); }
    }

    /** The subsequent EMPTY publication must authenticate this exact service-issued object. */
    public boolean validates(LightInputReceipt receipt, BatchId expected) {
        Objects.requireNonNull(receipt); Objects.requireNonNull(expected);
        custody.gate().lock();
        try {
            Batch batch = batches.get(expected);
            return open && currentLocked(expected) && receipt.batch().equals(expected)
                    && batch != null && batch.failure == null && batch.receipt == receipt;
        } finally { custody.gate().unlock(); }
    }

    public Optional<LightInputReceipt> receipt(BatchId id) {
        custody.gate().lock();
        try {
            Batch batch = batches.get(id);
            // A claim winner may finish during a pending fence. This grants no later admission.
            return batch != null && (currentLocked(id) && open || claimedLocked(id))
                    ? Optional.ofNullable(batch.receipt) : Optional.empty();
        } finally { custody.gate().unlock(); }
    }

    void failedRead(ChunkStageClaim claim, BatchId id, Throwable failure) {
        claim.require(id.stage());
        custody.gate().lock();
        try {
            Batch batch = claimedBatchLocked(claim, id);
            if (batch.failure == null) batch.failure = failure instanceof ChunkProtocolException ? State.FAILED_BARRIER : State.FAILED_VANILLA;
        } finally { custody.gate().unlock(); }
    }

    public Optional<Snapshot> snapshot(BatchId id) {
        custody.gate().lock();
        try {
            Batch batch = batches.get(id);
            return batch == null ? Optional.empty() : Optional.of(new Snapshot(id,
                    batch.occurrences.stream().map(o -> new OccurrenceSnapshot(o.id, o.state, o.inserted, o.delegated)).toList(),
                    batch.sealed, batch.failure, batch.receipt != null));
        } finally { custody.gate().unlock(); }
    }

    /** Consumer/terminal acknowledgment. A live claimed batch remains until synchronous exit. */
    public void forget(BatchId id) {
        custody.gate().lock();
        try { discardLocked(id, State.SUPERSEDED); } finally { custody.gate().unlock(); }
    }
    public void retire(ChunkCustodyModel.LifecycleId lifecycle) {
        custody.gate().lock();
        try {
            for (BatchId id : List.copyOf(batches.keySet()))
                if (id.lifecycle().equals(lifecycle)) discardLocked(id, State.SUPERSEDED);
        } finally { custody.gate().unlock(); }
    }
    public void shutdown() {
        custody.gate().lock();
        try {
            open = false;
            for (BatchId id : List.copyOf(batches.keySet())) discardLocked(id, State.SHUTDOWN);
        } finally { custody.gate().unlock(); }
    }
    void stageExited(BatchId id) {
        custody.gate().lock();
        try {
            Batch batch = batches.get(id);
            if (batch != null && batch.cleanupRequested && !claimedLocked(id)) removeLocked(id);
        } finally { custody.gate().unlock(); }
    }
    public int entries() {
        custody.gate().lock(); try { return batches.size(); } finally { custody.gate().unlock(); }
    }
    public int occurrenceEntries() {
        custody.gate().lock(); try { return batches.values().stream().mapToInt(b -> b.occurrences.size()).sum(); }
        finally { custody.gate().unlock(); }
    }
    public int receiptEntries() {
        custody.gate().lock(); try { return (int) batches.values().stream().filter(b -> b.receipt != null).count(); }
        finally { custody.gate().unlock(); }
    }

    private Batch claimedBatchLocked(ChunkStageClaim claim, BatchId id) {
        Batch batch = batches.get(id);
        if (!claim.belongsTo(custody) || !claim.stage().equals(id.stage()) || !boundInstance(id)
                || !claimedLocked(id) || batch == null || !claim.bindsLight(batch.registration))
            throw new ChunkProtocolException("unregistered or mismatched light batch at physical ingress");
        return batch;
    }
    private Batch privateC2meBatchLocked(BatchId id) {
        Batch batch = batches.get(id);
        if (!open || !custody.admissionsOpenLocked() || !currentLocked(id) || batch == null)
            throw new ChunkProtocolException("stale or unregistered private C2ME light batch");
        return batch;
    }
    private boolean claimedLocked(BatchId id) { return custody.stageState(id.stage()).orElse(null) == ChunkCustodyModel.StageState.CLAIMED; }
    private boolean currentLocked(BatchId id) { return boundInstance(id) && works.hasReservation(id.work()) && custody.metadataEntitledLocked(id.work()); }
    private boolean boundInstance(BatchId id) {
        return world.equals(id.lifecycle().world()) && serverSession.equals(id.lifecycle().serverSession())
                && worldInstanceSequence == id.lifecycle().worldInstanceSequence();
    }
    private ChunkProtocolException breach(Batch batch, String message) {
        batch.failure = State.FAILED_BARRIER;
        return new ChunkProtocolException(message);
    }
    private void discardLocked(BatchId id, State terminal) {
        Batch batch = batches.get(id);
        if (batch == null) return;
        if (claimedLocked(id)) { batch.cleanupRequested = true; return; }
        custody.disarmLocked(id.stage(), terminal == State.SHUTDOWN
                ? ChunkCustodyModel.StageState.CANCELLED : ChunkCustodyModel.StageState.SUPERSEDED);
        removeLocked(id);
    }
    private void removeLocked(BatchId id) { batches.remove(id); byStage.remove(id.stage(), id); }

    @Override public void submitCompute(ChunkCustodyModel.LifecycleId lifecycle, ChunkCustodyModel.WorkId work, String batchKey) { Objects.requireNonNull(lifecycle); Objects.requireNonNull(work); Objects.requireNonNull(batchKey); }
    @Override public void applyLiveNotification(PendingVanillaStage<?, ?> stage) { Objects.requireNonNull(stage); }
}
