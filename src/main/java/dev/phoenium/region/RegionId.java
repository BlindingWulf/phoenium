package dev.phoenium.region;

import dev.phoenium.core.WorldId;
import java.util.Objects;

public record RegionId(WorldId world, String value) implements Comparable<RegionId> {
    public RegionId {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) throw new IllegalArgumentException("region identity is blank");
    }
    // S9-D13: the record's equality and hash (31 * world + value), written out for the hot owner checks.
    @Override public boolean equals(Object other) {
        return this == other || other instanceof RegionId region && value.equals(region.value) && world.equals(region.world);
    }
    @Override public int hashCode() { return 31 * world.hashCode() + value.hashCode(); }
    @Override public int compareTo(RegionId other) {
        int worldOrder = world.value().compareTo(other.world.value());
        return worldOrder != 0 ? worldOrder : value.compareTo(other.value);
    }
}
