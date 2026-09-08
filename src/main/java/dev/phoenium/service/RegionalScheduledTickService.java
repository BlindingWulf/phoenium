package dev.phoenium.service;

import dev.phoenium.core.WorldId;
import dev.phoenium.ownership.OwnershipContext;
import dev.phoenium.region.RegionId;
import dev.phoenium.topology.RegionDirectory;
import dev.phoenium.topology.RegionRoute;
import dev.phoenium.topology.RoutingKey;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.LinkedHashSet;
import java.util.function.BiConsumer;
import java.util.function.LongPredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.ScheduledTick;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongMaps;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

/**
 * Owner-local view of Minecraft's real per-chunk tick containers. The vanilla
 * {@link LevelChunkTicks} remains the persistence object and therefore preserves saved delay,
 * priority, sub-tick order, and its per-position/type deduplication. This service owns selection
 * and dispatch: no unchanged world-global {@code LevelTicks.tick} call remains on the live path.
 */
public final class RegionalScheduledTickService<T> {
    private static final Comparator<Eligible<?>> CONTAINER_ORDER = (left, right) ->
        ScheduledTick.INTRA_TICK_DRAIN_ORDER.compare(left.head(), right.head());

    private final WorldId world;
    private final RegionDirectory directory;
    private final RegionalServiceDispatcher dispatcher;
    private final RegionalServiceKind collectKind;
    private final RegionalServiceKind runKind;
    private final LongPredicate tickCheck;
    private final Map<RegionId, Map<Long, ContainerState<T>>> partitions = new LinkedHashMap<>();
    private final Map<Long, ContainerState<T>> containers = new LinkedHashMap<>();
    // The production binding supplies LevelTicks' actual fields. Native clear/copy/query
    // methods therefore observe and mutate the same cohort as regional dispatch.
    private Queue<ScheduledTick<T>> toRunThisTick = new ArrayDeque<>();
    private List<ScheduledTick<T>> alreadyRunThisTick = new ArrayList<>();
    private Set<ScheduledTick<?>> toRunThisTickSet =
        new it.unimi.dsi.fastutil.objects.ObjectOpenCustomHashSet<>(ScheduledTick.UNIQUE_TICK_HASH);
    private boolean originalCycleBound;
    private Long2LongMap nextTickForContainer = newTickIndex();
    private long cycle;
    private boolean cycling;
    private long collectedCount;
    private long executedCount;
    private long migratedContainers;

    public RegionalScheduledTickService(WorldId world, RegionDirectory directory,
                                        RegionalServiceDispatcher dispatcher,
                                        LongPredicate tickCheck, boolean blockTicks) {
        this.world = Objects.requireNonNull(world, "world");
        this.directory = Objects.requireNonNull(directory, "directory");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.tickCheck = Objects.requireNonNull(tickCheck, "tickCheck");
        this.collectKind = blockTicks ? RegionalServiceKind.COLLECT_SCHEDULED_BLOCK_TICK
                                      : RegionalServiceKind.COLLECT_SCHEDULED_FLUID_TICK;
        this.runKind = blockTicks ? RegionalServiceKind.RUN_SCHEDULED_BLOCK_TICK
                                  : RegionalServiceKind.RUN_SCHEDULED_FLUID_TICK;
    }

    /** One-time registration for already loaded persistent chunk containers. */
    public synchronized void bindOriginalCycle(Long2LongMap nextTicks, Queue<ScheduledTick<T>> pending, List<ScheduledTick<T>> ran,
                                                Set<ScheduledTick<?>> querySet) {
        if (originalCycleBound || cycling || !toRunThisTick.isEmpty() || !alreadyRunThisTick.isEmpty())
            throw new IllegalStateException("regional tick cycle storage already active or bound");
        nextTickForContainer = Objects.requireNonNull(nextTicks);
        toRunThisTick = Objects.requireNonNull(pending);
        alreadyRunThisTick = Objects.requireNonNull(ran);
        toRunThisTickSet = Objects.requireNonNull(querySet);
        if (!pending.isEmpty() || !ran.isEmpty() || !querySet.isEmpty())
            throw new IllegalStateException("cannot bind an active original tick cycle");
        originalCycleBound = true;
    }

