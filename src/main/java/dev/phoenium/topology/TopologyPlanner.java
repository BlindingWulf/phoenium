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
                List<RoutingKey> neighbors = remaining.stream().filter(candidate -> adjacent(current, candidate, radius)).sorted().toList();
                neighbors.forEach(candidate -> { remaining.remove(candidate); queue.addLast(candidate); });
            }
            components.add(Set.copyOf(component));
        }
        return components.stream().sorted(Comparator.comparing(component -> component.stream().min(RoutingKey::compareTo).orElseThrow())).toList();
    }
    public boolean bufferClosure(Set<RoutingKey> component, int radius) {
        return component.stream().allMatch(key -> component.stream().allMatch(other -> !key.world().equals(other.world()) || key.equals(other) || chebyshev(key, other) > radius || component.contains(other)));
    }
    private static boolean adjacent(RoutingKey left, RoutingKey right, int radius) { return left.world().equals(right.world()) && chebyshev(left, right) <= radius; }
    private static long chebyshev(RoutingKey left, RoutingKey right) { return Math.max(Math.abs((long) left.x() - right.x()), Math.abs((long) left.z() - right.z())); }
}
