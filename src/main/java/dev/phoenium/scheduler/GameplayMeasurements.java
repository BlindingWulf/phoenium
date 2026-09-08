package dev.phoenium.scheduler;

import dev.phoenium.ownership.OwnershipContext;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Bounded observations only. Authority always comes from the selected current claim. */
public final class GameplayMeasurements {
    private static final int HISTORY_CAPACITY = 4096;
    private final boolean trace = Boolean.getBoolean("phoenium.gameplay.trace");
    private final ArrayDeque<BodyInterval> history = new ArrayDeque<>();
    private final LongAdder bodies = new LongAdder(), failedBodies = new LongAdder(), bodyNanos = new LongAdder();
    private final LongAdder claims = new LongAdder(), queueNanos = new LongAdder(), dispatchNanos = new LongAdder();
    private final AtomicLong maxQueueNanos = new AtomicLong();
    private final AtomicInteger activeBodies = new AtomicInteger(), maxBodies = new AtomicInteger();
    private final AtomicLong sequence = new AtomicLong();
    private final ThreadLocal<Claim> current = new ThreadLocal<>();

    Claim enter(long enqueuedAt, long selectedAt, long claimStart, long generation, long operation) {
        var active = OwnershipContext.current().orElseThrow();
        if (current.get() != null) throw new IllegalStateException("nested measured gameplay claim");
        Claim claim = new Claim(selectedAt, claimStart, generation, operation, active.region().toString(), active.lease().value());
        current.set(claim); claims.increment();
        long workerStart = System.nanoTime(); long delay = workerStart - enqueuedAt;
        queueNanos.add(delay); maxQueueNanos.accumulateAndGet(delay, Math::max);
        dispatchNanos.add(workerStart - selectedAt);
        return claim;
    }

    /** Surrounds the original native virtual tick invocation, not a queued placeholder. */
    public void originalBody(String identity, Runnable original) {
        Claim claim = current.get();
        if (claim == null) throw new IllegalStateException("original local body has no measured current claim");
        var active = OwnershipContext.current().orElseThrow();
        if (!active.region().toString().equals(claim.region) || active.lease().value() != claim.lease)
            throw new IllegalStateException("original local body changed its claim");
        int simultaneous = activeBodies.incrementAndGet(); maxBodies.accumulateAndGet(simultaneous, Math::max);
        long start = System.nanoTime(); boolean completed = false;
        try { original.run(); completed = true; }
        finally {
            long end = System.nanoTime(); activeBodies.decrementAndGet(); bodyNanos.add(end - start);
            bodies.increment(); if (!completed) failedBodies.increment();
            if (trace) {
                if (claim.intervals.size() == 256) throw new IllegalStateException("original body trace exceeds cohort capacity");
                claim.intervals.add(new BodyInterval(sequence.incrementAndGet(), identity, claim.region, claim.lease,
                    claim.generation, claim.operation, Thread.currentThread().threadId(), claim.selectedAt,
                    claim.start, start, end, 0, completed));
            }
        }
    }

    void leaveBody() { current.remove(); }
    void released(Claim claim) {
        if (claim == null || !trace) return;
        long end = System.nanoTime();
        synchronized (history) {
            for (BodyInterval interval : claim.intervals) {
                if (history.size() == HISTORY_CAPACITY) history.removeFirst();
                history.addLast(new BodyInterval(interval.sequence, interval.identity, interval.region, interval.lease,
                    interval.generation, interval.operation, interval.thread, interval.selectedAt, interval.claimStart,
                    interval.bodyStart, interval.bodyEnd, end, interval.completed));
            }
        }
    }
    public Snapshot snapshot() {
        synchronized (history) {
            return new Snapshot(bodies.sum(), failedBodies.sum(), bodyNanos.sum(), claims.sum(), queueNanos.sum(), dispatchNanos.sum(), maxQueueNanos.get(),
                activeBodies.get(), maxBodies.get(), HISTORY_CAPACITY, List.copyOf(history));
        }
    }
    static final class Claim {
        final long selectedAt, start, generation, operation, lease;
        final String region;
        final java.util.ArrayList<BodyInterval> intervals = new java.util.ArrayList<>();
        Claim(long selectedAt, long start, long generation, long operation, String region, long lease) {
            this.selectedAt = selectedAt; this.start = start; this.generation = generation;
            this.operation = operation; this.region = region; this.lease = lease;
        }
    }
    public record Snapshot(long bodies, long failedBodies, long bodyNanos, long claims, long queueNanos, long dispatchNanos, long maxQueueNanos,
                           int activeBodies, int maxSimultaneousBodies, int intervalCapacity, List<BodyInterval> intervals) { }
    public record BodyInterval(long sequence, String identity, String region, long lease, long generation, long operation,
                               long thread, long selectedAt, long claimStart, long bodyStart, long bodyEnd, long claimEnd,
                               boolean completed) { }
}
