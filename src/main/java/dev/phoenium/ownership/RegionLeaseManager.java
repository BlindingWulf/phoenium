package dev.phoenium.ownership;

import dev.phoenium.region.RegionId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

/** Owner-local lease accounting; the serial scheduler is the only Stage 1 caller. */
public final class RegionLeaseManager {
    private final RegionId region;
    private long nextEpoch;
    private RegionLease active;
    private final ReentrantLock metadataGate = new ReentrantLock();
    private final Set<ReleaseNotice> releaseNotices = new LinkedHashSet<>();

    public RegionLeaseManager(RegionId region) { this.region = region; }
    public RegionLease acquire() {
        metadataGate.lock(); try {
            if (active != null) throw new IllegalStateException("region already has a mutation lease: " + region);
            active = new RegionLease(this, region, new LeaseEpoch(++nextEpoch));
            return active;
        } finally { metadataGate.unlock(); }
    }
    void requireCurrent(RegionLease lease) {
        metadataGate.lock(); try {
            if (active != lease) throw new IllegalStateException("stale or foreign lease: " + lease.region());
            OwnershipContext.require(region, lease.epoch());
        } finally { metadataGate.unlock(); }
    }
    public void requireCurrent(LeaseEpoch epoch) {
        metadataGate.lock(); try {
            if (active == null || !active.epoch().equals(epoch)) throw new IllegalStateException("stale or absent lease for " + region);
            OwnershipContext.require(region, epoch);
        } finally { metadataGate.unlock(); }
    }
    List<ReleaseNotice> release(RegionLease lease) {
        metadataGate.lock(); try {
            if (active != lease) throw new IllegalStateException("stale or foreign lease release: " + lease.region());
            var notices = List.copyOf(releaseNotices);
            releaseNotices.clear();
            active = null;
            return notices;
        } finally { metadataGate.unlock(); }
    }
    /** One metadata-only notification for the currently active lease. It cannot run a
     * waiting owner body. A waiting operation must cancel its subscription on retirement. */
    public AutoCloseable whenReleased(Runnable notification) {
        Objects.requireNonNull(notification);
        metadataGate.lock(); try {
            if (active != null) {
                var notice = new ReleaseNotice(notification);
                releaseNotices.add(notice);
                return notice;
            }
        } finally { metadataGate.unlock(); }
        notification.run(); return () -> { };
    }
    final class ReleaseNotice implements AutoCloseable {
        private Runnable notification;
        private ReleaseNotice(Runnable notification) { this.notification = notification; }
        private void fire() {
            final Runnable current;
            metadataGate.lock(); try { current = notification; notification = null; }
            finally { metadataGate.unlock(); }
            if (current != null) current.run();
        }
        @Override public void close() {
            metadataGate.lock(); try { releaseNotices.remove(this); notification = null; }
            finally { metadataGate.unlock(); }
        }
    }
    static void notifyReleased(List<ReleaseNotice> notices) {
        Throwable failure = null;
        for (var notice : notices) try { notice.fire(); }
        catch (Throwable problem) {
            if (failure == null) failure = problem;
            else if (failure != problem) failure.addSuppressed(problem);
        }
        if (failure instanceof RuntimeException problem) throw problem;
        if (failure instanceof Error problem) throw problem;
        if (failure != null) throw new IllegalStateException("regional lease release notification failed", failure);
    }
    public boolean hasActiveLease() {
        metadataGate.lock(); try { return active != null; } finally { metadataGate.unlock(); }
    }
    public LeaseEpoch currentEpoch() {
        metadataGate.lock(); try { return active == null ? new LeaseEpoch(nextEpoch) : active.epoch(); }
        finally { metadataGate.unlock(); }
    }
}
