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
import java.util.TreeSet;
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

/**
 * Owner-local view of Minecraft's real per-chunk tick containers. The vanilla
 * {@link LevelChunkTicks} remains the persistence object and therefore preserves saved delay,
 * priority, sub-tick order, and its per-position/type deduplication. This service owns selection
 * and dispatch: no unchanged world-global {@code LevelTicks.tick} call remains on the live path.
 */
public final class RegionalScheduledTickService<T> {
    private static final Comparator<Head<?>> INTRA_ORDER = (left, right) -> {
        int tick = ScheduledTick.INTRA_TICK_DRAIN_ORDER.compare(left.tick(), right.tick());
        if (tick != 0) return tick;
        return Long.compare(left.chunkKey(), right.chunkKey());
    };
    private static final Comparator<Head<?>> DUE_ORDER = (left, right) -> {
        int trigger = Long.compare(left.tick().triggerTick(), right.tick().triggerTick());
        return trigger != 0 ? trigger : INTRA_ORDER.compare(left, right);
    };

    private final WorldId world;
    private final RegionDirectory directory;
    private final RegionalServiceDispatcher dispatcher;
    private final RegionalServiceKind collectKind;
    private final RegionalServiceKind runKind;
    private final LongPredicate tickCheck;
    private final Map<RegionId, Map<Long, ContainerState<T>>> partitions = new LinkedHashMap<>();
    private final Map<Long, ContainerState<T>> containers = new LinkedHashMap<>();
    /** Persistent eligibility index. Trigger time leads so future priority cannot hide due work. */
    private final TreeSet<Head<T>> headsByDueTime = new TreeSet<>(dueComparator());
    // The production binding supplies LevelTicks' actual fields. Native clear/copy/query
    // methods therefore observe and mutate the same cohort as regional dispatch.
    private Queue<ScheduledTick<T>> toRunThisTick = new ArrayDeque<>();
    private List<ScheduledTick<T>> alreadyRunThisTick = new ArrayList<>();
    private Set<ScheduledTick<?>> toRunThisTickSet =
        new it.unimi.dsi.fastutil.objects.ObjectOpenCustomHashSet<>(ScheduledTick.UNIQUE_TICK_HASH);
    private boolean originalCycleBound;
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
    public synchronized void bindOriginalCycle(Queue<ScheduledTick<T>> pending, List<ScheduledTick<T>> ran,
                                                Set<ScheduledTick<?>> querySet) {
        if (originalCycleBound || cycling || !toRunThisTick.isEmpty() || !alreadyRunThisTick.isEmpty())
            throw new IllegalStateException("regional tick cycle storage already active or bound");
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
        refreshHead(state);
    }

