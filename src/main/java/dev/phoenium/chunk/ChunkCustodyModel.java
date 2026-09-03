package dev.phoenium.chunk;

import dev.phoenium.core.WorldId;
import dev.phoenium.topology.RoutingKey;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Pure, producer-agnostic chunk custody state.  It intentionally owns no Minecraft object,
 * future, executor, or lease.  The shared gate is used by the narrow admission model to
 * linearize lifecycle validation, lease reservation, and stage claim.
 */
public final class ChunkCustodyModel {
    public enum LifecycleState { LIVE, PENDING_UNLOAD, RETIRED }
    public enum WorkState { ACTIVE, RETIRED, CANCELLED }
    public enum StageState {
        READY, QUEUED, CLAIMED, APPLIED,
        SUPERSEDED, CANCELLED, FAILED_VANILLA, FAILED_BARRIER
    }
    public enum DeliveryDisposition { QUEUED, STALE_ROUTE, SUPERSEDED, CANCELLED, SHUTDOWN, DUPLICATE }

    /** Logical incarnation, never an identity hash or topology snapshot. */
    public record LifecycleId(String serverSession, long worldInstanceSequence, WorldId world,
                              RoutingKey chunkKey, long holderActivationSequence) {
        public LifecycleId {
            Objects.requireNonNull(serverSession, "serverSession");
            Objects.requireNonNull(world, "world");
            Objects.requireNonNull(chunkKey, "chunkKey");
            if (serverSession.isBlank() || worldInstanceSequence < 0 || holderActivationSequence < 0
                || !world.equals(chunkKey.world())) throw new IllegalArgumentException("invalid lifecycle identity");
        }
    }
    public record WorkId(LifecycleId lifecycle, long workSequence) {
        public WorkId { Objects.requireNonNull(lifecycle, "lifecycle"); if (workSequence < 0) throw new IllegalArgumentException("negative work sequence"); }
    }
    public record DemandId(WorkId work, long subscriptionSequence) {
        public DemandId { Objects.requireNonNull(work, "work"); if (subscriptionSequence < 0) throw new IllegalArgumentException("negative subscription sequence"); }
    }
    /** status/section/phase is an explicit sub-key, preventing collisions between valid sub-stages. */
    public record StageId(WorkId work, ChunkStageKind kind, String subKey, long stageSequence) {
        public StageId {
            Objects.requireNonNull(work, "work"); Objects.requireNonNull(kind, "kind"); Objects.requireNonNull(subKey, "subKey");
            if (subKey.isBlank() || stageSequence < 0) throw new IllegalArgumentException("invalid stage identity");
        }
    }

    private final ReentrantLock gate = new ReentrantLock();
    private final Map<LifecycleSlot, LifecycleRecord> lifecycles = new HashMap<>();
    private final Map<LifecycleId, LifecycleRecord> lifecycleById = new HashMap<>();
    private final Map<WorkId, WorkRecord> works = new HashMap<>();
    private final Map<DemandId, Boolean> demands = new HashMap<>();
    private final Map<StageId, StageState> stages = new HashMap<>();
    private final Map<StageId, DeliveryDisposition> dispositions = new HashMap<>();

    ReentrantLock gate() { return gate; }

    /** Holder reference equality is evidence: the same active holder may be rescued from pending unload. */
    public LifecycleId activate(String serverSession, long worldInstanceSequence, RoutingKey key, Object holderEvidence) {
        Objects.requireNonNull(holderEvidence, "holderEvidence");
        gate.lock(); try {
            LifecycleSlot slot = new LifecycleSlot(worldInstanceSequence, key);
            LifecycleRecord current = lifecycles.get(slot);
            if (current != null && current.state != LifecycleState.RETIRED && current.holderEvidence == holderEvidence) {
                current.state = LifecycleState.LIVE;
                return current.id;
            }
            if (current != null && current.state != LifecycleState.RETIRED) retireLocked(current);
            long activation = current == null ? 0 : current.id.holderActivationSequence() + 1;
            LifecycleId id = new LifecycleId(serverSession, worldInstanceSequence, key.world(), key, activation);
            LifecycleRecord created = new LifecycleRecord(id, holderEvidence);
            lifecycles.put(slot, created);
            lifecycleById.put(id, created);
            return id;
        } finally { gate.unlock(); }
    }

