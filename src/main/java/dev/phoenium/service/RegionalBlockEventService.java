package dev.phoenium.service;

import dev.phoenium.core.WorldId;
import dev.phoenium.ownership.OwnershipContext;
import dev.phoenium.region.RegionId;
import dev.phoenium.topology.RegionDirectory;
import dev.phoenium.topology.RegionRoute;
import dev.phoenium.topology.RoutingKey;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

/**
 * Region-owned block-event state with one global insertion sequence used only to reproduce the
 * pinned {@code ObjectLinkedOpenHashSet} dispatch order across serial owners. Nested enqueue joins
 * the same cycle; ineligible events move to the tail after all newly enqueued events.
 */
public final class RegionalBlockEventService<E> {
    private final WorldId world;
    private final RegionDirectory directory;
    private final RegionalServiceDispatcher dispatcher;
    private final Function<E, BlockPos> position;
    private final Map<RegionId, ArrayDeque<Event<E>>> partitions = new LinkedHashMap<>();
    private final TreeMap<Long, Event<E>> ordered = new TreeMap<>();
    private final Set<E> deduplicated = new HashSet<>();
    private long nextSequence;
    private long cycles;
    private long enqueued;
    private long executed;
    private long deferred;
    private long migrated;
    private boolean cycling;

    public RegionalBlockEventService(WorldId world, RegionDirectory directory,
                                     RegionalServiceDispatcher dispatcher,
                                     Function<E, BlockPos> position) {
        this.world = Objects.requireNonNull(world, "world");
        this.directory = Objects.requireNonNull(directory, "directory");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.position = Objects.requireNonNull(position, "position");
    }

    /** Enqueue is a synchronous mutation and must originate in the event position's active owner. */
    public synchronized boolean enqueue(E event) {
        Objects.requireNonNull(event, "event");
        RoutingKey key = key(event);
        dispatcher.requireCurrentClosure(key, 1);
        return enqueueOwned(event, route(key).region());
    }

    /** Initial world-load binding at an owner-free boundary, before service admission opens. */
    public synchronized void bootstrap(Iterable<E> initial) {
        if (!ordered.isEmpty()) throw new IllegalStateException("block event service already bootstrapped");
        for (E event : initial) enqueueOwned(event, route(key(event)).region());
    }

    public void runCycle(Predicate<E> eligible, Consumer<E> originalEffect) {
        Objects.requireNonNull(eligible, "eligible");
        Objects.requireNonNull(originalEffect, "originalEffect");
        if (OwnershipContext.current().isPresent())
            throw new IllegalStateException("regional block-event cycle entered with owner authority");
        List<RoutingKey> initialClosures;
        synchronized (this) {
            if (cycling) throw new IllegalStateException("nested regional block-event cycle");
            initialClosures = ordered.values().stream().map(Event::key).distinct().toList();
        }
        for (RoutingKey key : initialClosures) dispatcher.ensureClosure(key, 1);
        synchronized (this) {
            if (cycling) throw new IllegalStateException("regional block-event cycle began during closure preflight");
            cycling = true;
            cycles++;
        }
        List<Event<E>> retry = new ArrayList<>();
        try {
            while (true) {
                Event<E> selected;
                synchronized (this) {
                    if (ordered.isEmpty()) break;
                    selected = ordered.pollFirstEntry().getValue();
                }
                dispatcher.executeAndDrain(selected.key(), RegionalServiceKind.BLOCK_EVENT, execution -> {
                    // ObjectLinkedOpenHashSet.removeFirst removes the dedup entry before the
                    // pinned eligibility test, so an equal event may be enqueued meanwhile.
                    synchronized (this) {
                        removeFromOwner(selected, execution.runtime().id());
                        if (!deduplicated.remove(selected.event()))
                            throw new IllegalStateException("block event lost its dedup entry");
                    }
                    if (!eligible.test(selected.event())) {
                        synchronized (this) { retry.add(selected); deferred++; }
                        return;
                    }
                    originalEffect.accept(selected.event());
                    synchronized (this) { executed++; }
                });
            }
            for (Event<E> event : retry) {
                RoutingKey currentKey = key(event.event());
                dispatcher.executeAndDrain(currentKey, RegionalServiceKind.BLOCK_EVENT, execution -> {
                    synchronized (this) {
                        RegionRoute current = route(currentKey);
                        if (!current.region().equals(execution.runtime().id()))
                            throw new IllegalStateException("deferred block event reached stale owner");
                        requeueDeferred(event.event(), currentKey, current.region());
                    }
                });
            }
        } finally {
            synchronized (this) { cycling = false; }
        }
    }

