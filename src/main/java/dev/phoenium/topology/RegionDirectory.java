package dev.phoenium.topology;

import dev.phoenium.core.TopologyGeneration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** Atomic publication makes lookups immutable snapshots, never mutation authority. */
public final class RegionDirectory {
    private final AtomicReference<View> view = new AtomicReference<>(new View(new TopologyGeneration(0), Map.of()));
    public View snapshot() { return view.get(); }
    public Optional<RegionRoute> resolve(RoutingKey key) { return Optional.ofNullable(view.get().routes().get(key)); }
    public void publish(TopologyGeneration generation, Map<RoutingKey, RegionRoute> routes) {
        Objects.requireNonNull(generation, "generation");
        View previous = view.get();
        if (generation.value() <= previous.generation().value()) throw new IllegalStateException("directory generations must increase");
        routes.forEach((key, route) -> {
            if (!key.world().equals(route.region().world())) throw new IllegalArgumentException("route crosses world identity");
            if (!route.generation().equals(generation)) throw new IllegalArgumentException("route generation differs from view");
        });
        view.set(new View(generation, Map.copyOf(routes)));
    }
    public record View(TopologyGeneration generation, Map<RoutingKey, RegionRoute> routes) {
        public View { routes = Map.copyOf(routes); }
    }
}
