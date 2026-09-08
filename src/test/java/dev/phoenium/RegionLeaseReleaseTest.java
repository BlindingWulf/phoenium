package dev.phoenium;

import dev.phoenium.core.WorldId;
import dev.phoenium.ownership.RegionLeaseManager;
import dev.phoenium.region.RegionId;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RegionLeaseReleaseTest {
    @Test void aCancelledOrCompletedNoticeCannotObserveALaterLease() throws Exception {
        var manager = manager(); var calls = new AtomicInteger();
        var metadata = metadata(manager);
        var first = manager.acquire();
        var cancelled = manager.whenReleased(() -> fail("retired waiter must be detached"));
        var delivered = manager.whenReleased(() -> {
            assertFalse(manager.hasActiveLease());
            assertFalse(metadata.isHeldByCurrentThread(), "release notifications are outside lease metadata");
            calls.incrementAndGet();
        });
        cancelled.close(); first.close();
        assertEquals(1, calls.get());
        var second = manager.acquire();
        manager.whenReleased(calls::incrementAndGet);
        cancelled.close(); delivered.close();
        assertEquals(1, calls.get()); second.close(); assertEquals(2, calls.get());
    }

    @Test void notificationFailuresCannotRetainTheLeaseOrSkipOtherWaitingOperations() throws Exception {
        var manager = manager(); var calls = new AtomicInteger();
        var first = new IllegalStateException("first waiter"); var second = new IllegalArgumentException("second waiter");
        var lease = manager.acquire();
        manager.whenReleased(() -> { throw first; });
        manager.whenReleased(() -> { calls.incrementAndGet(); throw second; });
        manager.whenReleased(calls::incrementAndGet);
        assertSame(first, assertThrows(IllegalStateException.class, lease::close));
        assertArrayEquals(new Throwable[]{second}, first.getSuppressed());
        assertEquals(2, calls.get()); assertFalse(manager.hasActiveLease());
        assertTrue(assertThrows(IllegalStateException.class, lease::close).getMessage().contains("released twice"),
                "the first close committed before its notification failed");
        try (var next = manager.acquire()) { assertEquals(2, calls.get()); }
    }

    @Test void anAlreadyFreeLeaseNotifiesImmediatelyWithoutHoldingMetadata() throws Exception {
        var manager = manager(); var calls = new AtomicInteger();
        var metadata = metadata(manager);
        try (var notice = manager.whenReleased(() -> {
            assertFalse(metadata.isHeldByCurrentThread()); calls.incrementAndGet();
        })) { assertEquals(1, calls.get()); }
        try (var next = manager.acquire()) { }
        assertEquals(1, calls.get());
    }

    private static RegionLeaseManager manager() {
        return new RegionLeaseManager(new RegionId(new WorldId("lease-release-test"), "region"));
    }
    private static java.util.concurrent.locks.ReentrantLock metadata(RegionLeaseManager manager) throws Exception {
        var field = RegionLeaseManager.class.getDeclaredField("metadataGate"); field.setAccessible(true);
        return (java.util.concurrent.locks.ReentrantLock) field.get(manager);
    }
}
