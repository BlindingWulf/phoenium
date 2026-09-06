package dev.phoenium.chunk;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Associates actual vanilla work reservations with one shared WorkId.  A demand subscription is
 * deliberately a separate record, so repeated get/future callers do not manufacture work.
 */
public final class ChunkWorkRegistry {
    private final ChunkCustodyModel custody;
    /** Small work-lineage metadata gate only; no chunk or region mutation occurs under it. */
    private final ReentrantLock metadataGate;
    private final Map<WorkSlot, ChunkCustodyModel.WorkId> workByReservation = new HashMap<>();
    private final Map<ChunkCustodyModel.LifecycleId, Long> nextWorkSequence = new HashMap<>();
    private final Map<ChunkCustodyModel.WorkId, Long> nextDemandSequence = new HashMap<>();
    private final java.util.Set<ChunkCustodyModel.WorkId> registered = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public ChunkWorkRegistry(ChunkCustodyModel custody) { this.custody = Objects.requireNonNull(custody, "custody"); metadataGate = custody.gate(); }

    ChunkCustodyModel custody() { return custody; }

    /**
     * reservation is a real holder/status-work reservation (status slot, promotion slot, etc.),
     * not a public getChunkFuture demand.
     */
    public ChunkCustodyModel.WorkId beginOrResolveSharedWork(ChunkCustodyModel.LifecycleId lifecycle, WorkReservation reservation) {
        Objects.requireNonNull(lifecycle, "lifecycle"); Objects.requireNonNull(reservation, "reservation");
        metadataGate.lock(); try {
            WorkSlot slot = new WorkSlot(lifecycle, reservation);
            ChunkCustodyModel.WorkId existing = workByReservation.get(slot);
            if (existing != null) return existing;
            long sequence = nextWorkSequence.merge(lifecycle, 1L, Long::sum) - 1L;
            ChunkCustodyModel.WorkId work = custody.beginWork(lifecycle, sequence);
            workByReservation.put(slot, work);
            registered.add(work);
            return work;
        } finally { metadataGate.unlock(); }
    }

    public ChunkCustodyModel.DemandId addDemand(ChunkCustodyModel.WorkId work) {
        metadataGate.lock(); try {
            long sequence = nextDemandSequence.merge(work, 1L, Long::sum) - 1L;
            return custody.subscribe(work, sequence);
        } finally { metadataGate.unlock(); }
    }
    public void removeDemand(ChunkCustodyModel.DemandId demand) { custody.cancelDemand(Objects.requireNonNull(demand, "demand")); }
    public void retireWork(ChunkCustodyModel.WorkId work, boolean cancelled) { custody.retireWork(Objects.requireNonNull(work, "work"), cancelled); }
    public boolean isCurrentWork(ChunkCustodyModel.WorkId work) { return custody.workState(work).filter(state -> state == ChunkCustodyModel.WorkState.ACTIVE).isPresent(); }
    boolean hasReservation(ChunkCustodyModel.WorkId work) { return registered.contains(work); }
    boolean hasRetainedWork(ChunkCustodyModel.LifecycleId lifecycle) { return registered.stream().anyMatch(work -> work.lifecycle().equals(lifecycle)); }

    void releaseRetired(ChunkCustodyModel.LifecycleId id) {
        metadataGate.lock(); try {
            if (!custody.canReleaseRetiredLifecycle(id)) throw new ChunkProtocolException("work cleanup before terminal acknowledgment");
            workByReservation.keySet().removeIf(slot -> slot.lifecycle().equals(id));
            nextWorkSequence.remove(id);
            nextDemandSequence.keySet().removeIf(work -> work.lifecycle().equals(id));
            registered.removeIf(work -> work.lifecycle().equals(id));
        } finally { metadataGate.unlock(); }
    }
    public void releaseRetiredWork(ChunkCustodyModel.WorkId work) {
        Objects.requireNonNull(work, "work");
        metadataGate.lock(); try {
            if (custody.workState(work).filter(state -> state != ChunkCustodyModel.WorkState.ACTIVE).isEmpty())
                throw new ChunkProtocolException("active work cannot be reclaimed");
            workByReservation.values().removeIf(work::equals);
            nextDemandSequence.remove(work);
            registered.remove(work);
            custody.releaseRetiredWork(work);
        } finally { metadataGate.unlock(); }
    }
    public int retainedEntries() {
        metadataGate.lock(); try { return workByReservation.size() + nextWorkSequence.size() + nextDemandSequence.size() + registered.size(); }
        finally { metadataGate.unlock(); }
    }

    /** A stable, typed vanilla reservation lineage; callers must not use a demand count here. */
    public record WorkReservation(String family, String slot, long reservationSequence) {
        public WorkReservation {
            Objects.requireNonNull(family, "family"); Objects.requireNonNull(slot, "slot");
            if (family.isBlank() || slot.isBlank() || reservationSequence < 0) throw new IllegalArgumentException("invalid work reservation");
        }
    }
    private record WorkSlot(ChunkCustodyModel.LifecycleId lifecycle, WorkReservation reservation) { }
}
