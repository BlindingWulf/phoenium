package dev.phoenium.service;

import dev.phoenium.chunk.ChunkProtocolException;
import dev.phoenium.ownership.OwnershipContext;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** S8-F6: an owned neighbour cascade that reaches an unfinished chunk continues after an
 * owner-free FULL wait instead of crashing, in exactly the native step order. The fake updater
 * is a line-for-line copy of the 26.2 CollectingNeighborUpdater addAndRun/runUpdates bytecode;
 * an unready read throws like ChunkSynchronousAccessGuard does under an owner. */
class NeighborCascadeContinuationTest {
    static final class BlockingRead extends IllegalStateException {
        BlockingRead(BlockPos position) { super("unfinished synchronous chunk dependency inside an owner transaction " + position); }
    }

    /** Positions in chunk x=0 are always ready; other chunks are generated on demand by the test. */
    static final class World {
        final Set<Long> ready = new HashSet<>(Set.of(ChunkPos.pack(new BlockPos(0, 0, 0))));
        final List<String> log = new ArrayList<>();
        final Map<BlockPos, List<BlockPos>> fanout = new HashMap<>();
        final Map<BlockPos, BlockPos> liquid = new HashMap<>(); // dispatch target -> its shouldSpreadLiquid neighbour read
        final List<String> owners = new ArrayList<>();
        boolean ready(BlockPos position) { return ready.contains(ChunkPos.pack(position)); }
        void generate(BlockPos position) { ready.add(ChunkPos.pack(position)); }
        void read(BlockPos position) { if (!ready(position)) throw new BlockingRead(position); }
    }

    interface Step { boolean runNext(Updater updater); }
    /** MultiNeighborUpdate: idx++, read the target, dispatch, return idx < length. */
    static final class Multi implements Step {
        final BlockPos source; final List<BlockPos> targets; int idx;
        Multi(BlockPos source, List<BlockPos> targets) { this.source = source; this.targets = List.copyOf(targets); }
        public boolean runNext(Updater updater) {
            BlockPos target = targets.get(idx++);
            updater.world.read(target);
            updater.dispatch(target);
            return idx < targets.size();
        }
    }

    static final class Updater implements NeighborCascadeContinuation.Port {
        final World world; final int max;
        final ArrayDeque<Object> stack = new ArrayDeque<>();
        final List<Object> layer = new ArrayList<>();
        int count;
        Updater(World world, int max) { this.world = world; this.max = max; }
        void addAndRun(BlockPos position, Object update) {
            if (NeighborCascadeContinuation.deferRoot(this, position, update)) return;
            boolean runningAlready = count > 0;
            boolean tooMany = max >= 0 && count >= max;
            count++;
            if (!tooMany) { if (runningAlready) layer.add(update); else stack.push(update); }
            else if (count - 1 == max) world.log.add("too-many:" + position.toShortString());
            if (!runningAlready) phoenium$cascadeRunUpdates();
        }
        void updateNeighbors(BlockPos source) {
            addAndRun(source, new Multi(source, world.fanout.getOrDefault(source, List.of())));
        }
        /** The block at target reacts: logs, may spread like lava, and may update its own neighbours. */
        void dispatch(BlockPos target) {
            BlockPos deep = world.liquid.get(target);
            if (deep != null) {
                NeighborCascadeContinuation.neighborChanged(world, target, () -> {
                    NeighborCascadeContinuation.spreadCheck(() -> {
                        NeighborCascadeContinuation.deepRead(world, deep);
                        world.read(deep);
                        NeighborCascadeContinuation.beforeMutation();
                        return true;
                    });
                    react(target);
                });
            } else react(target);
        }
        private void react(BlockPos target) {
            world.log.add("update:" + target.toShortString());
            OwnershipContext.current().ifPresent(context -> world.owners.add(context.region().value()));
            if (world.fanout.containsKey(target)) updateNeighbors(target);
        }
        @Override public Object phoenium$cascadeLevel() { return world; }
        @Override public ArrayDeque<Object> phoenium$cascadeStack() { return stack; }
        @Override public List<Object> phoenium$cascadeLayer() { return layer; }
        @Override public int phoenium$cascadeCount() { return count; }
        @Override public void phoenium$cascadeCount(int value) { count = value; }
        @Override public int phoenium$cascadeMaxChained() { return max; }
        @Override public void phoenium$cascadeRunUpdates() {
            try {
                while (!stack.isEmpty() || !layer.isEmpty()) {
                    for (int i = layer.size() - 1; i >= 0; i--) stack.push(layer.get(i));
                    layer.clear();
                    Object next = stack.peek();
                    while (layer.isEmpty()) {
                        if (!NeighborCascadeContinuation.runNext(this, next, () -> ((Step) next).runNext(this))) {
                            stack.pop(); break;
                        }
                    }
                }
            } finally { stack.clear(); layer.clear(); count = 0; }
        }
    }

