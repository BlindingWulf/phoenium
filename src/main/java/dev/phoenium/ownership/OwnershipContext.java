package dev.phoenium.ownership;

import dev.phoenium.region.RegionId;
import java.util.Objects;
import java.util.Optional;

/** Context is evidence supplied by an active lease; a thread name is never authority. */
public final class OwnershipContext {
    /** S9-D7: closed scopes store null rather than remove() the entry, here and in Phoenium's other plain
     * ThreadLocal scopes. get() answers null either way, but a removed entry makes every following get() miss
     * its slot, walk the probe chain and re-insert, and remove() itself rehashes the run. */
    private static final ThreadLocal<Active> CURRENT = new dev.phoenium.core.FastThreadLocal<>();
    private OwnershipContext() {}

    public static Scope enter(RegionId region, LeaseEpoch lease, OperationCategory category) {
        Objects.requireNonNull(region, "region");
        if (CURRENT.get() != null) throw new IllegalStateException("nested ownership context is forbidden");
        Active active = new Active(region, lease, category, Thread.currentThread().threadId());
        CURRENT.set(active);
        return new Scope(active);
    }

    public static Optional<Active> current() { return Optional.ofNullable(CURRENT.get()); }

    public static void require(RegionId region, LeaseEpoch lease) {
        Active active = CURRENT.get();
        if (active == null || !active.region.equals(region) || !active.lease.equals(lease)) {
            throw new IllegalStateException("mutation requires active owner lease for " + region + " / " + lease);
        }
    }

    public record Active(RegionId region, LeaseEpoch lease, OperationCategory category, long workerThreadId) {}

    public static final class Scope implements AutoCloseable {
        private final Active active;
        private boolean closed;
        private Scope(Active active) { this.active = active; }
        @Override public void close() {
            if (closed) throw new IllegalStateException("ownership context closed twice");
            if (CURRENT.get() != active) throw new IllegalStateException("ownership context escaped its worker");
            CURRENT.set(null);
            closed = true;
        }
    }
}
