package dev.phoenium.chunk;

import dev.phoenium.core.*;
import dev.phoenium.failure.FailureBarrier;
import dev.phoenium.region.*;
import dev.phoenium.topology.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.server.level.*;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.status.*;
import static dev.phoenium.chunk.FullDecisionWitnessSupport.*;

/** In-memory generation outputs/world endpoints; original task, holder, borrows, FULL wrapper and registrar. */
final class FullRegistrationFixture implements GeneratingChunkMap, FullGenerationSource {
    final FullTadpoleDecisionTest.Fixture world = new FullTadpoleDecisionTest.Fixture(1);
    final WorldId worldId = new WorldId("full-registration");
    final RegionId ownerId = new RegionId(worldId, "owner");
    final RegionRuntime owner = new RegionRuntime(ownerId, new OwnerEpoch(0));
    final RegionId otherId = new RegionId(worldId, "other-owner");
    final RegionRuntime other = new RegionRuntime(otherId, new OwnerEpoch(0));
    final RegionDirectory directory = new RegionDirectory();
    final FailureBarrier barrier = new FailureBarrier();
    final ChunkPrerequisiteServices services = new ChunkPrerequisiteServices(worldId, "registration", 1);
    final Map<Long, Holder> holders = new HashMap<>();
    final Map<Long, ProtoChunk> inputs = new HashMap<>();
    final ArrayDeque<Runnable> registrations = new ArrayDeque<>();
    final FullRegistrationRuntime runtime;
    final WorldGenContext context;
    ChunkMap fixtureMap;
    final Set<ChunkGenerationTask> tasks = new LinkedHashSet<>();
    ChunkPos delayedPosition;
    ChunkStatus delayedStatus;
    CompletableFuture<ChunkAccess> producer;
    int mapCalls;
    Runnable beforeRegistration = () -> {};

