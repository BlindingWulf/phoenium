package dev.phoenium.topology;

import dev.phoenium.core.WorldId;
import java.util.Objects;

/** Generic isolated holder coordinate; no Minecraft chunk object is carried by the core. */
public record RoutingKey(WorldId world, int x, int z) implements Comparable<RoutingKey> {
    public RoutingKey { Objects.requireNonNull(world, "world"); }
    @Override public int compareTo(RoutingKey other) {
        int worldOrder = world.value().compareTo(other.world.value());
        if (worldOrder != 0) return worldOrder;
        int xOrder = Integer.compare(x, other.x);
        return xOrder != 0 ? xOrder : Integer.compare(z, other.z);
    }
}