    public synchronized void bootstrap(Map<Long, LevelChunkTicks<T>> initial) {
        if (!containers.isEmpty()) throw new IllegalStateException("scheduled tick service already bootstrapped");
        initial.forEach(this::addContainer);
    }

    public synchronized void addContainer(long chunkKey, LevelChunkTicks<T> container) {
        Objects.requireNonNull(container, "container");
        if (containers.containsKey(chunkKey)) throw new IllegalStateException("duplicate regional tick container " + key(chunkKey));
        RegionId owner = route(chunkKey).region();
        ContainerState<T> state = new ContainerState<>(chunkKey, owner, container);
        containers.put(chunkKey, state);
        partitions.computeIfAbsent(owner, ignored -> new LinkedHashMap<>()).put(chunkKey, state);
        ScheduledTick<T> head = container.peek();
        if (head != null) nextTickForContainer.put(chunkKey, head.triggerTick());
    }

    public synchronized void removeContainer(long chunkKey, LevelChunkTicks<T> expected) {
        ContainerState<T> state = containers.get(chunkKey);
        if (state == null) return;
        if (state.container() != expected) throw new IllegalStateException("regional tick container identity changed " + key(chunkKey));
        containers.remove(chunkKey);
        nextTickForContainer.remove(chunkKey);
        Map<Long, ContainerState<T>> partition = partitions.get(state.owner());
        if (partition == null || partition.remove(chunkKey) != state)
            throw new IllegalStateException("regional tick partition lost container " + key(chunkKey));
        if (partition.isEmpty()) partitions.remove(state.owner());
    }

    /** Called after vanilla's real container applies its deduplication rule. */
    public synchronized void scheduled(long chunkKey, LevelChunkTicks<T> expected) {
        ContainerState<T> state = containers.get(chunkKey);
        if (state == null) return; // unloaded scheduling retains vanilla's original warning/no-op
        if (state.container() != expected) throw new IllegalStateException("regional tick scheduled through foreign container");
        ScheduledTick<T> head = state.container().peek();
        if (head == null) nextTickForContainer.remove(chunkKey);
        else nextTickForContainer.put(chunkKey, head.triggerTick());
    }

    /** Producer gate invoked before the real persistent container is mutated. */
    public synchronized void requireScheduleOwner(long chunkKey, LevelChunkTicks<T> expected) {
        ContainerState<T> state = containers.get(chunkKey);
        if (state == null) return; // preserve vanilla's unloaded-position warning/no-op path
        if (state.container() != expected)
            throw new IllegalStateException("regional tick scheduled through foreign container");
        RegionRoute current = route(chunkKey);
        if (!current.region().equals(state.owner()))
            throw new IllegalStateException("regional tick producer reached stale owner partition");
        // Scheduling changes this container and its scheduling index only. Its future
        // callback halo is admitted when that tick becomes due, not recursively here.
        dispatcher.requireCurrentOwner(key(chunkKey));
    }
    RegionalServiceKind producerKind() { return collectKind; }
    WorldId world() { return world; }

