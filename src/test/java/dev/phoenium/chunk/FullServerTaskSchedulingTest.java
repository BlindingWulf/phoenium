package dev.phoenium.chunk;

import dev.phoenium.core.WorldId;
import dev.phoenium.ownership.*;
import dev.phoenium.region.RegionId;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.util.thread.BlockableEventLoop;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static dev.phoenium.chunk.FullDecisionWitnessSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** The actual vanilla event-loop/CompletableFuture inline dispatch that lost spawn completion. */
final class FullServerTaskSchedulingTest {
    @BeforeAll static void bootstrap() { FullProductionRegistrationTest.bootstrap(); }
    private static MinecraftServer server(AtomicBoolean stopped) throws Exception {
        Thread owner = Thread.currentThread();
        return stub(MinecraftServer.class, List.of(
                new Answer(MinecraftServer.class.getMethod("getRunningThread"), ignored -> owner),
                new Answer(MinecraftServer.class.getMethod("isStopped"), ignored -> stopped.get())));
    }
    private static void owned(Runnable body) {
        RegionId region = new RegionId(new WorldId("server-task-test"), "publication");
        var manager = new RegionLeaseManager(region);
        try (var lease = manager.acquire();
             var owner = OwnershipContext.enter(region, lease.epoch(), OperationCategory.OWNER_TASK)) { body.run(); }
        assertFalse(manager.hasActiveLease());
    }
    private static final class OriginalLoop extends BlockableEventLoop<TickTask> {
        final Thread owner = Thread.currentThread();
        final MinecraftServer server;
        final List<TickTask> queued = new ArrayList<>(), executed = new ArrayList<>();
        OriginalLoop(MinecraftServer server) { super("FULL server task regression", true); this.server = server; }
        @Override public TickTask wrapRunnable(Runnable command) { return new TickTask(0, command); }
        @Override protected boolean shouldRun(TickTask task) { return true; }
        @Override protected Thread getRunningThread() { return owner; }
        @Override protected void doRunTask(TickTask task) {
            FullFinishingServices.runServerTask(server, () -> { queued.add(task); schedule(task); },
                    () -> { executed.add(task); super.doRunTask(task); });
        }
        boolean pollOne() { return pollTask(); }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void originalAsyncDependentWaitsForOwnerExitThenRunsTheSameTaskExactlyOnce(boolean serialParent) throws Exception {
        MinecraftServer server = server(new AtomicBoolean());
        var service = FullFinishingServices.forServer(server); var loop = new OriginalLoop(server);
        var published = new CompletableFuture<String>(); var spawn = new CompletableFuture<String>();
        var calls = new AtomicInteger();
        var dependent = published.whenCompleteAsync((value, failure) -> {
            assertTrue(OwnershipContext.current().isEmpty());
            FullFinishingServices.requireServer(server, FullFinishingServices.Kind.ENVIRONMENT);
            calls.incrementAndGet(); spawn.complete(value);
        }, loop);
        try (var parent = serialParent ? service.serial(FullFinishingServices.Phase.SERVER_TICK) : null) {
            owned(() -> {
                assertTrue(published.complete("original FULL"));
                assertFalse(dependent.isDone(), "the discarded async dependent must not fail before its body can run");
                assertFalse(spawn.isDone()); assertEquals(1, loop.getPendingTasksCount());
                assertTrue(loop.executed.isEmpty());
            });
            assertTrue(loop.pollOne());
            assertEquals("original FULL", spawn.join()); assertEquals("original FULL", dependent.join());
            assertEquals(1, calls.get()); assertEquals(1, loop.executed.size());
            assertSame(loop.queued.getFirst(), loop.executed.getFirst());
            assertFalse(loop.pollOne()); assertEquals(serialParent ? 1L : 0L, service.measurements().get("active"));
        } finally { FullFinishingServices.stopped(server); }
    }

    @Test void ownerFreeOriginalExecutorStillRunsInlineAndPreservesOriginalFailure() throws Exception {
        MinecraftServer server = server(new AtomicBoolean());
        var service = FullFinishingServices.forServer(server); var loop = new OriginalLoop(server);
        var exact = new IllegalStateException("original task failure");
        try {
            var result = CompletableFuture.completedFuture("ready").thenApplyAsync(value -> {
                assertTrue(OwnershipContext.current().isEmpty());
                FullFinishingServices.requireServer(server, FullFinishingServices.Kind.CLOCK);
                throw exact;
            }, loop);
            assertSame(exact, assertThrows(CompletionException.class, result::join).getCause());
            assertEquals(1, loop.executed.size()); assertTrue(loop.queued.isEmpty());
            assertEquals(0, loop.getPendingTasksCount()); assertEquals(0L, service.measurements().get("active"));
        } finally { FullFinishingServices.stopped(server); }
    }

    @Test void originalQueueRejectionReachesTheDependentWithoutExecutingItsBody() throws Exception {
        MinecraftServer server = server(new AtomicBoolean());
        var service = FullFinishingServices.forServer(server); var exact = new RejectedExecutionException("original queue rejected");
        var calls = new AtomicInteger(); var source = new CompletableFuture<String>();
        var dependent = source.thenApplyAsync(value -> { calls.incrementAndGet(); return value; }, command ->
                FullFinishingServices.runServerTask(server, () -> { throw exact; }, command));
        try {
            owned(() -> source.complete("FULL"));
            assertSame(exact, assertThrows(CompletionException.class, dependent::join).getCause());
            assertEquals(0, calls.get()); assertEquals(0L, service.measurements().get("active"));
        } finally { FullFinishingServices.stopped(server); }
    }

    @Test void stoppedOwnedSubmissionCannotAcceptAContinuationThatItsQueueWillNeverDrain() throws Exception {
        MinecraftServer server = server(new AtomicBoolean(true));
        var queued = new AtomicInteger(); var calls = new AtomicInteger();
        owned(() -> assertThrows(RejectedExecutionException.class, () -> FullFinishingServices.runServerTask(server,
                queued::incrementAndGet, calls::incrementAndGet)));
        assertEquals(0, queued.get()); assertEquals(0, calls.get());
        FullFinishingServices.stopped(server);
    }

    @Test void originalTerminalProducerAcknowledgementStillRunsWithoutResurrectingServices() throws Exception {
        MinecraftServer server = server(new AtomicBoolean(true));
        FullFinishingServices.stopped(server);
        var queued = new AtomicInteger(); var calls = new AtomicInteger();
        CompletableFuture.runAsync(() -> FullFinishingServices.runServerTask(server, queued::incrementAndGet, () -> {
            assertFalse(server.isSameThread()); assertTrue(OwnershipContext.current().isEmpty());
            calls.incrementAndGet();
        })).get(5, TimeUnit.SECONDS);
        assertEquals(0, queued.get()); assertEquals(1, calls.get());
        assertThrows(ChunkProtocolException.class, () -> FullFinishingServices.forServer(server));
    }
}
