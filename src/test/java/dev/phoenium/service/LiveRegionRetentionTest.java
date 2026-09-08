package dev.phoenium.service;

import dev.phoenium.core.OwnerEpoch;
import dev.phoenium.core.WorldId;
import dev.phoenium.ownership.OperationCategory;
import dev.phoenium.ownership.OwnershipContext;
import dev.phoenium.region.RegionId;
import dev.phoenium.region.RegionRuntime;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LiveRegionRetentionTest {
    @Test void finiteLiveTickDemandKeepsCountsAndRetainsNoCompletedTickHistory() {
        var live = new RegionRuntime(new RegionId(new WorldId("retention"), "live"), new OwnerEpoch(0), false);
        assertThrows(IllegalStateException.class, live::tick);
        try (var lease = live.leases().acquire(); var context = OwnershipContext.enter(
                live.id(), lease.epoch(), OperationCategory.TICK)) {
            for (int i = 0; i < 50_000; i++) live.tick();
        }
        assertEquals(50_000, live.tickCount());
        assertTrue(live.immutableLog().isEmpty());
        assertFalse(live.leases().hasActiveLease());
    }
}