    public synchronized void removeContainer(long chunkKey, LevelChunkTicks<T> expected) {
        ContainerState<T> state = containers.remove(chunkKey);
        if (state == null) return;
        if (state.container() != expected) throw new IllegalStateException("regional tick container identity changed " + key(chunkKey));
        removeHead(state);
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
        refreshHead(state);
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
        dispatcher.requireCurrentClosure(key(chunkKey), 1);
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
        if (maxTicksToProcess < 0) throw new IllegalArgumentException("negative scheduled tick limit");
        List<RoutingKey> dueClosures = new ArrayList<>();
        synchronized (this) {
            if (cycling) throw new IllegalStateException("nested regional scheduled-tick cycle");
            for (Head<T> head : headsByDueTime) {
                if (head.tick().triggerTick() > currentTick) break;
                dueClosures.add(key(head.chunkKey()));
            }
        }
        for (RoutingKey key : dueClosures) dispatcher.ensureClosure(key, 1);
        synchronized (this) {
            if (cycling) throw new IllegalStateException("regional scheduled-tick cycle began during closure preflight");
            cycling = true;
            cycle++;
        }
        TreeSet<Head<T>> ready = new TreeSet<>(headComparator());
        try {
            synchronized (this) {
                for (Head<T> head : headsByDueTime) {
                    if (head.tick().triggerTick() > currentTick) break;
                    ready.add(head);
                }
            }
            while (collectedSize() < maxTicksToProcess) {
                Head<T> head = ready.pollFirst();
                if (head == null) break;
                dispatcher.executeAndDrain(key(head.chunkKey()), collectKind, execution -> {
                    ContainerState<T> state;
                    synchronized (this) {
                        state = requireCurrentState(head, execution.runtime().id());
                    }
                    if (!tickCheck.test(head.chunkKey())) return;
                    ScheduledTick<T> polled = state.container().poll();
                    if (polled != head.tick())
                        throw new IllegalStateException("scheduled tick head changed after selection " + key(head.chunkKey()));
                    synchronized (this) {
                        toRunThisTick.add(polled);
                        collectedCount++;
                        refreshHead(state);
                        if (state.head != null && state.head.tick().triggerTick() <= currentTick)
                            ready.add(state.head);
                    }
                });
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
                if (state != null) { dispatcher.requireCurrentOwner(key(state.chunkKey())); refreshHead(state); }
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
                removeHead(state);
                state.owner = owner;
                refreshHead(state);
                migratedContainers++;
            }
            next.computeIfAbsent(owner, ignored -> new LinkedHashMap<>()).put(entry.getKey(), state);
        }
        partitions.clear();
        partitions.putAll(next);
    }

    public synchronized Measurements measurements() {
        return new Measurements(partitions.size(), containers.size(), headsByDueTime.size(), toRunThisTick.size(),
            collectedCount, executedCount, migratedContainers, cycle, cycling);
    }

    private synchronized int collectedSize() { return toRunThisTick.size(); }
    private ContainerState<T> requireCurrentState(Head<T> head, RegionId executingOwner) {
        ContainerState<T> state = containers.get(head.chunkKey());
        if (state == null || state.container() != head.container() || state.headVersion() != head.version())
            throw new IllegalStateException("stale scheduled tick selection " + key(head.chunkKey()));
        if (!state.owner().equals(executingOwner))
            throw new IllegalStateException("scheduled tick partition owner changed before collection");
        return state;
    }
    private void refreshHead(ContainerState<T> state) {
        removeHead(state);
        ScheduledTick<T> tick = state.container().peek();
        state.headVersion++;
        if (tick != null) {
            Head<T> next = new Head<>(state.chunkKey(), state.owner(), state.container(), tick, state.headVersion());
            state.head = next;
            headsByDueTime.add(next);
        }
    }
    private void removeHead(ContainerState<T> state) {
        if (state.head != null) {
            headsByDueTime.remove(state.head);
            state.head = null;
        }
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
    private static <T> Comparator<Head<T>> headComparator() { return (Comparator) INTRA_ORDER; }
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> Comparator<Head<T>> dueComparator() { return (Comparator) DUE_ORDER; }

    private static final class ContainerState<T> {
        private final long chunkKey;
        private RegionId owner;
        private final LevelChunkTicks<T> container;
        private long headVersion;
        private Head<T> head;
        private ContainerState(long chunkKey, RegionId owner, LevelChunkTicks<T> container) {
            this.chunkKey = chunkKey; this.owner = owner; this.container = container;
        }
        long chunkKey() { return chunkKey; }
        RegionId owner() { return owner; }
        LevelChunkTicks<T> container() { return container; }
        long headVersion() { return headVersion; }
    }
    private record Head<T>(long chunkKey, RegionId owner, LevelChunkTicks<T> container,
                           ScheduledTick<T> tick, long version) { }
    public record MigrationPlan(long generation, Map<Long, RegionId> owners) {
        public MigrationPlan { owners = Map.copyOf(owners); }
    }
    public record Measurements(int ownerPartitions, int containers, int indexedHeads, int collectedNow,
                               long collected, long executed, long migratedContainers, long cycles,
                               boolean cycling) { }
}
