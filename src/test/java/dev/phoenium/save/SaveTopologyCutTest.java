package dev.phoenium.save;

import dev.phoenium.core.*;
import dev.phoenium.region.RegionId;
import dev.phoenium.topology.*;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SaveTopologyCutTest {
    @Test void retainedSaveViewCannotBeReplacedUntilEverySaveAcknowledgesItsCut() {
        var directory = new RegionDirectory();
        var world = new WorldId("save-cut"); var key = new RoutingKey(world, 0, 0);
        var owner = new RegionId(world, "owner"); var first = new TopologyGeneration(1);
        directory.publish(first, Map.of(key, new RegionRoute(owner, first, new OwnerEpoch(0))));
        var a = directory.tryRetainSaveCut(); var b = directory.tryRetainSaveCut();
        assertNotNull(a); assertNotNull(b); assertSame(a.view(), b.view());
        var next = first.next(); var routes = Map.of(key, new RegionRoute(owner, next, new OwnerEpoch(1)));
        assertThrows(IllegalStateException.class, () -> directory.publish(next, routes));
        a.close(); a.close(); assertTrue(directory.hasRetainedSaveCuts());
        assertThrows(IllegalStateException.class, () -> directory.publish(next, routes));
        assertEquals(first, b.view().generation()); b.close();
        assertFalse(directory.hasRetainedSaveCuts()); directory.publish(next, routes);
        assertEquals(next, directory.snapshot().generation());
        assertThrows(IllegalStateException.class, b::view);
    }

    @Test void failingReleaseNoticeCannotSkipLaterObserversOrRetainTheCompletedCut() throws Exception {
        var directory = new RegionDirectory(); var cut = directory.tryRetainSaveCut();
        var field = RegionDirectory.class.getDeclaredField("publicationMetadata"); field.setAccessible(true);
        Object metadata = field.get(directory); var calls = new AtomicInteger();
        var first = new IllegalArgumentException("first notice"); var second = new IllegalStateException("second notice");
        directory.observeChanges(() -> { assertFalse(Thread.holdsLock(metadata)); throw first; });
        directory.observeChanges(() -> { calls.incrementAndGet(); assertFalse(directory.hasRetainedSaveCuts()); throw second; });
        assertSame(first, assertThrows(IllegalArgumentException.class, cut::close));
        assertArrayEquals(new Throwable[]{second}, first.getSuppressed());
        assertEquals(1, calls.get()); assertFalse(directory.hasRetainedSaveCuts());
        cut.close(); assertEquals(1, calls.get());
    }

    @Test void aSaveCutCannotBeOpenedByAnActiveRegionalMutation() {
        var directory = new RegionDirectory(); var id = new RegionId(new WorldId("save-cut"), "owner");
        var manager = new dev.phoenium.ownership.RegionLeaseManager(id);
        try (var lease = manager.acquire(); var owner = dev.phoenium.ownership.OwnershipContext.enter(
                id, lease.epoch(), dev.phoenium.ownership.OperationCategory.OWNER_TASK)) {
            assertThrows(IllegalStateException.class, directory::tryRetainSaveCut);
        }
        assertFalse(directory.hasRetainedSaveCuts());
    }
}
