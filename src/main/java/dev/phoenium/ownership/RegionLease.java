package dev.phoenium.ownership;

import dev.phoenium.region.RegionId;

public final class RegionLease implements AutoCloseable {
    private final RegionLeaseManager manager;
    private final RegionId region;
    private final LeaseEpoch epoch;
    private boolean released;

    RegionLease(RegionLeaseManager manager, RegionId region, LeaseEpoch epoch) {
        this.manager = manager; this.region = region; this.epoch = epoch;
    }
    public RegionId region() { return region; }
    public LeaseEpoch epoch() { return epoch; }
    public void requireCurrent() { manager.requireCurrent(this); }
    @Override public void close() {
        if (released) throw new IllegalStateException("lease released twice: " + region + " / " + epoch);
        var notices = manager.release(this);
        released = true;
        RegionLeaseManager.notifyReleased(notices);
    }
}
