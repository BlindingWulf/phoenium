package dev.phoenium.region;

import dev.phoenium.core.OwnerEpoch;
import dev.phoenium.ownership.RegionLeaseManager;
import dev.phoenium.task.RegionTaskEnvelope;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Mutable test state can be touched only through the scheduler's current lease. */
public final class RegionRuntime {
    private final RegionId id;
    private final RegionLeaseManager leases;
    private final java.util.concurrent.ConcurrentLinkedDeque<RegionTaskEnvelope> inbox = new java.util.concurrent.ConcurrentLinkedDeque<>();
    private final java.util.concurrent.ConcurrentLinkedDeque<RegionTaskEnvelope> transitionMailbox = new java.util.concurrent.ConcurrentLinkedDeque<>();
    private final List<String> operationLog = new ArrayList<>();
    private RegionLifecycle lifecycle = RegionLifecycle.TRANSIENT;
    private OwnerEpoch ownerEpoch;
    private boolean transitioning;
    private long accumulator;
    private long tickCount;

    public RegionRuntime(RegionId id, OwnerEpoch ownerEpoch) {
        this.id = Objects.requireNonNull(id, "id"); this.ownerEpoch = Objects.requireNonNull(ownerEpoch, "ownerEpoch");
        this.leases = new RegionLeaseManager(id);
    }
    public RegionId id() { return id; }
    public OwnerEpoch ownerEpoch() { return ownerEpoch; }
    public RegionLeaseManager leases() { return leases; }
    public RegionLifecycle lifecycle() { return lifecycle; }
    public boolean transitioning() { return transitioning; }
    public void transitionTo(RegionLifecycle next) {
        if (!lifecycle.permits(next)) throw new IllegalStateException("illegal lifecycle transition " + lifecycle + " -> " + next + " for " + id);
        lifecycle = next;
    }
    public void beginTransition() { if (lifecycle == RegionLifecycle.DEAD) throw new IllegalStateException("dead region transition"); transitioning = true; }
    public void finishTransition(OwnerEpoch nextEpoch) { ownerEpoch = nextEpoch; transitioning = false; }
    public void enqueue(RegionTaskEnvelope envelope) { inbox.addLast(envelope); }
    public void defer(RegionTaskEnvelope envelope) { transitionMailbox.addLast(envelope); }
    public Optional<RegionTaskEnvelope> poll() { return Optional.ofNullable(inbox.pollFirst()); }
    public Optional<RegionTaskEnvelope> pollTransitionMailbox() { return Optional.ofNullable(transitionMailbox.pollFirst()); }
    public int transitionMailboxSize() { return transitionMailbox.size(); }
    public int inboxSize() { return inbox.size(); }
    public void append(String value) { requireMutation(); operationLog.add(value); }
    public void add(long value) { requireMutation(); accumulator += value; operationLog.add("add:" + value); }
    public List<String> immutableLog() { return List.copyOf(operationLog); }
    public long accumulator() { return accumulator; }
    public void tick() { requireMutation(); tickCount++; operationLog.add("tick:" + tickCount); }
    public long tickCount() { return tickCount; }
    private void requireMutation() {
        dev.phoenium.ownership.OwnershipContext.current().ifPresentOrElse(
            active -> leases.requireCurrent(active.lease()),
            () -> { throw new IllegalStateException("unleased mutation attempt: " + id); });
    }
}
