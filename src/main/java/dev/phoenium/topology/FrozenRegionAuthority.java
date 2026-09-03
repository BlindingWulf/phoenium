package dev.phoenium.topology;

import dev.phoenium.core.TopologyGeneration;
import dev.phoenium.core.WorldId;
import dev.phoenium.region.RegionId;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A region's frozen mutable authority at one published directory generation: exactly the chunk
 * coordinates the current view explicitly routes to this region. Freeze it at admission time and
 * check an operation's complete footprint against it before the first foreign lookup or position
 * store. An escaping footprint must fail closed via {@link ClosureEscapeException}: it must never
 * be clamped, replayed, reinterpreted as a relocation, or satisfied by a destination-only check.
 */
public record FrozenRegionAuthority(RegionId region, TopologyGeneration generation, Set<RoutingKey> domain) {
    public FrozenRegionAuthority {
        domain = Set.copyOf(domain);
    }
    public static FrozenRegionAuthority freeze(RegionDirectory directory, RegionId region) {
        RegionDirectory.View view = directory.snapshot();
        Set<RoutingKey> domain = new LinkedHashSet<>();
        for (java.util.Map.Entry<RoutingKey, RegionRoute> entry : view.routes().entrySet()) {
            if (entry.getValue().region().equals(region)) domain.add(entry.getKey());
        }
        return new FrozenRegionAuthority(region, view.generation(), domain);
    }
    /** Checks the complete footprint, not only the resolved destination. */
    public void requireWithin(Collection<RoutingKey> footprint) {
        if (footprint == null) throw new IllegalArgumentException("footprint");
        if (domain.containsAll(footprint)) return;
        Set<RoutingKey> missing = new LinkedHashSet<>(footprint);
        missing.removeAll(domain);
        throw new ClosureEscapeException("footprint escapes frozen authority of " + region.value()
            + " at generation " + generation.value() + ": " + missing);
    }
    /**
     * Exact containment of an axis-aligned Chebyshev footprint box in one world, without
     * enumerating the box when it is already provably too large: a box with more cells than
     * the frozen domain holds cannot be contained by it (pigeonhole), so a huge finite delta
     * fails immediately and deterministically. Otherwise every cell is checked by exact
     * RoutingKey identity. The box is never clamped, sampled, or approximated by its center
     * or endpoint.
     */
    public void requireWithinBox(WorldId world, long x0, long x1, long z0, long z1) {
        if (world == null) throw new IllegalArgumentException("world");
        if (x1 < x0 || z1 < z0) throw new IllegalArgumentException("inverted footprint box");
        if (!representable(x0) || !representable(x1) || !representable(z0) || !representable(z1)) {
            throw new ClosureEscapeException("footprint box " + box(x0, x1, z0, z1)
                + " leaves the representable routing domain of " + region.value()
                + " at generation " + generation.value());
        }
        long sideX = x1 - x0 + 1;
        long sideZ = z1 - z0 + 1;
        long area = sideX > Long.MAX_VALUE / sideZ ? Long.MAX_VALUE : sideX * sideZ;
        if (area > domain.size()) {
            throw new ClosureEscapeException("footprint box " + box(x0, x1, z0, z1) + " in " + world.value()
                + " spans " + area + " cells but the frozen authority of " + region.value()
                + " at generation " + generation.value() + " holds only " + domain.size() + " keys");
        }
        for (long x = x0; x <= x1; x++) {
            for (long z = z0; z <= z1; z++) {
                RoutingKey cell = new RoutingKey(world, (int) x, (int) z);
                if (!domain.contains(cell)) {
                    throw new ClosureEscapeException("footprint box " + box(x0, x1, z0, z1) + " in " + world.value()
                        + " escapes frozen authority of " + region.value()
                        + " at generation " + generation.value() + ": " + Set.of(cell));
                }
            }
        }
    }
    private static boolean representable(long coordinate) { return coordinate >= Integer.MIN_VALUE && coordinate <= Integer.MAX_VALUE; }
    private static String box(long x0, long x1, long z0, long z1) { return "x[" + x0 + ".." + x1 + "] z[" + z0 + ".." + z1 + "]"; }
}
