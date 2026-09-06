package dev.phoenium.topology;

import dev.phoenium.core.TopologyGeneration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** Atomic publication makes lookups immutable snapshots, never mutation authority. */
public final class RegionDirectory {
    private final AtomicReference<View> view = new AtomicReference<>(new View(new TopologyGeneration(0), Map.of(), Map.of()));
    private final AtomicReference<PendingChange> pending = new AtomicReference<>();
    private final java.util.concurrent.CopyOnWriteArraySet<Runnable> observations = new java.util.concurrent.CopyOnWriteArraySet<>();
    /** Change notices contain no route authority; listeners must reobserve the published view. */
    public AutoCloseable observeChanges(Runnable listener) { observations.add(listener); return () -> observations.remove(listener); }
    private void changed() { observations.forEach(Runnable::run); }

    /** Evidence of a real coordinator proposal, never authority to execute in its future route. */
    public static final class PendingChange {
        private final java.util.Set<RoutingKey> keys;
        private final java.util.Set<dev.phoenium.core.WorldId> defaultWorlds;
        private volatile boolean completed;
        private PendingChange(java.util.Set<RoutingKey> keys, java.util.Set<dev.phoenium.core.WorldId> defaultWorlds) {
            this.keys = java.util.Set.copyOf(keys); this.defaultWorlds = java.util.Set.copyOf(defaultWorlds);
        }
        public boolean completed() { return completed; }
    }

    PendingChange beginChange(java.util.Set<RoutingKey> keys, java.util.Set<dev.phoenium.region.RegionId> participants) {
        View before = view.get();
        java.util.Set<RoutingKey> affected = new java.util.HashSet<>(keys);
        java.util.Set<dev.phoenium.core.WorldId> defaultWorlds = new java.util.HashSet<>();
        before.routes().forEach((key, route) -> { if (participants.contains(route.region())) affected.add(key); });
        before.worldDefaults().forEach((world, route) -> { if (participants.contains(route.region())) defaultWorlds.add(world); });
        PendingChange change = new PendingChange(affected, defaultWorlds);
        if (!pending.compareAndSet(null, change)) throw new IllegalStateException("directory change already pending");
        changed();
        return change;
    }

    /** Detach after reconciliation, while the coordinator still excludes a new proposal. */
    void finishChange(PendingChange change) {
        if (!pending.compareAndSet(change, null)) throw new IllegalStateException("wrong directory change");
    }

    void signalChange(PendingChange change) { change.completed = true; changed(); }

    public Optional<PendingChange> pendingChange(RoutingKey key) {
        PendingChange change = pending.get();
        return change != null && (change.keys.contains(key) || change.defaultWorlds.contains(key.world()))
                ? Optional.of(change) : Optional.empty();
    }
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
        changed();
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
