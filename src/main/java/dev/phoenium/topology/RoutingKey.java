package dev.phoenium.topology;

import dev.phoenium.core.WorldId;
import java.util.Objects;

/** Generic isolated holder coordinate; no Minecraft chunk object is carried by the core. */
public record RoutingKey(WorldId world, int x, int z) implements Comparable<RoutingKey> {
    public RoutingKey { Objects.requireNonNull(world, "world"); }
    // S9-D13: the record's own hashCode is 31*(31*world + x) + z, so a chunk grid wider than 31 keys repeats whole
    // hash values ((x, z) and (x + 1, z - 31) collide) and large key maps degrade into treeified bins. Equality is
    // the record's (same components); only the hash mixes x and z.
    @Override public boolean equals(Object other) {
        return this == other || other instanceof RoutingKey key && x == key.x && z == key.z && world.equals(key.world);
    }
    @Override public int hashCode() {
        long mixed = (((long) x << 32) | (z & 0xFFFFFFFFL)) * 0x9E3779B97F4A7C15L;
        return (int) (mixed ^ (mixed >>> 32)) ^ world.hashCode();
    }
    @Override public int compareTo(RoutingKey other) {
        int worldOrder = world.value().compareTo(other.world.value());
        if (worldOrder != 0) return worldOrder;
        int xOrder = Integer.compare(x, other.x);
        return xOrder != 0 ? xOrder : Integer.compare(z, other.z);
    }
}
