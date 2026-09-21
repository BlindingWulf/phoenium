package dev.phoenium.service;

import dev.phoenium.core.*;
import dev.phoenium.failure.FailureBarrier;
import dev.phoenium.ownership.OwnershipContext;
import dev.phoenium.region.*;
import dev.phoenium.scheduler.*;
import dev.phoenium.topology.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.util.thread.BlockableEventLoop;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.phoenium.service.ManagedBlockPollTurnFence.State.*;

class ManagedBlockPollTurnFenceTest {
    static final class Loop extends BlockableEventLoop<Runnable> {
        final Thread thread = Thread.currentThread();
        final ManagedBlockPollTurnFence fence = new ManagedBlockPollTurnFence(thread);
        Runnable body = () -> fail("unexpected poll");
        int polls, waits;
        boolean pollResult = true;
        Loop() { super("spawn-fence-test", false); }
        public Runnable wrapRunnable(Runnable body) { return body; }
        protected boolean shouldRun(Runnable body) { return true; }
        protected Thread getRunningThread() { return thread; }
        protected boolean pollTask() { return fence.poll(() -> { polls++; body.run(); return pollResult; }); }
        protected void waitForTasks() { waits++; }
    }
    static final class Fixture implements AutoCloseable {
        final WorldId world = new WorldId("spawn");
        final RoutingKey key = new RoutingKey(world, 0, 0);
        final RegionRuntime a = runtime("a"), b = runtime("b");
        final Map<RegionId, RegionRuntime> runtimes = Map.of(a.id(), a, b.id(), b);
        final RegionDirectory directory = new RegionDirectory();
        final FailureBarrier barrier = new FailureBarrier();
        final RegionScheduler scheduler;
        final RegionalServiceDispatcher dispatcher;
        final Loop loop = new Loop();
        Fixture() {
            route(a, 1);
            scheduler = new RegionScheduler(runtimes, directory, barrier);
            dispatcher = new RegionalServiceDispatcher("spawn", 1, directory, runtimes,
                new OwnerGateway(directory, runtimes, scheduler), scheduler);
        }
        RegionRuntime runtime(String name) {
            var result = new RegionRuntime(new RegionId(world, name), new OwnerEpoch(0));
            result.transitionTo(RegionLifecycle.READY); return result;
        }
        void route(RegionRuntime owner, long generation) {
            var gen = new TopologyGeneration(generation);
            directory.publish(gen, Map.of(key, new RegionRoute(owner.id(), gen, owner.ownerEpoch())));
        }
        ManagedBlockPollTurnFence.Reservation reserve(CompletableFuture<?> future) {
            return loop.fence.reserve(1, 1, future, () -> true, dispatcher::requireNaturalSpawnOwnerFree);
        }
        void await(ManagedBlockPollTurnFence.Reservation r, Runnable suffix) {
            r.awaitAndResume(loop::managedBlock, () -> dispatcher.resumeNaturalSpawn(r, key, ignored -> suffix.run()));
        }
        void owner() { assertTrue(OwnershipContext.current().isPresent()); }
        public void close() { dispatcher.requireNaturalSpawnOwnerFree(); scheduler.stop(); }
    }