    static class Environment implements NeighborCascadeContinuation.Environment {
        final ManagedBlockPollTurnFenceTest.Fixture fixture; final World world;
        final List<BlockPos> waited = new ArrayList<>();
        RuntimeException dependencyFailure;
        Environment(ManagedBlockPollTurnFenceTest.Fixture fixture, World world) { this.fixture = fixture; this.world = world; }
        public BlockPos startRead(Object step) {
            return step instanceof Multi multi && multi.idx < multi.targets.size() ? multi.targets.get(multi.idx) : null;
        }
        public BlockPos dispatchTarget(Object step) { return startRead(step); }
        public Object snapshot(Object step) { return step instanceof Multi multi ? multi.idx : null; }
        public void restore(Object step, Object snapshot) { if (step instanceof Multi multi) multi.idx = (Integer) snapshot; }
        public boolean suspendable(Object level) { return level == world && Thread.currentThread() == fixture.loop.thread; }
        public boolean readable(Object level, BlockPos position) { return ((World) level).ready(position); }
        public NeighborCascadeContinuation.ChunkWait await(Object level, BlockPos position) {
            assertTrue(OwnershipContext.current().isEmpty(), "chunk selected under an owner");
            waited.add(position);
            var ready = new CompletableFuture<Void>();
            // The native loop's poll is where chunk generation finishes.
            fixture.loop.body = () -> { world.log.add("generate:" + position.toShortString()); world.generate(position); };
            return new NeighborCascadeContinuation.ChunkWait() {
                public ManagedBlockPollTurnFence fence() { return fixture.loop.fence; }
                public void managedBlock(BooleanSupplier supplier) { fixture.loop.managedBlock(supplier); }
                public CompletionStage<?> dependency() { return ready; }
                public void prepare() {
                    assertTrue(OwnershipContext.current().isEmpty(), "chunk prepared under an owner");
                    if (dependencyFailure != null) ready.completeExceptionally(dependencyFailure);
                    else if (world.ready(position)) ready.complete(null);
                }
                public boolean current() { return true; }
                public void close() { }
            };
        }
    }

    static final BlockPos ROOT = new BlockPos(1, 64, 1), A = new BlockPos(2, 64, 1), B = new BlockPos(3, 64, 1),
        C = new BlockPos(4, 64, 1), FAR = new BlockPos(40, 64, 1), FAR2 = new BlockPos(41, 64, 1),
        D = new BlockPos(5, 64, 1), E = new BlockPos(6, 64, 1), ROOT2 = new BlockPos(7, 64, 1), F = new BlockPos(8, 64, 1),
        DEEP = new BlockPos(100, 64, 1);

