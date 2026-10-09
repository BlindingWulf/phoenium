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
    private final Object publicationMetadata = new Object();
    private volatile int retainedSaveCuts;
    private final java.util.concurrent.CopyOnWriteArraySet<Runnable> observations = new java.util.concurrent.CopyOnWriteArraySet<>();
    /** Change notices contain no route authority; listeners must reobserve the published view. */
    public AutoCloseable observeChanges(Runnable listener) { observations.add(listener); return () -> observations.remove(listener); }
    private void changed() {
        Throwable first = null;
        for (Runnable observer : observations) {
            try { observer.run(); }
            catch (Throwable failure) {
                if (first == null) first = failure;
                else if (first != failure) first.addSuppressed(failure);
            }
        }
        if (first instanceof RuntimeException runtime) throw runtime;
        if (first instanceof Error error) throw error;
    }

    /** A save reservation retains the published topology, not an active region lease.
     * Acquire only after original save readiness has settled, before the first snapshot.
     * The metadata monitor is released before any callback, snapshot or storage work. */
    public SaveCut tryRetainSaveCut() {
        if (dev.phoenium.ownership.OwnershipContext.current().isPresent())
            throw new IllegalStateException("save topology cut requested inside regional ownership");
        synchronized (publicationMetadata) {
            if (pending.get() != null) return null;
            retainedSaveCuts = Math.incrementExact(retainedSaveCuts);
            return new SaveCut(view.get());
        }
    }
    public boolean hasRetainedSaveCuts() { return retainedSaveCuts != 0; }
    public final class SaveCut implements AutoCloseable {
        private final View captured;
        private boolean closed;
        private SaveCut(View captured) { this.captured = captured; }
        public View view() {
            if (closed) throw new IllegalStateException("expired save topology cut");
            return captured;
        }
        @Override public void close() {
            synchronized (publicationMetadata) {
                if (closed) return;
                closed = true;
                retainedSaveCuts--;
            }
            changed();
        }
    }

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
        PendingChange change;
        synchronized (publicationMetadata) {
        if (retainedSaveCuts != 0) return null;
        View before = view.get();
        java.util.Set<RoutingKey> affected = new java.util.HashSet<>(keys);
        java.util.Set<dev.phoenium.core.WorldId> defaultWorlds = new java.util.HashSet<>();
        before.routes().forEach((key, route) -> { if (participants.contains(route.region())) affected.add(key); });
        before.worldDefaults().forEach((world, route) -> { if (participants.contains(route.region())) defaultWorlds.add(world); });
        change = new PendingChange(affected, defaultWorlds);
        if (!pending.compareAndSet(null, change)) throw new IllegalStateException("directory change already pending");
        }
        return change;
    }
    /** Coordinator dispatches this only after every participant has closed admission and its own gate is released. */
    void signalChangeStarted(PendingChange change) {
        if (pending.get() != change) throw new IllegalStateException("wrong directory change start signal");
        changed();
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
        publishForTransition(generation, routes, worldDefaults);
        changed();
    }
    /** Atomic transition publication without observer dispatch; the coordinator signals after releasing its gate. */
    View publishForTransition(TopologyGeneration generation, Map<RoutingKey, RegionRoute> routes, Map<dev.phoenium.core.WorldId, RegionRoute> worldDefaults) {
        Objects.requireNonNull(generation, "generation");
        routes.forEach((key, route) -> {
            if (!key.world().equals(route.region().world())) throw new IllegalArgumentException("route crosses world identity");
            if (!route.generation().equals(generation)) throw new IllegalArgumentException("route generation differs from view");
        });
        worldDefaults.forEach((world, route) -> {
            if (!world.equals(route.region().world())) throw new IllegalArgumentException("default route crosses world identity");
            if (!route.generation().equals(generation)) throw new IllegalArgumentException("default route generation differs from view");
        });
        View replacement = new View(generation, Map.copyOf(routes), Map.copyOf(worldDefaults));
        synchronized (publicationMetadata) {
            if (retainedSaveCuts != 0) throw new IllegalStateException("topology publication overlaps a retained save cut");
            if (generation.value() <= view.get().generation().value()) throw new IllegalStateException("directory generations must increase");
            view.set(replacement);
        }
        return replacement;
    }
    void signalPublication(View replacement) {
        if (view.get() != replacement) throw new IllegalStateException("wrong topology publication signal");
        changed();
    }
    public record View(TopologyGeneration generation, Map<RoutingKey, RegionRoute> routes,
                       Map<dev.phoenium.core.WorldId, RegionRoute> worldDefaults,
                       java.util.Set<dev.phoenium.core.WorldId> explicitRouteWorlds) {
        public View(TopologyGeneration generation, Map<RoutingKey, RegionRoute> routes,
                    Map<dev.phoenium.core.WorldId, RegionRoute> worldDefaults) {
            this(generation, routes, worldDefaults, null);
        }
        /** S9-D6: explicitRouteWorlds is always derived from routes (any supplied value is ignored), so the
         * per-resolve scan of every route key becomes one set lookup. */
        public View {
            routes = Map.copyOf(routes); worldDefaults = Map.copyOf(worldDefaults);
            java.util.Set<dev.phoenium.core.WorldId> worlds = new java.util.HashSet<>();
            for (RoutingKey routeKey : routes.keySet()) worlds.add(routeKey.world());
            explicitRouteWorlds = java.util.Set.copyOf(worlds);
        }
        /** True when the world carries at least one explicit route, which disables default fallback for it. */
        public boolean hasExplicitRoutesForWorld(dev.phoenium.core.WorldId world) {
            return world != null && explicitRouteWorlds.contains(world);
        }
    }
}
