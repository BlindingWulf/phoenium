package dev.phoenium.topology;

import dev.phoenium.core.TopologyGeneration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** Atomic publication makes lookups immutable snapshots, never mutation authority. */
public final class RegionDirectory {
    private final AtomicReference<View> view = new AtomicReference<>(new View(new TopologyGeneration(0), Map.of(), Map.of()));
    public View snapshot() { return view.get(); }
    public Optional<RegionRoute> resolve(RoutingKey key) {
        View current = view.get();
        RegionRoute explicit = current.routes().get(key);
        if (explicit != null) return Optional.of(explicit);
        if (current.hasExplicitRoutesForWorld(key.world())) {
            // Explicit-topology world: a missing explicit route fails closed. A world default is
            // never hidden mutation authority once the world carries concrete routes.
            return Optional.empty();
        }
        return Optional.ofNullable(current.worldDefaults().get(key.world()));
    }
    public void publish(TopologyGeneration generation, Map<RoutingKey, RegionRoute> routes) {
        publish(generation, routes, Map.of());
    }
    /** A world default represents a deliberate temporary whole-world region, never an implicit thread fallback. */
    public void publish(TopologyGeneration generation, Map<RoutingKey, RegionRoute> routes, Map<dev.phoenium.core.WorldId, RegionRoute> worldDefaults) {
        Objects.requireNonNull(generation, "generation");
        View previous = view.get();
        if (generation.value() <= previous.generation().value()) throw new IllegalStateException("directory generations must increase");
        routes.forEach((key, route) -> {
            if (!key.world().equals(route.region().world())) throw new IllegalArgumentException("route crosses world identity");
            if (!route.generation().equals(generation)) throw new IllegalArgumentException("route generation differs from view");
        });
        worldDefaults.forEach((world, route) -> {
            if (!world.equals(route.region().world())) throw new IllegalArgumentException("default route crosses world identity");
            if (!route.generation().equals(generation)) throw new IllegalArgumentException("default route generation differs from view");
        });
        view.set(new View(generation, Map.copyOf(routes), Map.copyOf(worldDefaults)));
    }
    public record View(TopologyGeneration generation, Map<RoutingKey, RegionRoute> routes, Map<dev.phoenium.core.WorldId, RegionRoute> worldDefaults) {
        public View { routes = Map.copyOf(routes); worldDefaults = Map.copyOf(worldDefaults); }
        /** True when the world carries at least one explicit route, which disables default fallback for it. */
        public boolean hasExplicitRoutesForWorld(dev.phoenium.core.WorldId world) {
            for (RoutingKey routeKey : routes.keySet()) {
                if (routeKey.world().equals(world)) return true;
            }
            return false;
        }
    }
}