    FullRegistrationFixture(boolean inline) throws Exception {
        this(inline, FullInitializedInputs.Limits.production());
    }
    FullRegistrationFixture(boolean inline, FullInitializedInputs.Limits limits) throws Exception {
        owner.transitionTo(RegionLifecycle.READY);
        other.transitionTo(RegionLifecycle.READY);
        var generation = new TopologyGeneration(1);
        directory.publish(generation, Map.of(), Map.of(worldId, new RegionRoute(ownerId, generation, new OwnerEpoch(0))));
        set(world.cache, ServerChunkCache.class, "mainThread", Thread.currentThread());
        set(world.level, ServerLevel.class, "chunkSource", world.cache);
        runtime = new FullRegistrationRuntime(world.level, new ChunkBindingRuntime.WorldAuthority(worldId, 1, services,
                directory, Map.of(ownerId, owner.leases(), otherId, other.leases()), barrier), limits);
        context = new WorldGenContext(world.level, null, null, null, command -> {
            beforeRegistration.run();
            if (inline) command.run(); else registrations.add(command);
        }, ignored -> {});
        inputs.put(ChunkPos.ZERO.pack(), world.proto);
        // Installed Fabric lifecycle callback retains its real loaded-chunk collection. The
        // endpoint world is allocated without constructors; supply that ordinary empty field.
        try { set(world.level, net.minecraft.world.level.Level.class, "loadedChunks", new HashSet<LevelChunk>()); }
        catch (NoSuchFieldException junitWithoutFabricLifecycleMixin) { /* The JUnit agent does not install Fabric's own Mixins. */ }
        long[] emptyCache = new long[4]; Arrays.fill(emptyCache, Long.MAX_VALUE);
        set(world.cache, ServerChunkCache.class, "lastChunkPos", emptyCache);
        set(world.cache, ServerChunkCache.class, "lastChunkStatus", new ChunkStatus[4]);
        set(world.cache, ServerChunkCache.class, "lastChunk", new ChunkAccess[4]);
        var map = stub(ChunkMap.class, List.of(
                new Answer(ChunkMap.class.getMethod("scheduleGenerationTask", ChunkStatus.class, ChunkPos.class), args -> scheduleGenerationTask((ChunkStatus) args[0], (ChunkPos) args[1])),
                new Answer(ChunkMap.class.getMethod("runGenerationTasks"), args -> { runGenerationTasks(); return null; })));
        set(world.cache, ServerChunkCache.class, "chunkMap", map);
        set(map, ChunkMap.class, "phoenium$inputChanges", new FullInputNotifications());
        fixtureMap = map; publishVisible();
    }
    public FullRegistrationRuntime phoenium$fullRegistration() { return runtime; }
    /** Separate map selects the ordinary original FULL supplier from the start of its task. */
    final class BaselineMap implements GeneratingChunkMap, FullPublicationSource {
        public FullPublishedInputs phoenium$publishedInputs() { return runtime.published; }
        public ChunkGenerationTask scheduleGenerationTask(ChunkStatus status, ChunkPos pos) {
            var task = ChunkGenerationTask.create(this, status, pos); tasks.add(task); return task;
        }
        public void runGenerationTasks() { FullRegistrationFixture.this.runGenerationTasks(); }
        public GenerationChunkHolder acquireGeneration(long position) { return FullRegistrationFixture.this.acquireGeneration(position); }
        public void releaseGeneration(GenerationChunkHolder holder) { FullRegistrationFixture.this.releaseGeneration(holder); }
        public CompletableFuture<ChunkAccess> applyStep(GenerationChunkHolder holder, ChunkStep step, StaticCache2D<GenerationChunkHolder> cache) {
            return FullRegistrationFixture.this.applyStep(holder, step, cache);
        }
    }
    ChunkGenerationTask startBaseline(ChunkPos pos) {
        var task = ChunkGenerationTask.create(new BaselineMap(), ChunkStatus.FULL, pos);
        tasks.add(task); resume(task); return task;
    }
    Holder holder(ChunkPos pos) {
        return holders.computeIfAbsent(pos.pack(), ignored -> {
            Holder holder = new Holder(pos); world.visibleHolders.put(pos.pack(), holder); publishVisible(); return holder;
        });
    }
    /** Controlled updating-map contents; original volatile clone publication and production notice. */
    void publishVisible() {
        if (fixtureMap == null) return;
        try {
            var updating = new it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<ChunkHolder>();
            world.visibleHolders.forEach(updating::put);
            set(fixtureMap, ChunkMap.class, "updatingChunkMap", updating);
            set(fixtureMap, ChunkMap.class, "modified", true);
            var publish = ChunkMap.class.getDeclaredMethod("promoteChunkMap"); publish.setAccessible(true); publish.invoke(fixtureMap);
        } catch (Exception failure) { throw new AssertionError(failure); }
    }
    ProtoChunk input(ChunkPos pos) {
        return inputs.computeIfAbsent(pos.pack(), ignored -> {
            var proto = new ProtoChunk(pos, UpgradeData.EMPTY, world.level, world.level.palettedContainerFactory(), null);
            proto.setPersistedStatus(ChunkStatus.SPAWN); return proto;
        });
    }
    public GenerationChunkHolder acquireGeneration(long packed) {
        Holder holder = holder(new ChunkPos(ChunkPos.getX(packed), ChunkPos.getZ(packed))); holder.increaseGenerationRefCount(); return holder;
    }
    public void releaseGeneration(GenerationChunkHolder holder) { holder.decreaseGenerationRefCount(); }
    public CompletableFuture<ChunkAccess> applyStep(GenerationChunkHolder holder, ChunkStep step,
                                                    StaticCache2D<GenerationChunkHolder> cache) {
        mapCalls++;
        if (step.targetStatus() == ChunkStatus.FULL) return step.apply(context, cache, input(holder.getPos()));
        if (holder.getPos().equals(delayedPosition) && step.targetStatus() == delayedStatus) {
            producer = new CompletableFuture<>(); return producer;
        }
        return CompletableFuture.completedFuture(input(holder.getPos()));
    }
    public ChunkGenerationTask scheduleGenerationTask(ChunkStatus status, ChunkPos pos) {
        var task = ChunkGenerationTask.create(this, status, pos); tasks.add(task); return task;
    }
    public void runGenerationTasks() { for (var task : List.copyOf(tasks)) resume(task); }
    void resume(ChunkGenerationTask task) { if (task.runUntilWait() == null) tasks.remove(task); }
    ChunkGenerationTask start(ChunkPos pos, ChunkStatus status) {
        var task = scheduleGenerationTask(status, pos); resume(task); return task;
    }
    void drain() { while (!registrations.isEmpty()) registrations.remove().run(); while (runtime.pumpOne()) { } }
    void stop() {
        runtime.shutdown();
        while (!registrations.isEmpty()) registrations.remove().run();
        for (var task : List.copyOf(tasks)) { task.markForCancellation(); resume(task); }
        runtime.shutdown();
    }
    static final class Holder extends ChunkHolder {
        final List<CompletableFuture<?>> saves = new ArrayList<>();
        Runnable afterReplace = () -> {};
        Runnable afterSave = () -> {};
        int replacements;
        @Override public void replaceProtoChunk(ImposterProtoChunk chunk) {
            super.replaceProtoChunk(chunk); replacements++; afterReplace.run();
        }
        Holder(ChunkPos pos) {
            super(pos, 33, net.minecraft.world.level.LevelHeightAccessor.create(0, 256), null,
                    (position, previous, next, setter) -> setter.accept(next), (position, borderOnly) -> List.of());
            updateHighestAllowedStatus(null);
        }
        protected void addSaveDependency(CompletableFuture<?> future) { super.addSaveDependency(future); saves.add(future); afterSave.run(); }
        void eligibility(int ticket, ChunkMap map) { setTicketLevel(ticket); updateHighestAllowedStatus(map); }
    }
}