    public void pendingUnload(LifecycleId id) { gate.lock(); try { requireLifecycle(id).state = LifecycleState.PENDING_UNLOAD; } finally { gate.unlock(); } }
    public boolean rescue(LifecycleId id, Object holderEvidence) {
        gate.lock(); try {
            LifecycleRecord record = lifecycleById.get(id);
            if (record == null || record.state != LifecycleState.PENDING_UNLOAD || record.holderEvidence != holderEvidence) return false;
            record.state = LifecycleState.LIVE;
            return true;
        } finally { gate.unlock(); }
    }
    public void retire(LifecycleId id) { gate.lock(); try { retireLocked(requireLifecycle(id)); } finally { gate.unlock(); } }
    public Optional<LifecycleState> lifecycleState(LifecycleId id) { gate.lock(); try { return Optional.ofNullable(lifecycleById.get(id)).map(record -> record.state); } finally { gate.unlock(); } }

    public WorkId beginWork(LifecycleId lifecycle, long workSequence) {
        gate.lock(); try {
            requireLive(lifecycle);
            WorkId id = new WorkId(lifecycle, workSequence);
            WorkRecord existing = works.get(id);
            if (existing == null) works.put(id, new WorkRecord());
            return id;
        } finally { gate.unlock(); }
    }
    public void retireWork(WorkId id, boolean cancelled) {
        gate.lock(); try {
            WorkRecord work = requireWork(id);
            work.state = cancelled ? WorkState.CANCELLED : WorkState.RETIRED;
            for (Map.Entry<StageId, StageState> entry : stages.entrySet()) {
                if (entry.getKey().work().equals(id) && entry.getValue() != StageState.CLAIMED && entry.getValue() != StageState.APPLIED) {
                    entry.setValue(cancelled ? StageState.CANCELLED : StageState.SUPERSEDED);
                }
            }
        } finally { gate.unlock(); }
    }
    public Optional<WorkState> workState(WorkId id) { gate.lock(); try { return Optional.ofNullable(works.get(id)).map(record -> record.state); } finally { gate.unlock(); } }

    public DemandId subscribe(WorkId work, long subscriptionSequence) {
        gate.lock(); try { requireActiveWork(work); DemandId id = new DemandId(work, subscriptionSequence); demands.putIfAbsent(id, Boolean.TRUE); return id; } finally { gate.unlock(); }
    }
    /** A demand is independent from shared work; cancellation never implicitly retires the producer lineage. */
    public void cancelDemand(DemandId demand) { gate.lock(); try { if (!demands.containsKey(demand)) throw new IllegalStateException("unknown demand " + demand); demands.put(demand, Boolean.FALSE); } finally { gate.unlock(); } }
    public boolean demandActive(DemandId demand) { gate.lock(); try { return Boolean.TRUE.equals(demands.get(demand)); } finally { gate.unlock(); } }
    public boolean hasActiveDemand(WorkId work) { gate.lock(); try { return demands.entrySet().stream().anyMatch(entry -> entry.getKey().work().equals(work) && entry.getValue()); } finally { gate.unlock(); } }

