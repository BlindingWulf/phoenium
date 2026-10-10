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
    // S9-D14: every write is under the metadata gate; the owner checks read these without it (a held gate only
    // ordered a single read, which a volatile read is). The one two-field change, promote(), writes active before
    // clearing selection, so a reader of selection then active never sees neither.
    private volatile RegionLease active;
    private boolean admissionClosed;
    private volatile Selection selection;
    private final ReentrantLock metadataGate = new ReentrantLock();
    private final Set<ReleaseNotice> releaseNotices = new LinkedHashSet<>();
    /** releaseNotices.size(), republished under the gate after every change. */
    private volatile int releaseNoticeCount;

    public RegionLeaseManager(RegionId region) { this.region = region; }
    public RegionLease acquire() {
        metadataGate.lock(); try {
            if (admissionClosed) throw new IllegalStateException("region claim admission is closed: " + region);
            if (active != null || selection != null) throw new IllegalStateException("region already has a mutation lease or selected delivery: " + region);
            active = new RegionLease(this, region, new LeaseEpoch(++nextEpoch));
            return active;
        } finally { metadataGate.unlock(); }
    }
    /** Selection and topology closure share this metadata gate. An unavailable peer is
     * skipped, never waited on; a selected claim stays live through its original body. */
    public java.util.Optional<RegionLease> tryAcquire() {
        metadataGate.lock(); try {
            if (admissionClosed || active != null || selection != null) return java.util.Optional.empty();
            active = new RegionLease(this, region, new LeaseEpoch(++nextEpoch));
            return java.util.Optional.of(active);
        } finally { metadataGate.unlock(); }
    }
    /** A delivery reservation fences topology without granting gameplay authority. In
     * particular, terminal packet rejection must never acquire a mutation lease. */
    public java.util.Optional<Selection> trySelect() {
        metadataGate.lock(); try {
            if (admissionClosed || active != null || selection != null) return java.util.Optional.empty();
            selection = new Selection(); return java.util.Optional.of(selection);
        } finally { metadataGate.unlock(); }
    }
    public final class Selection implements AutoCloseable {
        private boolean closed;
        private Selection() { }
        public RegionLease promote() {
            metadataGate.lock(); try {
                if (closed || selection != this || active != null) throw new IllegalStateException("expired regional delivery selection");
                active = new RegionLease(RegionLeaseManager.this, region, new LeaseEpoch(++nextEpoch));
                selection = null; closed = true; return active;
            } finally { metadataGate.unlock(); }
        }
        @Override public void close() {
            List<ReleaseNotice> notices;
            metadataGate.lock(); try {
                if (closed) return;
                if (selection != this) throw new IllegalStateException("foreign regional delivery selection");
                selection = null; closed = true;
                notices = List.copyOf(releaseNotices); releaseNotices.clear(); releaseNoticeCount = 0;
            } finally { metadataGate.unlock(); }
            notifyReleased(notices);
        }
    }
    /** S9-D11: a release notice is waiting for the active lease (or selection) to end. */
    public boolean releaseNoticesPending() { return releaseNoticeCount != 0; }
    public boolean hasClaimOrSelection() {
        // S9-D14: selection first; see the field order note.
        return selection != null || active != null;
    }
    public void closeAdmission() {
        metadataGate.lock(); try { admissionClosed = true; } finally { metadataGate.unlock(); }
    }
    public void openAdmission() {
        metadataGate.lock(); try {
            if (active != null || selection != null) throw new IllegalStateException("region reopened with a live claim or selection: " + region);
            admissionClosed = false;
        } finally { metadataGate.unlock(); }
    }
    void requireCurrent(RegionLease lease) {
        if (active != lease) throw new IllegalStateException("stale or foreign lease: " + lease.region());
        OwnershipContext.require(region, lease.epoch());
    }
    public void requireCurrent(LeaseEpoch epoch) {
        RegionLease current = active;
        if (current == null || !current.epoch().equals(epoch)) throw new IllegalStateException("stale or absent lease for " + region);
        OwnershipContext.require(region, epoch);
    }
    List<ReleaseNotice> release(RegionLease lease) {
        metadataGate.lock(); try {
            if (active != lease) throw new IllegalStateException("stale or foreign lease release: " + lease.region());
            var notices = List.copyOf(releaseNotices);
            releaseNotices.clear(); releaseNoticeCount = 0;
            active = null;
            return notices;
        } finally { metadataGate.unlock(); }
    }
    /** One metadata-only notification for the currently active lease. It cannot run a
     * waiting owner body. A waiting operation must cancel its subscription on retirement. */
    public AutoCloseable whenReleased(Runnable notification) {
        Objects.requireNonNull(notification);
        metadataGate.lock(); try {
            if (active != null || selection != null) {
                var notice = new ReleaseNotice(notification);
                releaseNotices.add(notice); releaseNoticeCount = releaseNotices.size();
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
            metadataGate.lock(); try { releaseNotices.remove(this); releaseNoticeCount = releaseNotices.size(); notification = null; }
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
    public boolean hasActiveLease() { return active != null; }
    public LeaseEpoch currentEpoch() {
        metadataGate.lock(); try { return active == null ? new LeaseEpoch(nextEpoch) : active.epoch(); }
        finally { metadataGate.unlock(); }
    }
}