    @Test void completionInsideNestedSaveDrainDoesNotPreemptAndSuffixWinsNextArbitration() {
        try (var f = new Fixture()) {
            var events = new ArrayList<String>(); var random = new Random(812L); var draws = new ArrayList<Integer>();
            draws.add(random.nextInt()); events.add("prefix");
            var ready = new CompletableFuture<Void>(); var r = f.reserve(ready);
            var completion = f.dispatcher.submitC2mePersistedPoiAfterRelease(f.key, ignored -> {
                f.owner(); assertEquals(1, ManagedBlockPollTurnFence.currentPollId()); events.add("dependency-body");
            });
            completion.completion().thenRun(() -> {
                f.dispatcher.requireNaturalSpawnOwnerFree(); ready.complete(null);
                assertEquals(DEPENDENCY_COMPLETE_IN_POLL, r.state()); events.add("raw-complete");
            });
            f.dispatcher.submit(f.key, RegionalServiceKind.CHUNK_GAMEPLAY, ignored -> {
                f.owner(); assertEquals(DEPENDENCY_COMPLETE_IN_POLL, r.state());
                assertEquals(1, ManagedBlockPollTurnFence.currentPollId()); draws.add(random.nextInt()); events.add("inside-poll");
            });
            f.loop.body = () -> {
                assertEquals(WAITING_DEPENDENCY, r.state()); events.add("poll-enter");
                f.dispatcher.executeAndDrain(f.key, RegionalServiceKind.SAVE_ACKNOWLEDGEMENT, ignored -> {
                    assertEquals(1, ManagedBlockPollTurnFence.currentPollId()); events.add("save");
                });
                f.dispatcher.submit(f.key, RegionalServiceKind.CHUNK_GAMEPLAY, ignored -> { draws.add(random.nextInt()); events.add("later-gameplay"); });
                assertEquals(DEPENDENCY_COMPLETE_IN_POLL, r.state()); events.add("poll-return");
            };
            f.await(r, () -> { f.owner(); assertEquals(RESUMING, r.state());
                assertEquals(0, ManagedBlockPollTurnFence.currentPollId()); draws.add(random.nextInt()); events.add("suffix"); });
            assertEquals(List.of("prefix", "poll-enter", "dependency-body", "raw-complete", "inside-poll", "save", "poll-return", "suffix"), events);
            assertEquals(List.of(-1270909246, -1970933588, 446055132), draws);
            assertEquals(SETTLED, r.state()); assertEquals(1, r.completedPoll()); assertEquals(1, r.returnedPoll());
            assertEquals(1, f.loop.polls); assertEquals(1, f.dispatcher.pendingCount());
            f.scheduler.advanceOne(); assertEquals("later-gameplay", events.getLast());
            assertEquals(0, f.dispatcher.pendingCount()); assertFalse(f.barrier.tripped());
            assertThrows(IllegalStateException.class, () -> f.await(r, () -> fail("duplicate suffix")));
        }
    }
    @Test void readyBeforeWaitConsumesNextDrawWithoutPolling() {
        try (var f = new Fixture()) {
            var random = new Random(812L); assertEquals(-1270909246, random.nextInt());
            var r = f.reserve(CompletableFuture.completedFuture(null));
            assertEquals(DEPENDENCY_COMPLETE_OUTSIDE_POLL, r.state());
            f.await(r, () -> assertEquals(-1970933588, random.nextInt()));
            assertEquals(0, f.loop.polls); assertEquals(SETTLED, r.state()); assertEquals(0, r.completedPoll());
        }
    }
    @Test void nativeFalsePollStillCallsWaitForTasksBeforeObservingReadiness() {
        try (var f = new Fixture()) {
            var ready = new CompletableFuture<Void>(); var r = f.reserve(ready); f.loop.pollResult = false;
            f.loop.body = () -> ready.complete(null);
            f.await(r, () -> assertEquals(1, f.loop.waits));
            assertEquals(1, f.loop.polls); assertEquals(SETTLED, r.state());
        }
    }
    @Test void nestedPollWrapperInheritsTurnWithoutHoldingCompletionMonitor() {
        try (var f = new Fixture()) {
            var ready = new CompletableFuture<Void>(); var r = f.reserve(ready);
            f.loop.body = () -> f.loop.fence.poll(() -> {
                assertEquals(1, ManagedBlockPollTurnFence.currentPollId());
                Thread worker = Thread.ofPlatform().start(() -> ready.complete(null));
                try { worker.join(2000); } catch (InterruptedException failure) { throw new AssertionError(failure); }
                assertFalse(worker.isAlive()); assertEquals(DEPENDENCY_COMPLETE_IN_POLL, r.state()); return true;
            });
            f.await(r, () -> { }); assertEquals(1, r.completedPoll());
        }
    }
    @Test void successiveQueriesHaveMonotonicPollIdentityAndCannotOvertake() {
        try (var f = new Fixture()) {
            var resumed = new ArrayList<Integer>();
            for (int i = 1; i <= 3; i++) {
                int ordinal = i; var ready = new CompletableFuture<Void>();
                var r = f.loop.fence.reserve(ordinal, 1, ready, () -> true, f.dispatcher::requireNaturalSpawnOwnerFree);
                f.loop.body = () -> { assertEquals(ordinal, ManagedBlockPollTurnFence.currentPollId()); ready.complete(null); };
                f.await(r, () -> resumed.add(ordinal)); assertEquals(ordinal, r.returnedPoll());
            }
            assertEquals(List.of(1, 2, 3), resumed);
        }
    }
    @Test void multipleSimultaneousReservationsAreRejected() {
        try (var f = new Fixture(); var r = f.reserve(new CompletableFuture<>())) {
            assertThrows(IllegalStateException.class, () -> f.reserve(new CompletableFuture<>()));
            var other = new ManagedBlockPollTurnFence(Thread.currentThread());
            var ready = new CompletableFuture<Void>();
            r.retire(CANCELLED);
            // A second world also cannot overlap an active native wait on this thread.
            f.loop.body = () -> fail("retired reservation polled");
            f.await(r, () -> fail("retired resumed"));
            try (var fresh = other.reserve(1, 1, ready, () -> true, () -> { })) { assertEquals(WAITING_DEPENDENCY, fresh.state()); }
        }
    }
    @Test void resumeResolvesCurrentOwnerRatherThanCaptureRoute() {
        try (var f = new Fixture()) {
            var ready = new CompletableFuture<Void>(); var r = f.reserve(ready);
            f.loop.body = () -> { f.route(f.b, 2); ready.complete(null); };
            f.await(r, () -> assertEquals(f.b.id(), OwnershipContext.current().orElseThrow().region()));
            assertEquals(SETTLED, r.state()); assertFalse(f.barrier.tripped());
        }
    }
    @Test void staleIdentityRetiresBeforeClaimAndLateCompletionIsInert() {
        try (var f = new Fixture()) {
            var valid = new AtomicBoolean(true); var ready = new CompletableFuture<Void>();
            var r = f.loop.fence.reserve(1, 1, ready, valid::get, f.dispatcher::requireNaturalSpawnOwnerFree);
            f.loop.body = () -> valid.set(false);
            f.await(r, () -> fail("stale resumed")); ready.complete(null);
            assertEquals(STALE, r.state()); assertEquals(1, f.loop.polls);
        }
    }
    @Test void shutdownAfterReadinessRetiresWithoutAnotherBody() {
        try (var f = new Fixture()) {
            var ready = new CompletableFuture<Void>(); var r = f.reserve(ready);
            f.loop.body = () -> { ready.complete(null); f.scheduler.stop(); };
            f.await(r, () -> fail("shutdown resumed")); assertEquals(SHUTDOWN, r.state());
        }
    }
    @Test void dependencyFailureWaitsForPollReturnAndKeepsOriginalCause() {
        try (var f = new Fixture()) {
            var ready = new CompletableFuture<Void>(); var r = f.reserve(ready);
            var failure = new IllegalArgumentException("exact dependency failure"); var finishedPoll = new AtomicBoolean();
            f.loop.body = () -> { ready.completeExceptionally(failure); finishedPoll.set(true); };
            assertSame(failure, assertThrows(IllegalArgumentException.class, () -> f.await(r, () -> fail("failed resumed"))));
            assertTrue(finishedPoll.get()); assertSame(failure, r.failure()); assertEquals(FAILED, r.state());
        }
    }
    @Test void cancelledPollFinishesAndLateReturnCannotPromote() {
        try (var f = new Fixture()) {
            var ready = new CompletableFuture<Void>(); var r = f.reserve(ready); var tail = new AtomicBoolean();
            f.loop.body = () -> { r.retire(CANCELLED); ready.complete(null); tail.set(true); };
            f.await(r, () -> fail("cancelled resumed")); assertTrue(tail.get()); assertEquals(CANCELLED, r.state());
        }
    }
    @Test void preparationCompletesAfterNestedDrainAndBeforeNativeObservation() {
        try (var f = new Fixture()) {
            var raw = new AtomicBoolean(); var prepared = new CompletableFuture<Void>(); var r = f.reserve(prepared);
            var events = new ArrayList<String>();
            r.prepareAtObservation(() -> { if (raw.get() && !prepared.isDone()) { events.add("immutable-copy"); prepared.complete(null); } });
            f.loop.body = () -> { raw.set(true); events.add("raw"); events.add("remaining-poll"); };
            f.await(r, () -> events.add("suffix"));
            assertEquals(List.of("raw", "remaining-poll", "immutable-copy", "suffix"), events);
            assertEquals(1, r.completedPoll()); assertEquals(1, f.loop.polls);
        }
    }
    @Test void ordinaryDispatchAndNativePollAreUnchangedWithoutReservation() {
        try (var f = new Fixture()) {
            var order = new ArrayList<Integer>();
            f.dispatcher.submit(f.key, RegionalServiceKind.CHUNK_GAMEPLAY, ignored -> order.add(1));
            f.loop.body = () -> f.dispatcher.executeAndDrain(f.key, RegionalServiceKind.SAVE_ACKNOWLEDGEMENT, ignored -> order.add(2));
            assertTrue(f.loop.pollTask()); assertEquals(List.of(1, 2), order);
            assertEquals(0, ManagedBlockPollTurnFence.currentPollId()); assertFalse(f.barrier.tripped());
        }
    }
    @Test void reservationMonitorNeverEnclosesExternalCallbacksOrOwnerBodies() {
        try (var f = new Fixture()) {
            var ready = new CompletableFuture<Void>();
            var ref = new java.util.concurrent.atomic.AtomicReference<ManagedBlockPollTurnFence.Reservation>();
            var calls = new HashSet<String>();
            ConsumerCheck check = name -> {
                assertFalse(Thread.holdsLock(ref.get()), name + " retained reservation monitor");
                calls.add(name);
            };
            var r = f.loop.fence.reserve(1, 1, ready,
                () -> { check.run("valid"); return true; },
                () -> { check.run("owner-free"); f.dispatcher.requireNaturalSpawnOwnerFree(); });
            ref.set(r);
            r.prepareAtObservation(() -> check.run("prepare"))
                .shutdownWhen(() -> { check.run("shutdown"); return false; });
            f.loop.body = () -> { check.run("poll"); ready.complete(null); };
            f.await(r, () -> { check.run("suffix"); f.owner(); });
            assertEquals(Set.of("valid", "owner-free", "prepare", "shutdown", "poll", "suffix"), calls);
            assertEquals(SETTLED, r.state());
        }
    }
    private interface ConsumerCheck { void run(String name); }
}
