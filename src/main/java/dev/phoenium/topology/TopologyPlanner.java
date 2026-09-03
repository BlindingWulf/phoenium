package dev.phoenium.topology;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Deterministic, pure metadata planning for create/merge/split candidate components. */
public final class TopologyPlanner {
    public List<Set<RoutingKey>> mergePlan(Collection<RoutingKey> holders, int radius) {
        if (radius < 0) throw new IllegalArgumentException("negative radius");
        Set<RoutingKey> remaining = new java.util.TreeSet<>(holders);
        List<Set<RoutingKey>> components = new ArrayList<>();
        while (!remaining.isEmpty()) {
            RoutingKey root = remaining.iterator().next();
            Set<RoutingKey> component = new java.util.TreeSet<>();
            ArrayDeque<RoutingKey> queue = new ArrayDeque<>(); queue.add(root); remaining.remove(root);
            while (!queue.isEmpty()) {
                RoutingKey current = queue.removeFirst(); component.add(current);
                List<RoutingKey> neighbors = remaining.stream().filter(candidate -> adjacentWithin(current, candidate, radius)).sorted().toList();
                neighbors.forEach(candidate -> { remaining.remove(candidate); queue.addLast(candidate); });
            }
            components.add(Set.copyOf(component));
        }
        return components.stream().sorted(Comparator.comparing(component -> component.stream().min(RoutingKey::compareTo).orElseThrow())).toList();
    }
    /**
     * Static required closure: the union of the world-local Chebyshev balls of radius {@code radius}
     * around every active seed. Each ball lives entirely in the seed's own world; a key from another
     * world never substitutes for a missing neighbor. This is a coordinate domain, not a complete
     * MutationClosure: containment of every admitted operation's full footprint (broad-phase queries,
     * callbacks, cached positions) in the frozen region authority is a separate admission-time duty.
     */
    public Set<RoutingKey> requiredClosure(Collection<RoutingKey> seeds, int radius) {
        if (radius < 0) throw new IllegalArgumentException("negative radius");
        Set<RoutingKey> closure = new LinkedHashSet<>();
        for (RoutingKey seed : seeds) {
            for (int x = seed.x() - radius; x <= seed.x() + radius; x++) {
                for (int z = seed.z() - radius; z <= seed.z() + radius; z++) {
                    closure.add(new RoutingKey(seed.world(), x, z));
                }
            }
        }
        return Set.copyOf(closure);
    }

    /**
     * Buffer closure as containment: the required closure of the active seeds must be a subset of
     * the region's own authority. The region may own strictly more than the closure; exact equality
     * with a bounding square is neither required nor inferred. Membership is by exact RoutingKey
     * identity, so authority in another world can never satisfy a seed's own-world neighbors. A
     * PASS here is a static containment fact only; it does not prove that all movement or other
     * operations stay enclosed.
     */
    public boolean bufferClosure(Set<RoutingKey> activeSeeds, Set<RoutingKey> regionAuthority, int radius) {
        if (activeSeeds == null) throw new IllegalArgumentException("activeSeeds");
        if (regionAuthority == null) throw new IllegalArgumentException("regionAuthority");
        return regionAuthority.containsAll(requiredClosure(activeSeeds, radius));
    }
    public static boolean adjacentWithin(RoutingKey left, RoutingKey right, int radius) { return left.world().equals(right.world()) && chebyshev(left, right) <= radius; }
    private static long chebyshev(RoutingKey left, RoutingKey right) { return Math.max(Math.abs((long) left.x() - right.x()), Math.abs((long) left.z() - right.z())); }
}
