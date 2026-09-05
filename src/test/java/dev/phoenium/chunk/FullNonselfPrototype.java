package dev.phoenium.chunk;

import dev.phoenium.core.*;
import dev.phoenium.failure.FailureBarrier;
import dev.phoenium.ownership.*;
import dev.phoenium.region.RegionId;
import dev.phoenium.task.TaskCorrelationId;
import dev.phoenium.topology.*;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.frog.Tadpole;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.status.*;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.ticks.LevelChunkTicks;
import static dev.phoenium.chunk.FullDecisionWitnessSupport.*;
import static dev.phoenium.chunk.FullNestedQueryBoundaryTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Bounded TEST protocol over the existing custody, real claim/lease, guard and executor.
 * Frozen routing, C/S/W turn availability, generation-writer receipts and footprint metadata
 * are controlled endpoints, NOT installed production services. Each window prepares only its
 * direct inputs. It never waits for a prerequisite's callback or recursively runs an owner.
 * Construction receipts are independent of later callback success (explicit proposed semantics).
 */
final class FullNonselfPrototype {
    final FullTadpoleDecisionTest.Fixture f = new FullTadpoleDecisionTest.Fixture(Tadpole.ticksToBeFrog);
    final WorldId world = new WorldId("nonself-test");
    final RegionId owner = new RegionId(world, "preconverged-window");
    TopologyGeneration generation = new TopologyGeneration(0);
    final RegionDirectory directory = new RegionDirectory();
    final ChunkCustodyModel custody = new ChunkCustodyModel();
    final RegionLeaseManager lease = new RegionLeaseManager(owner);
    final FailureBarrier barrier = new FailureBarrier();
    final ChunkStageAdmission admission = new ChunkStageAdmission(custody, directory, Map.of(owner, lease), barrier);
    final ChunkStageExecutor executor = new ChunkStageExecutor(admission);
    final Map<ChunkPos, Work> works = new LinkedHashMap<>();
    final Set<Work> retainedWorks = new LinkedHashSet<>();
    final Set<InputConsumer> consumers = new HashSet<>();
    final List<String> events = new ArrayList<>();
    final List<Access> accesses = new ArrayList<>();
    final ArrayDeque<Work> queue = new ArrayDeque<>();
    final Set<Work> retirement = new HashSet<>();
    final int maxWindow;
    final int maxQueue;
    final int maxRetained;
    Scope active;
    boolean turnsAvailable = true, closed;
    long wakeups, version;
    int releases, peakRetained;
    java.util.function.Consumer<Work> afterConstruction = ignored -> { };
    java.util.function.Consumer<Work> duringCallback = ignored -> { };
    java.util.function.Consumer<Access> onAccess = ignored -> { };