    /** ROOT -> [A, B, C]; A -> [FAR, D]; FAR -> [FAR2, E]; ROOT2 -> [F]. FAR is in an unready chunk. */
    static World cascadeWorld() {
        var world = new World();
        world.fanout.put(ROOT, List.of(A, B, C));
        world.fanout.put(A, List.of(FAR, D));
        world.fanout.put(FAR, List.of(FAR2, E));
        world.fanout.put(ROOT2, List.of(F));
        return world;
    }

    record Run(List<String> log, List<String> owners, List<BlockPos> waited, long suspensions) { }

    static Run run(World world, RegionalServiceKind kind, int max, java.util.function.Consumer<Updater> body) {
        try (var f = new ManagedBlockPollTurnFenceTest.Fixture()) {
            var environment = new Environment(f, world);
            f.dispatcher.installNeighborCascades(environment);
            var updater = new Updater(world, max);
            f.dispatcher.executeAndDrain(f.key, kind, execution -> body.accept(updater));
            assertEquals(0, updater.count); assertTrue(updater.stack.isEmpty()); assertTrue(updater.layer.isEmpty());
            assertFalse(f.barrier.tripped());
            return new Run(world.log, world.owners, environment.waited, f.loop.polls);
        }
    }
    static List<String> updates(List<String> log) { return log.stream().filter(line -> line.startsWith("update:") || line.startsWith("too-many:")).toList(); }

    @Test void stepStartCutWaitsOwnerFreeAndResumesInNativeOrder() {
        var reference = cascadeWorld(); reference.generate(FAR);
        var expected = run(reference, RegionalServiceKind.RUN_SCHEDULED_FLUID_TICK, -1, u -> u.updateNeighbors(ROOT));
        var actual = run(cascadeWorld(), RegionalServiceKind.RUN_SCHEDULED_FLUID_TICK, -1, u -> u.updateNeighbors(ROOT));
        assertEquals(updates(expected.log()), updates(actual.log()));
        assertEquals(List.of(FAR), actual.waited());
        int generated = actual.log().indexOf("generate:" + FAR.toShortString());
        assertEquals(List.of("update:2, 64, 1"), actual.log().subList(0, generated), "only the prefix before the cut ran first");
        assertEquals(1, actual.suspensions());
        assertTrue(actual.owners().stream().allMatch("a"::equals), "every update ran under the owner");
    }

    @Test void rootsStartedAfterTheCutRunFifoBehindTheRetainedCascade() {
        java.util.function.Consumer<Updater> body = u -> {
            u.updateNeighbors(ROOT); u.world.log.add("body-between"); u.updateNeighbors(ROOT2); u.world.log.add("body-end");
        };
        var reference = cascadeWorld(); reference.generate(FAR);
        var expected = run(reference, RegionalServiceKind.RUN_SCHEDULED_BLOCK_TICK, -1, body);
        var actual = run(cascadeWorld(), RegionalServiceKind.RUN_SCHEDULED_BLOCK_TICK, -1, body);
        assertEquals(updates(expected.log()), updates(actual.log()), "neighbour updates keep their exact native order");
        // Declared deviation: the body's own code after the cut runs before the deferred remainder.
        assertEquals(List.of("update:2, 64, 1", "body-between", "body-end", "generate:40, 64, 1"), actual.log().subList(0, 4));
    }

    @Test void deepLiquidReadRollsBackTheStepAndDispatchesExactlyOnce() {
        var reference = cascadeWorld(); reference.liquid.put(B, DEEP); reference.generate(FAR); reference.generate(DEEP);
        var expected = run(reference, RegionalServiceKind.RUN_SCHEDULED_FLUID_TICK, -1, u -> u.updateNeighbors(ROOT));
        var world = cascadeWorld(); world.liquid.put(B, DEEP); world.generate(FAR);
        var actual = run(world, RegionalServiceKind.RUN_SCHEDULED_FLUID_TICK, -1, u -> u.updateNeighbors(ROOT));
        assertEquals(updates(expected.log()), updates(actual.log()));
        assertEquals(1, Collections.frequency(actual.log(), "update:3, 64, 1"));
        assertEquals(List.of(DEEP), actual.waited());
    }