    public synchronized MigrationPlan planMigration(RegionDirectory.View published) {
        if (OwnershipContext.current().isPresent() || cycling)
            throw new IllegalStateException("block event migration is not owner-free");
        Map<Long, RegionId> owners = new LinkedHashMap<>();
        ordered.forEach((sequence, event) -> owners.put(sequence, resolve(published, event.key()).region()));
        return new MigrationPlan(published.generation().value(), Map.copyOf(owners));
    }

    public synchronized void installMigration(MigrationPlan plan, RegionDirectory.View published) {
        if (plan.generation() != published.generation().value())
            throw new IllegalStateException("block event migration generation changed");
        if (!plan.owners().keySet().equals(ordered.keySet()))
            throw new IllegalStateException("block event migration pending set changed");
        Map<RegionId, ArrayDeque<Event<E>>> next = new LinkedHashMap<>();
        for (var entry : ordered.entrySet()) {
            Event<E> event = entry.getValue();
            RegionId owner = Objects.requireNonNull(plan.owners().get(entry.getKey()));
            if (!event.owner().equals(owner)) { event.owner = owner; migrated++; }
            next.computeIfAbsent(owner, ignored -> new ArrayDeque<>()).addLast(event);
        }
        partitions.clear();
        partitions.putAll(next);
    }

    public synchronized Measurements measurements() {
        return new Measurements(partitions.size(), ordered.size(), enqueued, executed, deferred, migrated, cycles, cycling);
    }

    private boolean enqueueOwned(E event, RegionId owner) {
        if (!deduplicated.add(event)) return false;
        long sequence = nextSequence = Math.incrementExact(nextSequence);
        Event<E> retained = new Event<>(sequence, key(event), owner, event);
        ordered.put(sequence, retained);
        partitions.computeIfAbsent(owner, ignored -> new ArrayDeque<>()).addLast(retained);
        enqueued++;
        return true;
    }
    private void requeueDeferred(E event, RoutingKey key, RegionId owner) {
        if (!deduplicated.add(event))
            throw new IllegalStateException("deferred block event collided after cycle drain");
        long sequence = nextSequence = Math.incrementExact(nextSequence);
        Event<E> retained = new Event<>(sequence, key, owner, event);
        ordered.put(sequence, retained);
        partitions.computeIfAbsent(owner, ignored -> new ArrayDeque<>()).addLast(retained);
    }
    private void removeFromOwner(Event<E> event, RegionId owner) {
        if (!event.owner().equals(owner)) throw new IllegalStateException("block event partition owner changed before dispatch");
        ArrayDeque<Event<E>> queue = partitions.get(owner);
        if (queue == null || !queue.remove(event)) throw new IllegalStateException("block event missing from owner partition");
        if (queue.isEmpty()) partitions.remove(owner);
    }
    private RoutingKey key(E event) {
        BlockPos pos = position.apply(event);
        return new RoutingKey(world, pos.getX() >> 4, pos.getZ() >> 4);
    }
    private RegionRoute route(RoutingKey key) {
        return directory.resolve(key).orElseThrow(() -> new IllegalStateException("unresolved block event " + key));
    }
    private static RegionRoute resolve(RegionDirectory.View view, RoutingKey key) {
        RegionRoute route = view.routes().get(key);
        if (route != null) return route;
        if (view.hasExplicitRoutesForWorld(key.world())) throw new IllegalStateException("unresolved block event migration key " + key);
        route = view.worldDefaults().get(key.world());
        if (route == null) throw new IllegalStateException("unresolved block event migration key " + key);
        return route;
    }

    private static final class Event<E> {
        private final long sequence;
        private final RoutingKey key;
        private RegionId owner;
        private final E event;
        private Event(long sequence, RoutingKey key, RegionId owner, E event) {
            this.sequence = sequence; this.key = key; this.owner = owner; this.event = event;
        }
        long sequence() { return sequence; }
        RoutingKey key() { return key; }
        RegionId owner() { return owner; }
        E event() { return event; }
    }
    public record MigrationPlan(long generation, Map<Long, RegionId> owners) {
        public MigrationPlan { owners = Map.copyOf(owners); }
    }
    public record Measurements(int ownerPartitions, int pending, long enqueued, long executed,
                               long deferred, long migrated, long cycles, boolean cycling) { }
}