    final class Work {
        final Node node;
        final ChunkCustodyModel.WorkId id;
        final List<CompoundTag> entities;
        final CompletableFuture<LevelChunk> conversion = new CompletableFuture<>();
        final WorldGenContext context;
        PendingVanillaStage<Integer, LevelChunk> construction, callback;
        PendingVanillaStage<Integer, Void> publication;
        LevelChunk actual;
        long revision;
        int writers, constructions, callbacks, unsaved, pins, cleanups;
        boolean retired, released;
        Work(Node node, List<CompoundTag> entities) {
            this.node = node; this.entities = entities;
            var key = new RoutingKey(world, node.pos().x(), node.pos().z());
            var lifecycle = custody.activate("nonself-session", 1, key, node.holder());
            id = custody.beginWork(lifecycle, 0);
            context = new WorldGenContext(f.level, null, null, null,
                ignored -> { throw new AssertionError("no owner executor pump"); }, ignored -> unsaved++);
        }
        ChunkPos pos() { return node.pos(); }
        Map<ChunkPos, ChunkHolder> holders(Set<ChunkPos> positions) {
            Map<ChunkPos, ChunkHolder> snapshot = new HashMap<>();
            for (ChunkPos pos : positions) snapshot.put(pos, f.visibleHolders.get(pos.pack()));
            return Collections.unmodifiableMap(snapshot); // null is a witnessed absent holder.
        }
        @Override public String toString() { return pos().toString(); }
    }
    record Plan(Work source, Map<Work, Long> inputs, Set<ChunkPos> positions, long generation,
                Map<ChunkPos, ChunkHolder> holders, Map<Work, List<CompoundTag>> saved) {
        Plan(Work source, Map<Work, Long> inputs, Set<ChunkPos> positions, long generation) {
            this(source, Map.copyOf(inputs), Set.copyOf(positions), generation, source.holders(positions), snapshot(inputs));
        }
        private static Map<Work, List<CompoundTag>> snapshot(Map<Work, Long> inputs) {
            Map<Work, List<CompoundTag>> result = new HashMap<>();
            inputs.keySet().forEach(work -> result.put(work, work.entities.stream().map(CompoundTag::copy).toList()));
            return Map.copyOf(result);
        }
    }
    record Access(Work source, Work target, boolean create, boolean present, boolean loaded, List<String> callers) { }
    /** Retention is not query authority. Each independent consumer must acknowledge its own exit. */
    final class InputConsumer implements AutoCloseable {
        private Set<Work> inputs;
        private InputConsumer(Plan plan) {
            inputs = Set.copyOf(plan.inputs.keySet());
            inputs.forEach(work -> work.pins++); consumers.add(this);
        }
        @Override public void close() {
            if (inputs == null) return;
            Set<Work> done = inputs; inputs = null; consumers.remove(this);
            done.forEach(work -> { assertTrue(work.pins > 0); work.pins--; });
            done.forEach(FullNonselfPrototype.this::releaseIfAcknowledged);
        }
    }
    InputConsumer retainInputs(Plan plan) {
        assertFalse(lease.hasActiveLease());
        if (!valid(plan)) throw new ChunkProtocolException("cannot retain stale inputs");
        if (consumers.size() >= maxQueue) throw new IllegalStateException("consumer capacity; producer keeps demand");
        return new InputConsumer(plan);
    }
    final class Scope implements AutoCloseable {
        Plan plan;
        ChunkStageClaim claim;
        final Map<Work, LevelChunk> objects = new IdentityHashMap<>();
        // Explicit exclusive TEST ownership of IMMEDIATE creation/replacement, ticker/listener
        // registration and shape state. This is deliberately not a read-only collision borrow.
        final Set<Work> mutationOwners = new HashSet<>();
        boolean ticketTurn = true;
        boolean live = true;
        private Scope(Plan plan, ChunkStageClaim claim) {
            this.plan = plan; this.claim = claim;
            plan.inputs.keySet().forEach(work -> { objects.put(work, work.actual); work.pins++; });
            mutationOwners.addAll(plan.inputs.keySet());
        }
        LevelChunk use(ServerLevel level, ChunkCustodyModel.WorkId caller, Work target, ChunkHolder holder) {
            return use(level, caller, target, holder, target.actual);
        }
        LevelChunk use(ServerLevel level, ChunkCustodyModel.WorkId caller, Work target, ChunkHolder holder, LevelChunk object) {
            if (!live || active != this) return denied();
            try { claim.require(plan.source.callback.stage()); }
            catch (ChunkProtocolException failure) { return denied(); }
            if (level != f.level || !caller.equals(plan.source.id)
                    || holder != target.node.holder() || !plan.inputs.containsKey(target)
                    || object == null || object != objects.get(target) || object != target.actual
                    || object != target.node.actual() || object.getLevel() != level
                    || works.get(target.pos()) != target || f.visibleHolders.get(target.pos().pack()) != holder
                    || !claim.frozenRoute().region().equals(owner)) return denied();
            return object;
        }
        void requireMutation(Work target) { if (!live || !mutationOwners.contains(target)) denied(); }
        private LevelChunk denied() {
            ChunkSynchronousAccessGuard.beforeWait(() -> false);
            throw new ChunkProtocolException("wrong or expired private collision entitlement");
        }
        @Override public void close() {
            if (!live) return;
            if (active != this) denied();
            live = false; active = null;
            objects.keySet().forEach(work -> { assertTrue(work.pins > 0); work.pins--; });
            objects.clear(); mutationOwners.clear(); plan = null; claim = null; ticketTurn = false;
        }
    }