    /**
     * Collects the same globally ordered cohort before invoking any callback, so
     * willTickThisTick observes all not-yet-run entries and callback-created ticks wait until the
     * next cycle exactly as in the pinned LevelTicks algorithm.
     */
    public void tick(long currentTick, int maxTicksToProcess, BiConsumer<BlockPos, T> output) {
        Objects.requireNonNull(output, "output");
        if (OwnershipContext.current().isPresent())
            throw new IllegalStateException("regional scheduled-tick cycle entered with owner authority");
        synchronized (this) {
            if (cycling) throw new IllegalStateException("nested regional scheduled-tick cycle");
        }
        Queue<Eligible<T>> ready = new PriorityQueue<>(containerComparator());
        try {
            // Pinned LevelTicks probes every due nextTickForContainer entry exactly once, in the
            // primitive map's iteration order, before maxTicksToProcess can stop container drain.
            List<PendingIndex> pending = pendingIndexSnapshot();
            for (PendingIndex indexed : pending) {
                if (indexed.triggerTick > currentTick) continue;
                ContainerState<T> state;
                synchronized (this) {
                    if (nextTickForContainer.get(indexed.chunkKey) != indexed.triggerTick) continue;
                    state = containers.get(indexed.chunkKey);
                    if (state == null) { nextTickForContainer.remove(indexed.chunkKey); continue; }
                }
                dispatcher.ensureClosure(key(indexed.chunkKey), 1);
                @SuppressWarnings("unchecked") Eligible<T>[] eligible = new Eligible[1];
                long[] future = {Long.MIN_VALUE};
                dispatcher.executeAndDrain(key(indexed.chunkKey), collectKind, execution -> {
                    synchronized (this) { requireCurrentState(state, execution.runtime().id()); }
                    ScheduledTick<T> head = state.container().peek();
                    if (head == null) future[0] = Long.MAX_VALUE;
                    else if (head.triggerTick() > currentTick) future[0] = head.triggerTick();
                    else if (tickCheck.test(indexed.chunkKey)) eligible[0] = new Eligible<>(state, head);
                });
                synchronized (this) {
                    if (nextTickForContainer.get(indexed.chunkKey) != indexed.triggerTick)
                        throw new IllegalStateException("scheduled tick index changed during eligibility probe");
                    if (eligible[0] != null) {
                        nextTickForContainer.remove(indexed.chunkKey);
                        ready.add(eligible[0]);
                    } else if (future[0] == Long.MAX_VALUE) nextTickForContainer.remove(indexed.chunkKey);
                    else if (future[0] != Long.MIN_VALUE) nextTickForContainer.put(indexed.chunkKey, future[0]);
                }
            }

            synchronized (this) {
                if (cycling) throw new IllegalStateException("regional scheduled-tick cycle began during eligibility sort");
                cycling = true; cycle++;
            }

            while (collectedSize() < maxTicksToProcess) {
                Eligible<T> selected = ready.poll();
                if (selected == null) break;
                ContainerState<T> state = selected.state();
                dispatcher.executeAndDrain(key(state.chunkKey()), collectKind, execution -> {
                    synchronized (this) { requireCurrentState(state, execution.runtime().id()); }
                    ScheduledTick<T> polled = state.container().poll();
                    if (polled != selected.head())
                        throw new IllegalStateException("scheduled tick head changed after eligibility " + key(state.chunkKey()));
                    synchronized (this) {
                        toRunThisTick.add(polled); collectedCount++;
                        Eligible<T> nextBest = ready.peek();
                        ScheduledTick<T> nextOther = nextBest == null ? null : nextBest.head();
                        while (collectedSize() < maxTicksToProcess) {
                            ScheduledTick<T> next = state.container().peek();
                            if (next == null || next.triggerTick() > currentTick
                                    || nextOther != null && ScheduledTick.INTRA_TICK_DRAIN_ORDER.compare(next, nextOther) > 0) break;
                            state.container().poll(); toRunThisTick.add(next); collectedCount++;
                        }
                        ScheduledTick<T> next = state.container().peek();
                        if (next != null) {
                            if (next.triggerTick() <= currentTick && collectedSize() < maxTicksToProcess)
                                ready.add(new Eligible<>(state, next));
                            else nextTickForContainer.put(state.chunkKey(), next.triggerTick());
                        }
                    }
                });
            }

            synchronized (this) {
                for (Eligible<T> state : ready)
                    nextTickForContainer.put(state.state().chunkKey(), state.head().triggerTick());
            }

            while (true) {
                ScheduledTick<T> entry;
                synchronized (this) {
                    entry = toRunThisTick.peek();
                    if (entry == null) break;
                }
                long chunkKey = ChunkPos.pack(entry.pos());
                dispatcher.executeAndDrain(key(chunkKey), runKind, execution -> {
                    RegionRoute current = route(chunkKey);
                    if (!current.region().equals(execution.runtime().id()))
                        throw new IllegalStateException("collected scheduled tick reached stale owner");
                    synchronized (this) {
                        if (toRunThisTick.poll() != entry) throw new IllegalStateException("regional tick cohort changed before delivery");
                        if (!toRunThisTickSet.isEmpty()) toRunThisTickSet.remove(entry);
                        alreadyRunThisTick.add(entry);
                    }
                    output.accept(entry.pos(), entry.type());
                    synchronized (this) { executedCount++; }
                });
            }
        } finally {
            synchronized (this) {
                toRunThisTick.clear();
                alreadyRunThisTick.clear();
                toRunThisTickSet.clear();
                cycling = false;
            }
        }
    }

