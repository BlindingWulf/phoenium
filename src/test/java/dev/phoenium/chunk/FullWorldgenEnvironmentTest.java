package dev.phoenium.chunk;

import dev.phoenium.ownership.OwnershipContext;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static dev.phoenium.chunk.FullDecisionWitnessSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real service reservations; the deterministic queue represents the original chunk event loop. */
final class FullWorldgenEnvironmentTest {
    @BeforeAll static void bootstrap() { FullProductionRegistrationTest.bootstrap(); }
    private static FullServiceOperationFixture fixture() throws Exception {
        Thread owner = Thread.currentThread();
        return new FullServiceOperationFixture(stub(MinecraftServer.class, List.of(
                new Answer(MinecraftServer.class.getMethod("getRunningThread"), ignored -> owner))));
    }

    @Test void backgroundMoonReadWaitsUnclaimedThenRunsOriginalOnceInTheNamedOwnerFreeTurn() throws Exception {
        try (var f = fixture()) {
            var world = f.world(); var queue = new LinkedBlockingQueue<Runnable>(); var calls = new AtomicInteger();
            var reservation = f.service.tryReserve(f.stage, f.holder, f.worlds, 1); assertNotNull(reservation);
            CompletableFuture<Float> response;
            Runnable delivery;
            try (reservation) {
                response = CompletableFuture.supplyAsync(() -> FullWorldgenEnvironment.moonBrightness(world, queue::add, () -> {
                    assertTrue(f.server.isSameThread()); assertTrue(OwnershipContext.current().isEmpty());
                    FullFinishingServices.requireWorld(world, FullFinishingServices.Kind.ENVIRONMENT);
                    calls.incrementAndGet(); return world.getMoonBrightness(BlockPos.ZERO);
                }));
                delivery = queue.poll(5, TimeUnit.SECONDS); assertNotNull(delivery);
                assertEquals(0, calls.get()); assertFalse(response.isDone());
                assertEquals(1L, f.service.measurements().get("environmentRequests"));
            }
            delivery.run();
            float observed = response.get(5, TimeUnit.SECONDS);
            float original;
            try (var turn = f.service.serial(FullFinishingServices.Phase.SERVER_TASK)) { original = world.getMoonBrightness(BlockPos.ZERO); }
            assertEquals(original, observed); assertEquals(1, calls.get());
            delivery.run(); assertEquals(1, calls.get(), "duplicate delivery cannot replay the original getter");
            assertEquals(0L, f.service.measurements().get("environmentRequests"));
        }
    }

    @Test void originalGetterFailureReturnsItsExactCauseAfterReleasingTheTurn() throws Exception {
        try (var f = fixture()) {
            var world = f.world(); var queue = new LinkedBlockingQueue<Runnable>(); var calls = new AtomicInteger();
            var exact = new IllegalStateException("original moon layer failed");
            var response = CompletableFuture.supplyAsync(() -> FullWorldgenEnvironment.moonBrightness(world, queue::add, () -> {
                calls.incrementAndGet(); throw exact;
            }));
            var delivery = queue.poll(5, TimeUnit.SECONDS); assertNotNull(delivery); delivery.run();
            assertSame(exact, assertThrows(ExecutionException.class, () -> response.get(5, TimeUnit.SECONDS)).getCause());
            assertEquals(1, calls.get()); assertEquals(0L, f.service.measurements().get("active"));
            assertEquals(0L, f.service.measurements().get("environmentRequests"));
        }
    }

    @Test void executorRejectionAcknowledgesItsRequestWithoutRunningTheGetter() throws Exception {
        try (var f = fixture()) {
            var world = f.world(); var calls = new AtomicInteger(); var exact = new RejectedExecutionException("original chunk executor rejected");
            var response = CompletableFuture.supplyAsync(() -> FullWorldgenEnvironment.moonBrightness(world,
                    command -> { throw exact; }, () -> { calls.incrementAndGet(); return 0F; }));
            assertSame(exact, assertThrows(ExecutionException.class, () -> response.get(5, TimeUnit.SECONDS)).getCause());
            assertEquals(0, calls.get()); assertEquals(0L, f.service.measurements().get("environmentRequests"));
        }
    }

    @Test void shutdownSettlesQueuedWorldgenAndLateDeliveryCannotReviveIt() throws Exception {
        try (var f = fixture()) {
            var world = f.world(); var queue = new LinkedBlockingQueue<Runnable>(); var calls = new AtomicInteger();
            var response = CompletableFuture.supplyAsync(() -> FullWorldgenEnvironment.moonBrightness(world, queue::add,
                    () -> { calls.incrementAndGet(); return 0F; }));
            var delivery = queue.poll(5, TimeUnit.SECONDS); assertNotNull(delivery);
            FullFinishingServices.stopped(f.server);
            assertInstanceOf(CancellationException.class, assertThrows(ExecutionException.class, () -> response.get(5, TimeUnit.SECONDS)).getCause());
            delivery.run(); assertEquals(0, calls.get()); assertEquals(0L, f.service.measurements().get("environmentRequests"));
            assertThrows(ChunkProtocolException.class, () -> FullFinishingServices.forServer(f.server));
        }
    }

    @Test void aGameplayClaimCannotUseTheWorldgenWaitOrHideItsBarrierInAVanillaCatch() throws Exception {
        try (var f = fixture()) {
            var world = f.world(); var calls = new AtomicInteger();
            assertThrows(ChunkProtocolException.class, () -> f.full(claim -> {
                assertThrows(ChunkProtocolException.class, () -> FullWorldgenEnvironment.moonBrightness(world, Runnable::run,
                        () -> { calls.incrementAndGet(); return 0F; }));
            }));
            assertEquals(0, calls.get()); assertFalse(f.leases.hasActiveLease());
            assertEquals(0L, f.service.measurements().get("environmentRequests"));
        }
    }
}
