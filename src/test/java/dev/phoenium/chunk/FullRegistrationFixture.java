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
    final RegionDirectory directory = new RegionDirectory();
    final FailureBarrier barrier = new FailureBarrier();
    final ChunkPrerequisiteServices services = new ChunkPrerequisiteServices(worldId, "registration", 1);
    final Map<Long, Holder> holders = new HashMap<>();
    final Map<Long, ProtoChunk> inputs = new HashMap<>();
    final ArrayDeque<Runnable> registrations = new ArrayDeque<>();
    final FullRegistrationRuntime runtime;
    final WorldGenContext context;
    final Set<ChunkGenerationTask> tasks = new LinkedHashSet<>();
    ChunkPos delayedPosition;
    ChunkStatus delayedStatus;
    CompletableFuture<ChunkAccess> producer;
    int mapCalls;

    FullRegistrationFixture(boolean inline) throws Exception {
        owner.transitionTo(RegionLifecycle.READY);
        var generation = new TopologyGeneration(1);
        directory.publish(generation, Map.of(), Map.of(worldId, new RegionRoute(ownerId, generation, new OwnerEpoch(0))));
        runtime = new FullRegistrationRuntime(world.level, new ChunkBindingRuntime.WorldAuthority(worldId, 1, services,
                directory, Map.of(ownerId, owner.leases()), barrier));
        context = new WorldGenContext(world.level, null, null, null, inline ? Runnable::run : registrations::add, ignored -> {});
        inputs.put(ChunkPos.ZERO.pack(), world.proto);
    }
    public FullRegistrationRuntime phoenium$fullRegistration() { return runtime; }
    Holder holder(ChunkPos pos) { return holders.computeIfAbsent(pos.pack(), ignored -> new Holder(pos)); }
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
        for (var task : List.copyOf(tasks)) { task.markForCancellation(); resume(task); }
        runtime.shutdown();
    }
    static final class Holder extends GenerationChunkHolder {
        final List<CompletableFuture<?>> saves = new ArrayList<>();
        Runnable afterReplace = () -> {};
        int replacements;
        @Override public void replaceProtoChunk(ImposterProtoChunk chunk) {
            super.replaceProtoChunk(chunk); replacements++; afterReplace.run();
        }
        Holder(ChunkPos pos) {
            super(pos);
            try { set(this, GenerationChunkHolder.class, "highestAllowedStatus", ChunkStatus.FULL); }
            catch (Exception e) { throw new AssertionError(e); }
        }
        protected void addSaveDependency(CompletableFuture<?> future) { saves.add(future); }
        public int getTicketLevel() { return 33; }
        public int getQueueLevel() { return 33; }
    }
}