    public synchronized boolean willTickThisTick(BlockPos pos, T type) {
        if (toRunThisTickSet.isEmpty() && !toRunThisTick.isEmpty()) toRunThisTickSet.addAll(toRunThisTick);
        return toRunThisTickSet.contains(ScheduledTick.probe(type, pos));
    }

    /** Runs the original finite-area operation once under its complete source/destination cut. */
    public void mutateArea(RegionalScheduledTickService<T> source, BoundingBox area, Vec3i offset, Runnable original) {
        Objects.requireNonNull(source); Objects.requireNonNull(area); Objects.requireNonNull(original);
        if (!world.equals(source.world) || dispatcher != source.dispatcher)
            throw new IllegalArgumentException("tick area operation crosses regional world identity");
        Set<RoutingKey> footprint = new LinkedHashSet<>(source.areaKeys(area));
        BoundingBox destination = offset == null ? area : new BoundingBox(
            area.minX() + offset.getX(), area.minY() + offset.getY(), area.minZ() + offset.getZ(),
            area.maxX() + offset.getX(), area.maxY() + offset.getY(), area.maxZ() + offset.getZ());
        Set<RoutingKey> destinationKeys = areaKeys(destination);
        footprint.addAll(offset == null ? destinationKeys
            : new dev.phoenium.topology.TopologyPlanner().requiredClosure(destinationKeys, 1));
        Runnable body = () -> {
            dispatcher.requireCurrentClosure(footprint);
            try { original.run(); }
            finally { refreshArea(destination); }
        };
        if (footprint.isEmpty()) { original.run(); return; }
        if (OwnershipContext.current().isPresent()) body.run();
        else {
            dispatcher.ensureClosure(footprint);
            dispatcher.executeAndDrain(footprint.iterator().next(), collectKind, ignored -> body.run());
        }
    }

    private synchronized Set<RoutingKey> areaKeys(BoundingBox area) {
        Set<RoutingKey> keys = new LinkedHashSet<>();
        for (int x = area.minX() >> 4; x <= area.maxX() >> 4; x++)
            for (int z = area.minZ() >> 4; z <= area.maxZ() >> 4; z++) {
                long chunkKey = ChunkPos.pack(x, z);
                if (containers.containsKey(chunkKey)) keys.add(key(chunkKey));
            }
        for (ScheduledTick<T> tick : alreadyRunThisTick) if (area.isInside(tick.pos())) keys.add(key(ChunkPos.pack(tick.pos())));
        for (ScheduledTick<T> tick : toRunThisTick) if (area.isInside(tick.pos())) keys.add(key(ChunkPos.pack(tick.pos())));
        return keys;
    }

    private synchronized void refreshArea(BoundingBox area) {
        for (int x = area.minX() >> 4; x <= area.maxX() >> 4; x++)
            for (int z = area.minZ() >> 4; z <= area.maxZ() >> 4; z++) {
                ContainerState<T> state = containers.get(ChunkPos.pack(x, z));
                if (state != null) {
                    dispatcher.requireCurrentOwner(key(state.chunkKey()));
                    ScheduledTick<T> head = state.container().peek();
                    if (head == null) nextTickForContainer.remove(state.chunkKey());
                    else nextTickForContainer.put(state.chunkKey(), head.triggerTick());
                }
            }
    }

    /**
     * Builds and installs an explicit partition transfer against an already published immutable
     * directory view. Callers must keep topology admission closed and prove all leases absent.
     */
    public synchronized MigrationPlan planMigration(RegionDirectory.View published) {
        if (OwnershipContext.current().isPresent() || cycling)
            throw new IllegalStateException("scheduled tick migration is not owner-free");
        Map<Long, RegionId> owners = new LinkedHashMap<>();
        for (long chunkKey : containers.keySet()) {
            RoutingKey key = key(chunkKey);
            RegionRoute route = resolve(published, key);
            owners.put(chunkKey, route.region());
        }
        return new MigrationPlan(published.generation().value(), Map.copyOf(owners));
    }

