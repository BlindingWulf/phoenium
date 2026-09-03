package dev.phoenium.ownership;

import dev.phoenium.region.RegionId;

/** Owner-local lease accounting; the serial scheduler is the only Stage 1 caller. */
public final class RegionLeaseManager {
    private final RegionId region;
    private long nextEpoch;
    private RegionLease active;

    public RegionLeaseManager(RegionId region) { this.region = region; }
    public RegionLease acquire() {
        if (active != null) throw new IllegalStateException("region already has a mutation lease: " + region);
        active = new RegionLease(this, region, new LeaseEpoch(++nextEpoch));
        return active;
    }
    void requireCurrent(RegionLease lease) {
        if (active != lease) throw new IllegalStateException("stale or foreign lease: " + lease.region());
        OwnershipContext.require(region, lease.epoch());
    }
    public void requireCurrent(LeaseEpoch epoch) {
        if (active == null || !active.epoch().equals(epoch)) throw new IllegalStateException("stale or absent lease for " + region);
        OwnershipContext.require(region, epoch);
    }
    void release(RegionLease lease) {
        if (active != lease) throw new IllegalStateException("stale or foreign lease release: " + lease.region());
        active = null;
    }
    public boolean hasActiveLease() { return active != null; }
    public LeaseEpoch currentEpoch() { return active == null ? new LeaseEpoch(nextEpoch) : active.epoch(); }
}
