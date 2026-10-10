package dev.phoenium.ownership;

import static org.junit.jupiter.api.Assertions.*;

import dev.phoenium.core.WorldId;
import dev.phoenium.region.RegionId;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** S9-D14: the gate-free owner checks answer as the gated ones did through every lease transition. */
class RegionLeaseLockFreeReadsTest {
    private final RegionId region = new RegionId(new WorldId("minecraft:overworld"), "serial");
    private final RegionLeaseManager leases = new RegionLeaseManager(region);

    @Test
    void claimSelectionAndLeaseReadsFollowEveryTransition() {
        assertFalse(leases.hasClaimOrSelection()); assertFalse(leases.hasActiveLease());
        var selection = leases.trySelect().orElseThrow();
        assertTrue(leases.hasClaimOrSelection()); assertFalse(leases.hasActiveLease());
        RegionLease lease = selection.promote();
        assertTrue(leases.hasClaimOrSelection()); assertTrue(leases.hasActiveLease());
        lease.close();
        assertFalse(leases.hasClaimOrSelection()); assertFalse(leases.hasActiveLease());
        var unused = leases.trySelect().orElseThrow();
        unused.close();
        assertFalse(leases.hasClaimOrSelection());
    }

    @Test
    void releaseNoticesArePendingExactlyWhileRegistered() throws Exception {
        AtomicInteger fired = new AtomicInteger();
        assertFalse(leases.releaseNoticesPending());
        leases.whenReleased(fired::incrementAndGet).close();
        assertEquals(1, fired.get(), "with nothing held a notice fires at once and is never pending");
        RegionLease lease = leases.acquire();
        AutoCloseable cancelled = leases.whenReleased(fired::incrementAndGet);
        leases.whenReleased(fired::incrementAndGet);
        assertTrue(leases.releaseNoticesPending());
        cancelled.close();
        assertTrue(leases.releaseNoticesPending(), "one registered notice remains");
        lease.close();
        assertFalse(leases.releaseNoticesPending());
        assertEquals(2, fired.get(), "the cancelled notice never fires");
        var selection = leases.trySelect().orElseThrow();
        leases.whenReleased(fired::incrementAndGet);
        assertTrue(leases.releaseNoticesPending());
        selection.close();
        assertFalse(leases.releaseNoticesPending());
        assertEquals(3, fired.get());
    }

    @Test
    void ownerChecksRejectStaleForeignAndContextlessLeases() {
        RegionLease lease = leases.acquire();
        try (var owner = OwnershipContext.enter(region, lease.epoch(), OperationCategory.OWNER_TASK)) {
            lease.requireCurrent();
            leases.requireCurrent(lease.epoch());
            var stale = assertThrows(IllegalStateException.class, () -> leases.requireCurrent(new LeaseEpoch(lease.epoch().value() + 1)));
            assertTrue(stale.getMessage().startsWith("stale or absent lease"));
        }
        assertThrows(IllegalStateException.class, lease::requireCurrent, "a lease is checked against the current context");
        lease.close();
        var released = assertThrows(IllegalStateException.class, lease::requireCurrent);
        assertTrue(released.getMessage().startsWith("stale or foreign lease"));
        assertThrows(IllegalStateException.class, () -> leases.requireCurrent(lease.epoch()));
    }
}