    public synchronized void installMigration(MigrationPlan plan, RegionDirectory.View published) {
        Objects.requireNonNull(plan, "plan");
        if (plan.generation() != published.generation().value())
            throw new IllegalStateException("scheduled tick migration generation changed");
        if (!plan.owners().keySet().equals(containers.keySet()))
            throw new IllegalStateException("scheduled tick migration container set changed");
        Map<RegionId, Map<Long, ContainerState<T>>> next = new LinkedHashMap<>();
        for (var entry : containers.entrySet()) {
            RegionId owner = Objects.requireNonNull(plan.owners().get(entry.getKey()));
            ContainerState<T> state = entry.getValue();
            if (!state.owner().equals(owner)) {
                state.owner = owner;
                migratedContainers++;
            }
            next.computeIfAbsent(owner, ignored -> new LinkedHashMap<>()).put(entry.getKey(), state);
        }
        partitions.clear();
        partitions.putAll(next);
    }

    public synchronized Measurements measurements() {
        return new Measurements(partitions.size(), containers.size(), nextTickForContainer.size(), toRunThisTick.size(),
            collectedCount, executedCount, migratedContainers, cycle, cycling);
    }

    private synchronized int collectedSize() { return toRunThisTick.size(); }
    private ContainerState<T> requireCurrentState(ContainerState<T> selected, RegionId executingOwner) {
        ContainerState<T> state = containers.get(selected.chunkKey());
        if (state == null || state != selected)
            throw new IllegalStateException("stale scheduled tick selection " + key(selected.chunkKey()));
        if (!state.owner().equals(executingOwner))
            throw new IllegalStateException("scheduled tick partition owner changed before collection");
        return state;
    }
    private synchronized List<PendingIndex> pendingIndexSnapshot() {
        List<PendingIndex> result = new ArrayList<>(nextTickForContainer.size());
        var iterator = Long2LongMaps.fastIterator(nextTickForContainer);
        while (iterator.hasNext()) {
            Long2LongMap.Entry entry = iterator.next();
            result.add(new PendingIndex(entry.getLongKey(), entry.getLongValue()));
        }
        return result;
    }
    private static Long2LongMap newTickIndex() {
        Long2LongMap result = new Long2LongOpenHashMap();
        result.defaultReturnValue(Long.MAX_VALUE);
        return result;
    }
    private RegionRoute route(long chunkKey) {
        return directory.resolve(key(chunkKey)).orElseThrow(() -> new IllegalStateException("unresolved scheduled tick chunk " + key(chunkKey)));
    }
    private RoutingKey key(long chunkKey) {
        return new RoutingKey(world, ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey));
    }
    private static RegionRoute resolve(RegionDirectory.View view, RoutingKey key) {
        RegionRoute route = view.routes().get(key);
        if (route != null) return route;
        if (view.hasExplicitRoutesForWorld(key.world()))
            throw new IllegalStateException("unresolved scheduled tick migration key " + key);
        route = view.worldDefaults().get(key.world());
        if (route == null) throw new IllegalStateException("unresolved scheduled tick migration key " + key);
        return route;
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> Comparator<Eligible<T>> containerComparator() { return (Comparator) CONTAINER_ORDER; }

    private static final class ContainerState<T> {
        private final long chunkKey;
        private RegionId owner;
        private final LevelChunkTicks<T> container;
        private ContainerState(long chunkKey, RegionId owner, LevelChunkTicks<T> container) {
            this.chunkKey = chunkKey; this.owner = owner; this.container = container;
        }
        long chunkKey() { return chunkKey; }
        RegionId owner() { return owner; }
        LevelChunkTicks<T> container() { return container; }
    }
    private record Eligible<T>(ContainerState<T> state, ScheduledTick<T> head) { }
    private record PendingIndex(long chunkKey, long triggerTick) { }
    public record MigrationPlan(long generation, Map<Long, RegionId> owners) {
        public MigrationPlan { owners = Map.copyOf(owners); }
    }
    public record Measurements(int ownerPartitions, int containers, int indexedHeads, int collectedNow,
                               long collected, long executed, long migratedContainers, long cycles,
                               boolean cycling) { }
}
