package dev.phoenium.topology;

import static org.junit.jupiter.api.Assertions.*;

import dev.phoenium.core.WorldId;
import dev.phoenium.region.RegionId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** S9-D13/D14: the written-out identities keep the records' equality; the routing key's hash spreads a chunk grid. */
class RoutingIdentityTest {
    private final WorldId overworld = new WorldId("minecraft:overworld");
    private final WorldId nether = new WorldId("minecraft:the_nether");

    @Test
    void equalityIsComponentEqualityAndHashIsConsistent() {
        RoutingKey a = new RoutingKey(overworld, 12, -40), same = new RoutingKey(new WorldId("minecraft:overworld"), 12, -40);
        assertEquals(a, same); assertEquals(a.hashCode(), same.hashCode());
        assertNotEquals(a, new RoutingKey(nether, 12, -40));
        assertNotEquals(a, new RoutingKey(overworld, 13, -40));
        assertNotEquals(a, new RoutingKey(overworld, 12, -41));
        assertNotEquals(a, null); assertNotEquals(a, "minecraft:overworld");
        assertEquals(new WorldId("minecraft:overworld"), overworld);
        assertEquals("minecraft:overworld".hashCode(), overworld.hashCode(), "a world's hash stays its value's");
        RegionId region = new RegionId(overworld, "serial");
        assertEquals(new RegionId(new WorldId("minecraft:overworld"), "serial"), region);
        assertNotEquals(new RegionId(nether, "serial"), region);
        assertEquals(31 * overworld.hashCode() + "serial".hashCode(), region.hashCode(), "a region's hash stays the record's");
        assertEquals("RoutingKey[world=WorldId[value=minecraft:overworld], x=12, z=-40]", a.toString());
        assertTrue(a.compareTo(new RoutingKey(overworld, 12, -39)) < 0);
    }

    @Test
    void aWideChunkGridHasDistinctHashesWhereTheRecordHashRepeated() {
        Map<Integer, Integer> perHash = new HashMap<>();
        var keys = new HashSet<RoutingKey>();
        for (int x = -150; x < 150; x++) for (int z = -150; z < 150; z++) {
            RoutingKey key = new RoutingKey(overworld, x, z);
            keys.add(key);
            perHash.merge(key.hashCode(), 1, Integer::sum);
        }
        assertEquals(90_000, keys.size());
        int worst = perHash.values().stream().mapToInt(Integer::intValue).max().orElseThrow();
        assertTrue(worst <= 2, "the record hash 31*(31*w+x)+z repeated a value up to 10 times here; worst=" + worst);
        for (int x = -150; x < 150; x++) for (int z = -150; z < 150; z++) assertTrue(keys.contains(new RoutingKey(overworld, x, z)));
    }
}
