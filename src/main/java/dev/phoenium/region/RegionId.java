package dev.phoenium.region;

import dev.phoenium.core.WorldId;
import java.util.Objects;

public record RegionId(WorldId world, String value) implements Comparable<RegionId> {
    public RegionId {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) throw new IllegalArgumentException("region identity is blank");
    }
    @Override public int compareTo(RegionId other) {
        int worldOrder = world.value().compareTo(other.world.value());
        return worldOrder != 0 ? worldOrder : value.compareTo(other.value);
    }
}