    FullNonselfPrototype(boolean reload, boolean reverse) throws Exception { this(reload, reverse, 4, 8); }
    FullNonselfPrototype(boolean reload, boolean reverse, int maxWindow, int maxQueue) throws Exception {
        this(reload, reverse, maxWindow, maxQueue, Map.of());
    }
    FullNonselfPrototype(boolean reload, boolean reverse, int maxWindow, int maxQueue,
                         Map<ChunkPos, List<CompoundTag>> savedBlocks) throws Exception {
        this.maxWindow = maxWindow; this.maxQueue = maxQueue;
        if (maxWindow < 1 || maxQueue < 1) throw new IllegalArgumentException("positive bounds required");
        this.maxRetained = Math.multiplyExact(maxWindow, maxQueue);
        Node x = primary(f); position(x.input(), 15.8);
        Node y = neighbor(f, Tadpole.ticksToBeFrog, 16.2);
        for (Node node : reverse ? List.of(y, x) : List.of(x, y)) add(node, reload, savedBlocks.getOrDefault(node.pos(), List.of()));
        set(f.cache, ServerChunkCache.class, "mainThread", Thread.currentThread());
        set(f.cache, ServerChunkCache.class, "lastChunkPos", new long[]{Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE, Long.MIN_VALUE});
        set(f.cache, ServerChunkCache.class, "lastChunkStatus", new ChunkStatus[4]);
        set(f.cache, ServerChunkCache.class, "lastChunk", new ChunkAccess[4]);
        f.queryEndpoint = this::query;
        publishRoutes();
    }
    Work x() { return works.get(new ChunkPos(0, 0)); }
    Work y() { return works.get(new ChunkPos(1, 0)); }
    Work add(Node original, boolean reload, List<CompoundTag> savedBlocks) throws Exception {
        assertFalse(lease.hasActiveLease(), "holder changes must precede an operation");
        if (closed || retainedWorks.size() >= maxRetained) throw new IllegalStateException("retained-input capacity; producer keeps demand");
        Work previous = works.get(original.pos());
        if (previous != null && !previous.retired) throw new ChunkProtocolException("replacement requires authentic old-holder retirement");
        List<CompoundTag> entities = original.input().getEntities();
        Node node = original;
        if (reload) {
            Method method = SerializableChunkData.class.getDeclaredMethod("postLoadChunk", ServerLevel.class, List.class, List.class);
            method.setAccessible(true);
            var postLoad = (LevelChunk.PostLoadProcessor)method.invoke(null, f.level, entities, savedBlocks);
            // Same actual constructor used by SerializableChunkData.read's LEVELCHUNK branch.
            LevelChunk loaded = new LevelChunk(f.level, original.pos(), UpgradeData.EMPTY,
                new LevelChunkTicks<>(), new LevelChunkTicks<>(), original.input().getInhabitedTime(),
                original.input().getSections(), postLoad, null);
            var input = new ImposterProtoChunk(loaded, false);
            @SuppressWarnings("unchecked") var slots = (java.util.concurrent.atomic.AtomicReferenceArray<CompletableFuture<ChunkResult<ChunkAccess>>>)
                field(original.holder(), GenerationChunkHolder.class, "futures");
            for (ChunkStatus status : ChunkStatus.getStatusList()) if (status != ChunkStatus.FULL)
                slots.set(status.getIndex(), CompletableFuture.completedFuture(ChunkResult.of(input)));
            node = new Node(original.holder(), input, original.full());
        }
        Work work = new Work(node, entities); works.put(node.pos(), work); retainedWorks.add(work);
        peakRetained = Math.max(peakRetained, retainedWorks.size()); return work;
    }
    void publishRoutes() {
        assertFalse(lease.hasActiveLease(), "quiescent convergence BEFORE claims");
        generation = new TopologyGeneration(generation.value() + 1); version++;
        Map<RoutingKey, RegionRoute> routes = new HashMap<>();
        for (Work work : works.values()) routes.put(work.id.lifecycle().chunkKey(), new RegionRoute(owner, generation, new OwnerEpoch(0)));
        directory.publish(generation, routes);
    }
    /** Audited tadpole-only discovery. Original formula's horizontal envelope, BOTH attempts;
     * no entity creation/random draws/readAdditionalSaveData during preflight. Other saved-reader
     * closures remain the separately documented D1/D2/C/S/W integration obligations. */
    Plan plan(Work source) {
        assertFalse(lease.hasActiveLease());
        Set<ChunkPos> positions = new LinkedHashSet<>(); positions.add(source.pos());
        double oldWidth = EntityTypes.TADPOLE.getDimensions().width();
        double newWidth = EntityTypes.FROG.getDimensions().width();
        double half = (Math.max(0.0F, (float)newWidth - (float)oldWidth) + 1.0E-6) / 2 + newWidth;
        for (CompoundTag tag : source.entities) {
            assertEquals("minecraft:tadpole", tag.getStringOr("id", ""));
            if (tag.getIntOr("Age", 0) < Tadpole.ticksToBeFrog) continue;
            var pos = tag.getListOrEmpty("Pos");
            double x = pos.getDoubleOr(0, 0), z = pos.getDoubleOr(2, 0);
            int x0 = ((int)Math.floor(x - half - 1.0E-7) - 1) >> 4;
            int x1 = ((int)Math.floor(x + half + 1.0E-7) + 1) >> 4;
            int z0 = ((int)Math.floor(z - half - 1.0E-7) - 1) >> 4;
            int z1 = ((int)Math.floor(z + half + 1.0E-7) + 1) >> 4;
            for (int cx = x0; cx <= x1; cx++) for (int cz = z0; cz <= z1; cz++) positions.add(new ChunkPos(cx, cz));
        }
        if (positions.size() > maxWindow) throw new IllegalArgumentException("UNSUPPORTED footprint; no claim or successful cancellation");
        Map<Work, Long> inputs = new LinkedHashMap<>();
        for (ChunkPos pos : positions) if (works.containsKey(pos)) inputs.put(works.get(pos), works.get(pos).revision);
        return new Plan(source, Map.copyOf(inputs), Set.copyOf(positions), version);
    }
    boolean valid(Plan plan) {
        if (closed || barrier.tripped() || !turnsAvailable || plan.generation != version) return false;
        if (plan.source.retired || works.get(plan.source.pos()) != plan.source || !plan.inputs.containsKey(plan.source)) return false;
        for (var entry : plan.holders.entrySet())
            if (f.visibleHolders.get(entry.getKey().pack()) != entry.getValue()) return false;
        for (var entry : plan.inputs.entrySet()) {
            Work work = entry.getKey();
            if (work.retired || work.writers != 0 || work.revision != entry.getValue()
                    || works.get(work.pos()) != work || f.visibleHolders.get(work.pos().pack()) != work.node.holder()
                    || !work.entities.equals(plan.saved.get(work))
                    || (work.actual != null && work.actual != work.node.actual())) return false;
        }
        return true;
    }
    boolean prepare(Plan plan) {
        assertFalse(lease.hasActiveLease());
        if (!valid(plan)) return false;
        // Deterministic source admission order; prerequisites are shared, not duplicated by windows.
        for (Work work : works.values()) if (plan.inputs.containsKey(work)) {
            if (work.construction != null && work.construction.outcome().isDone()) {
                if (!(work.construction.outcome().join() instanceof ChunkStageOutcome.Applied<?>)) return false;
                continue;
            }
            if (work.construction == null) work.construction = pending(work, "PROTOTYPE_CONSTRUCT", 0, (claim) -> {
                work.constructions++; events.add("P" + work);
                work.actual = FullConstructionFragments.prepare(work.node.input(), work.context, work.node.holder());
                assertFalse(work.node.full().isDone());
                assertSame(work.node.actual(), work.actual);
                afterConstruction.accept(work);
                return work.actual;
            });
            var result = executor.execute(work.construction, owner, generation);
            if (result != ChunkStageAdmission.Result.APPLIED) settleFailure(work, work.construction);
            for (Work retired : List.copyOf(retainedWorks)) releaseIfAcknowledged(retired);
            if (result != ChunkStageAdmission.Result.APPLIED || !valid(plan)) return false;
        }
        return true;
    }
    ChunkStageAdmission.Result execute(Plan plan) {
        assertFalse(lease.hasActiveLease());
        Work work = plan.source;
        if (work.callback != null && work.callback.outcome().isDone())
            return executor.execute(work.callback, owner, generation);
        if (!valid(plan) || plan.inputs.keySet().stream().anyMatch(w -> w.actual == null || w.construction == null
                || !(w.construction.outcome().getNow(null) instanceof ChunkStageOutcome.Applied<?>))) return ChunkStageAdmission.Result.NOT_READY;
        if (work.callback == null) work.callback = pending(work, "PROTOTYPE_CALLBACK", 1, claim -> {
            work.callbacks++; events.add("B" + work);
            try (Scope scope = new Scope(plan, claim)) {
                assertNull(active); active = scope;
                duringCallback.accept(work);
                return FullConstructionFragments.finish(work.actual, work.context);
            }
        });
        var result = executor.execute(work.callback, owner, generation);
        assertNull(active); assertFalse(lease.hasActiveLease()); assertTrue(OwnershipContext.current().isEmpty());
        if (result == ChunkStageAdmission.Result.APPLIED) {
            assertSame(work.actual, ((ChunkStageOutcome.Applied<?>)work.callback.outcome().join()).value());
            work.conversion.complete(work.actual);
        } else settleFailure(work, work.callback);
        // Reached mutations become the next cut; never reconstruct or replay on revision changes.
        if (work.callback.outcome().isDone()) for (Work input : plan.inputs.keySet()) input.revision++;
        for (Work retired : List.copyOf(retirement)) retireNow(retired);
        for (Work retired : List.copyOf(retainedWorks)) releaseIfAcknowledged(retired);
        return result;
    }
    private void settleFailure(Work work, PendingVanillaStage<Integer, LevelChunk> pending) {
        var outcome = pending.outcome().getNow(null);
        if (outcome instanceof ChunkStageOutcome.VanillaFailure<?> failure) work.conversion.completeExceptionally(failure.failure());
        if (outcome instanceof ChunkStageOutcome.BarrierFailure<?> failure) work.conversion.completeExceptionally(failure.failure());
    }
    ChunkStageAdmission.Result publish(Work work) {
        assertFalse(lease.hasActiveLease());
        if (!work.conversion.isDone() || work.conversion.isCompletedExceptionally() || work.retired || closed || barrier.tripped())
            return ChunkStageAdmission.Result.NOT_READY;
        if (works.get(work.pos()) != work || f.visibleHolders.get(work.pos().pack()) != work.node.holder()
                || work.actual != work.node.actual()) return ChunkStageAdmission.Result.NOT_READY;
        if (work.publication == null) {
            var stage = new ChunkCustodyModel.StageId(work.id, ChunkStageKind.STATUS_PUBLISH, "FULL", 0);
            work.publication = new PendingVanillaStage<>(custody, work.id.lifecycle(), work.id, stage,
                new TaskCorrelationId(3), (Integer ignored, ChunkStageClaim claim) -> {
                    claim.require(stage); events.add("U" + work);
                    assertSame(work.actual, work.conversion.getNow(null));
                    FullNestedQueryBoundaryTest.publish(work.node, work.actual); return null;
                });
            work.publication.bindInput(1); work.publication.publishReady();
        }
        return executor.execute(work.publication, owner, generation);
    }
    @FunctionalInterface interface Body { LevelChunk run(ChunkStageClaim claim) throws Throwable; }
    PendingVanillaStage<Integer, LevelChunk> pending(Work work, String phase, int sequence, Body body) {
        var stage = new ChunkCustodyModel.StageId(work.id, ChunkStageKind.FULL_CONVERT, phase, sequence);
        var pending = new PendingVanillaStage<Integer, LevelChunk>(custody, work.id.lifecycle(), work.id, stage,
            new TaskCorrelationId(sequence + 1), (ignored, claim) -> body.run(claim));
        assertTrue(pending.bindInput(1)); pending.publishReady(); return pending;
    }
    ChunkAccess query(Object[] args) {
        if (active == null) {
            ChunkSynchronousAccessGuard.beforeWait(() -> false);
            throw new ChunkProtocolException("private query outside its synchronous claim");
        }
        Plan plan = active.plan;
        ChunkPos pos = new ChunkPos((int)args[0], (int)args[1]);
        boolean create = (boolean)args[3];
        assertSame(ChunkStatus.FULL, args[2]);
        if (!plan.positions.contains(pos)) {
            ChunkSynchronousAccessGuard.beforeWait(() -> false); // fail closed, never successful diagnostic progress
            throw new AssertionError("guard did not reject undeclared footprint");
        }
        // Exact vanilla cache classifier; private values NEVER enter this cache. This classifier
        // and the ready result unwrap are controlled; the future selector and ticket prefix run original code.
        try {
            long[] keys = (long[])field(f.cache, ServerChunkCache.class, "lastChunkPos");
            var statuses = (ChunkStatus[])field(f.cache, ServerChunkCache.class, "lastChunkStatus");
            var values = (ChunkAccess[])field(f.cache, ServerChunkCache.class, "lastChunk");
            for (int i = 0; i < 4; i++) if (keys[i] == pos.pack() && statuses[i] == ChunkStatus.FULL
                    && (values[i] != null || !create)) return values[i];
            if (create && !active.ticketTurn) active.denied();
            Query query = select(f, args); // Includes original UNKNOWN add for true, at the original call.
            if (query.selected().isDone()) {
                ChunkAccess result = query.selected().join().orElse(null);
                if (result == null && create) throw new IllegalStateException("Chunk not there when requested: " + query.selected().join().getError());
                Method store = ServerChunkCache.class.getDeclaredMethod("storeInCache", long.class, ChunkAccess.class, ChunkStatus.class);
                store.setAccessible(true); store.invoke(f.cache, pos.pack(), result, ChunkStatus.FULL); return result;
            }
            Work target = works.get(pos);
            if (target == null || !plan.inputs.containsKey(target) || query.selected() != target.node.full()) {
                ChunkSynchronousAccessGuard.beforeWait(() -> false); throw new AssertionError("unprepared input");
            }
            if (target != plan.source && !query.callers().contains("getChunkForCollisions") && !query.callers().contains("getShape"))
                return active.denied(); // A caught unauthorized getter must still fence the operation.
            LevelChunk result = active.use(f.level, plan.source.id, target, target.node.holder());
            if (create) active.requireMutation(target);
            if (create) assertFalse(f.tickets.getTickets(pos.pack()).isEmpty());
            Access access = new Access(plan.source, target, create, !result.getBlockEntities().isEmpty(),
                (boolean)field(result, LevelChunk.class, "loaded"), query.callers());
            accesses.add(access); onAccess.accept(access);
            return result;
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    boolean enqueue(Work work) {
        assertFalse(lease.hasActiveLease());
        if (closed || work.retired || works.get(work.pos()) != work) return false;
        if (queue.contains(work)) return true;
        if (queue.size() >= maxQueue) return false; // Producer retains the demand; capacity wakeup is required.
        queue.add(work); return true;
    }
    boolean advance() {
        assertFalse(lease.hasActiveLease());
        Work work = queue.peek(); if (work == null) return false;
        Plan plan = plan(work);
        if (!prepare(plan)) return false;
        if (execute(plan) != ChunkStageAdmission.Result.APPLIED) return false;
        assertEquals(ChunkStageAdmission.Result.APPLIED, publish(work));
        queue.remove(); wakeups++; return true;
    }
    void changed(Work work) {
        if (active != null && active.plan.inputs.containsKey(work)) throw new ChunkProtocolException("conflicting writer must remain unclaimed");
        work.revision++; wakeups++;
    }
    void requestRetire(Work work) {
        if (work.released) return;
        if (active != null && active.plan.inputs.containsKey(work)) retirement.add(work);
        else retireNow(work);
    }
    private void retireNow(Work work) {
        if (!work.retired) { work.retired = true; custody.retire(work.id.lifecycle()); }
        retirement.remove(work); releaseIfAcknowledged(work);
    }
    private void releaseIfAcknowledged(Work work) {
        if (!work.retired || work.released || work.pins != 0 || lease.hasActiveLease()) return;
        for (var pending : Arrays.asList(work.construction, work.callback, work.publication)) if (pending != null) {
            pending.control(ChunkStageAdmission.Result.CANCELLED); assertFalse(pending.retainsInputOrContinuation());
        }
        if (!work.conversion.isDone()) work.conversion.completeExceptionally(new java.util.concurrent.CancellationException("authentic test shutdown/retirement"));
        if (!work.node.full().isDone()) work.node.full().completeExceptionally(new java.util.concurrent.CancellationException("controlled terminal holder endpoint"));
        custody.releaseRetiredLifecycle(work.id.lifecycle());
        work.actual = null; work.construction = null; work.callback = null; work.publication = null;
        work.released = true; work.cleanups++; releases++;
        works.remove(work.pos(), work); retainedWorks.remove(work); queue.remove(work);
        f.visibleHolders.remove(work.pos().pack(), work.node.holder());
        accesses.removeIf(access -> access.source == work || access.target == work);
    }
    void shutdown() {
        closed = true; admission.closeAdmissions(); queue.clear();
        for (Work work : List.copyOf(retainedWorks)) requestRetire(work);
    }
    void cleanup() {
        assertNull(active); assertFalse(lease.hasActiveLease());
        assertTrue(consumers.isEmpty(), "legitimate retained consumers must acknowledge before teardown");
        shutdown();
        assertTrue(retainedWorks.isEmpty()); assertTrue(works.isEmpty());
        queue.clear(); retirement.clear(); accesses.clear();
        afterConstruction = ignored -> { }; duringCallback = ignored -> { }; onAccess = ignored -> { };
        assertEquals(0, custody.retainedEntries());
    }
}