    /** Records readiness before a delivery can be queued or claimed. Duplicate readiness is idempotent. */
    public void ready(StageId id) {
        gate.lock(); try {
            requireActiveWork(id.work());
            StageState current = stages.get(id);
            if (current == null) { stages.put(id, StageState.READY); return; }
            if (current != StageState.READY) throw new IllegalStateException("stage cannot become ready from " + current);
        } finally { gate.unlock(); }
    }
    /** Queue publication is a required predecessor of claim; it closes registration/completion loss races. */
    public boolean queue(StageId id) {
        gate.lock(); try {
            StageState current = stages.get(id);
            if (current == StageState.READY) { stages.put(id, StageState.QUEUED); dispositions.put(id, DeliveryDisposition.QUEUED); return true; }
            if (current == StageState.QUEUED || current == StageState.CLAIMED || current == StageState.APPLIED) { dispositions.put(id, DeliveryDisposition.DUPLICATE); return false; }
            return false;
        } finally { gate.unlock(); }
    }
    boolean claimLocked(StageId id) {
        StageState current = stages.get(id);
        if (current != StageState.QUEUED) return false;
        requireActiveWork(id.work());
        stages.put(id, StageState.CLAIMED);
        return true;
    }
    boolean isEligibleLocked(StageId id) {
        return stages.get(id) == StageState.QUEUED && activeWorkLocked(id.work());
    }
    void appliedLocked(StageId id) {
        if (stages.get(id) != StageState.CLAIMED) throw new IllegalStateException("application requires claimed stage " + id);
        stages.put(id, StageState.APPLIED);
    }
    void barrierLocked(StageId id) { stages.put(id, StageState.FAILED_BARRIER); }
    public void failVanilla(StageId id) {
        gate.lock(); try {
            StageState current = stages.get(id);
            if (current != StageState.READY && current != StageState.QUEUED) throw new IllegalStateException("vanilla failure after stage claim " + current);
            stages.put(id, StageState.FAILED_VANILLA);
        } finally { gate.unlock(); }
    }
    public Optional<StageState> stageState(StageId id) { gate.lock(); try { return Optional.ofNullable(stages.get(id)); } finally { gate.unlock(); } }
    public Optional<DeliveryDisposition> disposition(StageId id) { gate.lock(); try { return Optional.ofNullable(dispositions.get(id)); } finally { gate.unlock(); } }

    private void retireLocked(LifecycleRecord record) {
        record.state = LifecycleState.RETIRED;
        for (Map.Entry<WorkId, WorkRecord> entry : works.entrySet()) if (entry.getKey().lifecycle().equals(record.id)) retireWorkLocked(entry.getKey(), entry.getValue(), false);
    }
    private void retireWorkLocked(WorkId id, WorkRecord work, boolean cancelled) {
        work.state = cancelled ? WorkState.CANCELLED : WorkState.RETIRED;
        for (Map.Entry<StageId, StageState> entry : stages.entrySet()) if (entry.getKey().work().equals(id) && entry.getValue() != StageState.CLAIMED && entry.getValue() != StageState.APPLIED) entry.setValue(cancelled ? StageState.CANCELLED : StageState.SUPERSEDED);
    }
    private LifecycleRecord requireLifecycle(LifecycleId id) { LifecycleRecord record = lifecycleById.get(id); if (record == null) throw new IllegalStateException("unknown lifecycle " + id); return record; }
    private void requireLive(LifecycleId id) { if (requireLifecycle(id).state != LifecycleState.LIVE) throw new IllegalStateException("lifecycle is not live " + id); }
    private WorkRecord requireWork(WorkId id) { WorkRecord work = works.get(id); if (work == null) throw new IllegalStateException("unknown work " + id); return work; }
    private void requireActiveWork(WorkId id) { if (!activeWorkLocked(id)) throw new IllegalStateException("work is not active " + id); }
    private boolean activeWorkLocked(WorkId id) { WorkRecord work = works.get(id); LifecycleRecord lifecycle = lifecycleById.get(id.lifecycle()); return work != null && work.state == WorkState.ACTIVE && lifecycle != null && lifecycle.state == LifecycleState.LIVE; }
    private record LifecycleSlot(long worldInstanceSequence, RoutingKey key) { }
    private static final class LifecycleRecord { final LifecycleId id; final Object holderEvidence; LifecycleState state = LifecycleState.LIVE; LifecycleRecord(LifecycleId id, Object holderEvidence) { this.id = id; this.holderEvidence = holderEvidence; } }
    private static final class WorkRecord { WorkState state = WorkState.ACTIVE; }
}
