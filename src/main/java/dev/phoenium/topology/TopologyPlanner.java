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
    /** A component is a closed buffer when it is exactly the full radius buffer square: a single world, spans of 2*radius+1 in both axes, and every point of that square present. */
    public boolean bufferClosure(Set<RoutingKey> component, int radius) {
        if (radius < 0) throw new IllegalArgumentException("negative radius");
        if (component.isEmpty()) return false;
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        dev.phoenium.core.WorldId world = null;
        for (RoutingKey key : component) {
            if (world == null) world = key.world();
            if (!world.equals(key.world())) return false;
            minX = Math.min(minX, key.x()); maxX = Math.max(maxX, key.x());
            minZ = Math.min(minZ, key.z()); maxZ = Math.max(maxZ, key.z());
        }
        if (maxX - minX != 2 * radius || maxZ - minZ != 2 * radius) return false;
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                if (!component.contains(new RoutingKey(world, x, z))) return false;
            }
        }
        return true;
    }
    public static boolean adjacentWithin(RoutingKey left, RoutingKey right, int radius) { return left.world().equals(right.world()) && chebyshev(left, right) <= radius; }
    private static long chebyshev(RoutingKey left, RoutingKey right) { return Math.max(Math.abs((long) left.x() - right.x()), Math.abs((long) left.z() - right.z())); }
}
