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
    private final boolean recordOperationLog;
    private volatile RegionLifecycle lifecycle = RegionLifecycle.TRANSIENT;
    private volatile OwnerEpoch ownerEpoch;
    private volatile boolean transitioning;
    private long accumulator;
    private long tickCount;

    public RegionRuntime(RegionId id, OwnerEpoch ownerEpoch) {
        this(id, ownerEpoch, true);
    }
    /** The deterministic model harness retains its trace. A live runtime keeps counters only;
     * ordinary world ticks must not accumulate an unbounded historical string list. */
    public RegionRuntime(RegionId id, OwnerEpoch ownerEpoch, boolean recordOperationLog) {
        this.id = Objects.requireNonNull(id, "id"); this.ownerEpoch = Objects.requireNonNull(ownerEpoch, "ownerEpoch");
        this.recordOperationLog = recordOperationLog;
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
    public void beginTransition() {
        if (lifecycle == RegionLifecycle.DEAD) throw new IllegalStateException("dead region transition");
        leases.closeAdmission(); transitioning = true;
    }
    public void finishTransition(OwnerEpoch nextEpoch) {
        ownerEpoch = nextEpoch; transitioning = false; leases.openAdmission();
    }
    public Optional<RegionTaskEnvelope> peek() { return Optional.ofNullable(inbox.peekFirst()); }
    /** S9-D16: envelopes in any runtime's inbox, counted before an envelope becomes visible and uncounted after its
     * removal, so it is never below the true total: zero means every inbox is empty. */
    private static final java.util.concurrent.atomic.AtomicLong QUEUED = new java.util.concurrent.atomic.AtomicLong();
    public static boolean noneQueued() { return QUEUED.get() == 0; }
    /** The queued count (diagnostics: a count that never returns to zero leaves inboxesEmpty on its walk). */
    public static long queued() { return QUEUED.get(); }
    public void enqueue(RegionTaskEnvelope envelope) { QUEUED.incrementAndGet(); inbox.addLast(envelope); }
    public void requeueFirst(RegionTaskEnvelope envelope) { QUEUED.incrementAndGet(); inbox.addFirst(envelope); }
    public void defer(RegionTaskEnvelope envelope) { transitionMailbox.addLast(envelope); }
    public void deferFirst(RegionTaskEnvelope envelope) { transitionMailbox.addFirst(envelope); }
    public Optional<RegionTaskEnvelope> poll() {
        RegionTaskEnvelope polled = inbox.pollFirst();
        if (polled != null) QUEUED.decrementAndGet();
        return Optional.ofNullable(polled);
    }
    public Optional<RegionTaskEnvelope> pollTransitionMailbox() { return Optional.ofNullable(transitionMailbox.pollFirst()); }
    public int transitionMailboxSize() { return transitionMailbox.size(); }
    public int inboxSize() { return inbox.size(); }
    /** S9-D10: {@code inboxSize() == 0} without ConcurrentLinkedDeque's O(n) size walk. */
    public boolean inboxEmpty() { return inbox.isEmpty(); }
    public void append(String value) { requireMutation(); if (recordOperationLog) operationLog.add(value); }
    public void add(long value) { requireMutation(); accumulator += value; if (recordOperationLog) operationLog.add("add:" + value); }
    public List<String> immutableLog() { return List.copyOf(operationLog); }
    public long accumulator() { return accumulator; }
    public void tick() { requireMutation(); tickCount++; if (recordOperationLog) operationLog.add("tick:" + tickCount); }
    public long tickCount() { return tickCount; }
    private void requireMutation() {
        dev.phoenium.ownership.OwnershipContext.current().ifPresentOrElse(
            active -> leases.requireCurrent(active.lease()),
            () -> { throw new IllegalStateException("unleased mutation attempt: " + id); });
    }
}