    @Test void chainLimitCountSurvivesTheCut() {
        var reference = cascadeWorld(); reference.generate(FAR);
        var expected = run(reference, RegionalServiceKind.BLOCK_EVENT, 2, u -> u.updateNeighbors(ROOT));
        var actual = run(cascadeWorld(), RegionalServiceKind.BLOCK_EVENT, 2, u -> u.updateNeighbors(ROOT));
        assertTrue(updates(expected.log()).stream().anyMatch(line -> line.startsWith("too-many:")));
        assertEquals(updates(expected.log()), updates(actual.log()));
    }

    @Test void secondUnreadyChunkCutsAgainAfterResume() {
        var world = cascadeWorld(); world.fanout.put(FAR, List.of(new BlockPos(80, 64, 1), E));
        var reference = cascadeWorld(); reference.fanout.put(FAR, List.of(new BlockPos(80, 64, 1), E));
        reference.generate(FAR); reference.generate(new BlockPos(80, 64, 1));
        var expected = run(reference, RegionalServiceKind.RUN_SCHEDULED_FLUID_TICK, -1, u -> u.updateNeighbors(ROOT));
        var actual = run(world, RegionalServiceKind.RUN_SCHEDULED_FLUID_TICK, -1, u -> u.updateNeighbors(ROOT));
        assertEquals(updates(expected.log()), updates(actual.log()));
        assertEquals(List.of(FAR, new BlockPos(80, 64, 1)), actual.waited());
    }

    /** Live evidence: every cut, deep cut, deferred root, wait and outcome is counted, with the
     * diagnostics event lines enabled (they must never change the result). */
    @Test void totalsRecordEveryCutWaitAndOutcome() {
        String previous = System.getProperty("phoenium.test.diagnostics");
        System.setProperty("phoenium.test.diagnostics", "true");
        try {
            var before = NeighborCascadeContinuation.totals();
            stepStartCutWaitsOwnerFreeAndResumesInNativeOrder();
            rootsStartedAfterTheCutRunFifoBehindTheRetainedCascade();
            deepLiquidReadRollsBackTheStepAndDispatchesExactlyOnce();
            failedFullDependencyFailsClosedWithItsCause();
            shutdownDuringTheWaitRetiresWithoutRunningTheSuffix();
            var after = NeighborCascadeContinuation.totals();
            assertEquals(5, after.cuts() - before.cuts(), "cuts");
            assertEquals(1, after.deepCuts() - before.deepCuts(), "deep cuts");
            assertEquals(1, after.deferredRoots() - before.deferredRoots(), "deferred roots");
            assertEquals(3, after.settled() - before.settled(), "settled resumes");
            assertEquals(1, after.failures() - before.failures(), "failures");
            assertEquals(1, after.shutdowns() - before.shutdowns(), "shutdowns");
            assertTrue(after.waits() - before.waits() >= 5, "every cut waited at least once");
            assertTrue(after.toString().startsWith("cuts="), after.toString());
        } finally {
            if (previous == null) System.clearProperty("phoenium.test.diagnostics");
            else System.setProperty("phoenium.test.diagnostics", previous);
        }
    }

    @Test void kindsWithTheirOwnContinuationKeepTheFailClosedGuard() {
        assertThrows(BlockingRead.class, () -> run(cascadeWorld(), RegionalServiceKind.CHUNK_GAMEPLAY, -1, u -> u.updateNeighbors(ROOT)));
        assertThrows(BlockingRead.class, () -> run(cascadeWorld(), RegionalServiceKind.BLOCK_ENTITY_TICK, -1, u -> u.updateNeighbors(ROOT)));
    }

