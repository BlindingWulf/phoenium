package dev.phoenium.chunk;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/** World/light-service scoped input ledger. It records ingress only and never retains a RegionLease. */
public final class LightInputService implements ChunkLightingApplicationBridge {
    public enum Layer { BLOCK, SKY }
    public enum Accept { ACCEPTED, DUPLICATE, NOT_DECLARED, STALE, SHUTDOWN }
    public record BatchId(ChunkCustodyModel.LifecycleId lifecycle, ChunkCustodyModel.WorkId work, long inputSequence) {
        public BatchId { Objects.requireNonNull(lifecycle, "lifecycle"); Objects.requireNonNull(work, "work"); if (!work.lifecycle().equals(lifecycle) || inputSequence < 0) throw new IllegalArgumentException("invalid light batch identity"); }
    }
    public record SectionInput(Layer layer, String sectionKey, long inputSequence) {
        public SectionInput { Objects.requireNonNull(layer, "layer"); Objects.requireNonNull(sectionKey, "sectionKey"); if (sectionKey.isBlank() || inputSequence < 0) throw new IllegalArgumentException("invalid light section input"); }
    }
    private static final class Batch {
        final boolean retainRequired; final Set<SectionInput> required; final Set<SectionInput> accepted = new HashSet<>(); boolean retained;
        Batch(boolean retainRequired, Set<SectionInput> required) { this.retainRequired = retainRequired; this.required = Set.copyOf(required); }
        boolean complete() { return (!retainRequired || retained) && accepted.containsAll(required); }
    }
    private final ChunkLifecycleRegistry lifecycles; private final ChunkWorkRegistry works;
    /** Bounded receipt bookkeeping gate only; there is no owner lease or light-engine wait here. */
    private final ReentrantLock gate = new ReentrantLock(); private final Map<BatchId, Batch> batches = new HashMap<>(); private boolean open = true;
    public LightInputService(ChunkLifecycleRegistry lifecycles, ChunkWorkRegistry works) { this.lifecycles = Objects.requireNonNull(lifecycles); this.works = Objects.requireNonNull(works); }
    public boolean register(BatchId id, boolean retainRequired, Set<SectionInput> required) {
        Objects.requireNonNull(required, "required"); gate.lock(); try { if (!open || !current(id)) return false; return batches.putIfAbsent(id, new Batch(retainRequired, required)) == null; } finally { gate.unlock(); }
    }
    public Accept retain(BatchId id) { gate.lock(); try { Batch batch = batch(id); if (batch == null) return status(id); if (batch.retained) return Accept.DUPLICATE; batch.retained = true; return Accept.ACCEPTED; } finally { gate.unlock(); } }
    public Accept section(BatchId id, SectionInput input) { Objects.requireNonNull(input, "input"); gate.lock(); try { Batch batch = batch(id); if (batch == null) return status(id); if (!batch.required.contains(input)) return Accept.NOT_DECLARED; return batch.accepted.add(input) ? Accept.ACCEPTED : Accept.DUPLICATE; } finally { gate.unlock(); } }
    public Optional<LightInputReceipt> receipt(BatchId id) { gate.lock(); try { Batch batch = batches.get(id); return open && current(id) && batch != null && batch.complete() ? Optional.of(new LightInputReceipt(id)) : Optional.empty(); } finally { gate.unlock(); } }
    public void forget(BatchId id) { gate.lock(); try { batches.remove(id); } finally { gate.unlock(); } }
    public void retire(ChunkCustodyModel.LifecycleId lifecycle) { gate.lock(); try { batches.keySet().removeIf(id -> id.lifecycle().equals(lifecycle)); } finally { gate.unlock(); } }
    public void shutdown() { gate.lock(); try { open = false; batches.clear(); } finally { gate.unlock(); } }
    public int entries() { gate.lock(); try { return batches.size(); } finally { gate.unlock(); } }
    private Batch batch(BatchId id) { return open && current(id) ? batches.get(id) : null; }
    private Accept status(BatchId id) { return !open ? Accept.SHUTDOWN : !current(id) ? Accept.STALE : Accept.NOT_DECLARED; }
    private boolean current(BatchId id) { return lifecycles.isCurrent(id.lifecycle()) && works.isCurrentWork(id.work()); }
    @Override public void submitCompute(ChunkCustodyModel.LifecycleId lifecycle, ChunkCustodyModel.WorkId work, String batchKey) { Objects.requireNonNull(lifecycle); Objects.requireNonNull(work); Objects.requireNonNull(batchKey); }
    @Override public void applyLiveNotification(PendingVanillaStage<?, ?> stage) { Objects.requireNonNull(stage); }
}
