package dev.phoenium.service;

import static org.junit.jupiter.api.Assertions.*;

import dev.phoenium.core.WorldId;
import dev.phoenium.region.RegionId;
import dev.phoenium.topology.RoutingKey;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class RegionalTrackingPartitionsTest {
    private final WorldId world = new WorldId("tracking");
    private final RegionId a = new RegionId(world, "a");
    private final RegionId b = new RegionId(world, "b");
    private final RegionId c = new RegionId(world, "c");
    private final RoutingKey aKey = new RoutingKey(world, 0, 0);
    private final RoutingKey bKey = new RoutingKey(world, 10, 0);
    private final RoutingKey cKey = new RoutingKey(world, 200, 0);

    @Test
    void sourceOrderAndExactTrackerIdentitySurviveReconciliation() {
        var index = new RegionalTrackingPartitions<String>();
        Object trackerOne = new Object(), trackerTwo = new Object();
        index.replaceAll(List.of(item(7, trackerOne, "one", aKey, a), item(3, trackerTwo, "two", bKey, b)));
        assertEquals(List.of("one", "two"), index.values());
        assertEquals(2, index.partitionCount());

        index.replaceAll(List.of(item(3, trackerTwo, "two-retained", bKey, b), item(7, trackerOne, "one-retained", aKey, a)));
        assertEquals(List.of("two-retained", "one-retained"), index.values(),
            "the actual source iteration order is authoritative on every reconciliation");
        index.reorder(List.of(new RegionalTrackingPartitions.Identity(7, trackerOne),
            new RegionalTrackingPartitions.Identity(3, trackerTwo)));
        assertEquals(List.of("one-retained", "two-retained"), index.values(),
            "an exact structural source suffix republishes the source map's current iteration order");
        assertThrows(IllegalStateException.class, () -> index.replaceAll(List.of(
            item(3, new Object(), "replacement", bKey, b), item(7, trackerOne, "one", aKey, a))));
        assertEquals(List.of("one-retained", "two-retained"), index.values(),
            "a rejected identity replacement must leave the retained source intact");
    }

    @Test
    void reorderKeepsRecordsAndValidatesEvenWhenTheOrderIsUnchanged() {
        var index = new RegionalTrackingPartitions<String>();
        Object one = new Object(), two = new Object(), three = new Object();
        index.replaceAll(List.of(item(1, one, "one", aKey, a), item(2, two, "two", bKey, b), item(3, three, "three", cKey, c)));
        List<String> before = index.values();
        // S9-D13: the retained order again changes nothing, not even the cached values list
        index.reorder(List.of(new RegionalTrackingPartitions.Identity(1, one), new RegionalTrackingPartitions.Identity(2, two),
            new RegionalTrackingPartitions.Identity(3, three)));
        assertSame(before, index.values());
        assertThrows(IllegalStateException.class, () -> index.reorder(List.of(new RegionalTrackingPartitions.Identity(1, one),
            new RegionalTrackingPartitions.Identity(2, new Object()), new RegionalTrackingPartitions.Identity(3, three))));
        index.reorder(List.of(new RegionalTrackingPartitions.Identity(3, three), new RegionalTrackingPartitions.Identity(1, one),
            new RegionalTrackingPartitions.Identity(2, two)));
        assertEquals(List.of("three", "one", "two"), index.values());
        assertEquals(3, index.partitionCount());
        assertEquals("two", index.valueByIdentity(two));
        index.update(2, aKey, a);
        assertEquals(2, index.partitionCount(), "a reorder left the partitions exact for later updates");
        assertEquals("two", index.remove(2, two));
        assertEquals(List.of("three", "one"), index.values());
        assertNull(index.valueByIdentity(two));
    }

    @Test
    void migrationMovesCompleteRecordsAndKeepsUnrelatedOwnerDistinct() {
        var index = new RegionalTrackingPartitions<String>();
        Object first = new Object(), second = new Object(), far = new Object();
        index.replaceAll(List.of(item(1, first, "first", aKey, a), item(2, second, "second", bKey, b),
            item(3, far, "far", cKey, c)));
        var plan = index.plan(9, key -> key == aKey || key == bKey ? b : c);

        assertEquals(1, index.install(plan));
        assertEquals(List.of("first", "second", "far"), index.values());
        assertEquals(2, index.partitionCount(),
            "a finite recipient closure must not collapse an unrelated far owner");
        assertEquals(0, index.install(plan), "installing the exact complete ownership plan is idempotent");
    }

    @Test
    void incompleteMigrationAndWrongRemovalPreserveAllRecords() {
        var index = new RegionalTrackingPartitions<String>();
        Object first = new Object(), second = new Object();
        index.replaceAll(List.of(item(1, first, "first", aKey, a), item(2, second, "second", bKey, b)));

        var incomplete = new RegionalTrackingPartitions.Plan(2, Map.of(1, b));
        assertThrows(IllegalStateException.class, () -> index.install(incomplete));
        assertThrows(IllegalStateException.class, () -> index.remove(1, new Object()));
        assertEquals(List.of("first", "second"), index.values());
        assertEquals(2, index.partitionCount());

        assertEquals("first", index.remove(1, first));
        assertEquals(List.of("second"), index.values());
        assertNull(index.remove(999, new Object()));
    }

    @Test
    void identityLookupMatchesSourceOrderScanThroughEveryMutation() {
        var index = new RegionalTrackingPartitions<String>();
        Object one = new Object(), two = new Object(), three = new Object();
        index.replaceAll(List.of(item(1, one, "one", aKey, a), item(2, two, "two", bKey, b)));
        assertEquals("one", index.valueByIdentity(one));
        assertEquals("two", index.valueByIdentity(two));
        assertNull(index.valueByIdentity(three));
        index.put(item(3, three, "three", cKey, c));
        assertEquals("three", index.valueByIdentity(three));
        index.update(3, aKey, a);
        assertEquals("three", index.valueByIdentity(three), "an owner/key update keeps the same retained record");
        index.reorder(List.of(new RegionalTrackingPartitions.Identity(3, three),
            new RegionalTrackingPartitions.Identity(1, one), new RegionalTrackingPartitions.Identity(2, two)));
        assertEquals("one", index.valueByIdentity(one));
        index.install(index.plan(4, key -> c));
        assertEquals("two", index.valueByIdentity(two));
        assertEquals("one", index.remove(1, one));
        assertNull(index.valueByIdentity(one), "a removed tracker is no longer retained");
        assertThrows(IllegalStateException.class, () -> index.remove(2, one));
        assertEquals("two", index.valueByIdentity(two), "a rejected removal leaves the identity index intact");
    }

    @Test
    void conservativeRecipientChunksAreFiniteAndDoNotIncludeFarPlayers() {
        RoutingKey source = new RoutingKey(world, 10, 10);
        assertTrue(RegionalTrackingService.candidateChunk(source, 64, new RoutingKey(world, 15, 10)));
        assertTrue(RegionalTrackingService.candidateChunk(source, 64, new RoutingKey(world, 10, 5)));
        assertFalse(RegionalTrackingService.candidateChunk(source, 64, new RoutingKey(world, 16, 10)));
        assertFalse(RegionalTrackingService.candidateChunk(source, 64, new RoutingKey(world, 10, 200)));
    }

    private static RegionalTrackingPartitions.Item<String> item(int id, Object identity, String value,
                                                                 RoutingKey key, RegionId owner) {
        return new RegionalTrackingPartitions.Item<>(id, identity, value, key, owner);
    }
}