    @Test void failedFullDependencyFailsClosedWithItsCause() {
        try (var f = new ManagedBlockPollTurnFenceTest.Fixture()) {
            var world = cascadeWorld(); var environment = new Environment(f, world);
            var failure = new ChunkProtocolException("neighbor cascade FULL dependency not there when requested");
            environment.dependencyFailure = failure;
            f.dispatcher.installNeighborCascades(environment);
            var updater = new Updater(world, -1);
            assertSame(failure, assertThrows(ChunkProtocolException.class,
                () -> f.dispatcher.executeAndDrain(f.key, RegionalServiceKind.RUN_SCHEDULED_FLUID_TICK, e -> updater.updateNeighbors(ROOT))));
            assertEquals(List.of("update:2, 64, 1"), updates(world.log), "nothing past the cut ran");
        }
    }

    @Test void shutdownDuringTheWaitRetiresWithoutRunningTheSuffix() {
        try (var f = new ManagedBlockPollTurnFenceTest.Fixture()) {
            var world = cascadeWorld();
            var environment = new Environment(f, world) {
                @Override public NeighborCascadeContinuation.ChunkWait await(Object level, BlockPos position) {
                    var wait = super.await(level, position);
                    f.loop.body = f.scheduler::stop;
                    return wait;
                }
            };
            f.dispatcher.installNeighborCascades(environment);
            var updater = new Updater(world, -1);
            f.dispatcher.executeAndDrain(f.key, RegionalServiceKind.RUN_SCHEDULED_FLUID_TICK, e -> updater.updateNeighbors(ROOT));
            assertEquals(List.of("update:2, 64, 1"), updates(world.log));
            assertEquals(0, updater.count);
        }
    }

    @Test void cutWithAnUnscheduledLayerIsRejected() {
        try (var f = new ManagedBlockPollTurnFenceTest.Fixture()) {
            var world = cascadeWorld(); f.dispatcher.installNeighborCascades(new Environment(f, world));
            var updater = new Updater(world, -1);
            var step = new Multi(ROOT, List.of(FAR));
            var failure = assertThrows(IllegalStateException.class, () -> f.dispatcher.executeAndDrain(f.key, RegionalServiceKind.RUN_SCHEDULED_FLUID_TICK, e -> {
                updater.stack.push(step); updater.layer.add(new Multi(A, List.of(B))); updater.count = 2;
                NeighborCascadeContinuation.runNext(updater, step, () -> step.runNext(updater));
            }));
            assertFalse(failure instanceof BlockingRead, "the cut itself must reject the non-boundary state");
        }
    }

    @Test void pumpedTasksInsideAnotherReturnFenceStayUnarmed() {
        try (var f = new ManagedBlockPollTurnFenceTest.Fixture()) {
            var world = cascadeWorld(); f.dispatcher.installNeighborCascades(new Environment(f, world));
            var ready = new CompletableFuture<Void>(); var reservation = f.reserve(ready);
            f.loop.body = () -> {
                assertTrue(ManagedBlockPollTurnFence.reservationActive());
                assertThrows(BlockingRead.class, () -> f.dispatcher.executeAndDrain(f.key,
                    RegionalServiceKind.RUN_SCHEDULED_FLUID_TICK, e -> new Updater(world, -1).updateNeighbors(ROOT)));
                ready.complete(null);
            };
            f.await(reservation, () -> { });
            assertFalse(ManagedBlockPollTurnFence.reservationActive());
        }
    }

    @Test void productionConfigurationInstallsTheCascadeSeams() throws Exception {
        String config;
        try (var input = getClass().getResourceAsStream("/phoenium.mixins.json")) {
            config = new String(Objects.requireNonNull(input).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        for (String mixin : List.of("NeighborCascadeUpdaterMixin", "NeighborCascadeMultiAccess", "NeighborCascadeSimpleAccess",
                "NeighborCascadeFullAccess", "NeighborCascadeShapeAccess", "NeighborCascadeLiquidMixin"))
            assertTrue(config.contains('"' + mixin + '"'), mixin);
    }
}
