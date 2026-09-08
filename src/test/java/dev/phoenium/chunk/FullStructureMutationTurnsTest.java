package dev.phoenium.chunk;

import dev.phoenium.topology.RoutingKey;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.GeneratingChunkMap;
import net.minecraft.server.level.ChunkGenerationTask;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static dev.phoenium.chunk.FullProductionRegistrationTest.apply;
import static org.junit.jupiter.api.Assertions.*;

/** Original winning holder -> registered generation producer -> original holder result.
 * Controlled generation outputs isolate the aliased structure writer boundary. */
class FullStructureMutationTurnsTest {
    @BeforeAll static void bootstrap() { FullProductionRegistrationTest.bootstrap(); }

    @Test void aReferencedStartEightChunksAwayDefersTheOriginalWriterUntilTheReadExits() throws Exception {
        var f = new FullRegistrationFixture(true);
        var cache = cache(f); var step = ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.FEATURES);
        prepareParent(f, cache); int initialCalls = f.mapCalls;
        f.delayedPosition = ChunkPos.ZERO; f.delayedStatus = ChunkStatus.FEATURES;
        var referenced = cache.get(8, 0); var outside = f.holder(new ChunkPos(9, 0));
        var operation = operation(f);
        var read = f.runtime.structureMutations.tryRead(operation, List.of(referenced)); assertNotNull(read);
        var dispatched = new CompletableFuture<CompletableFuture<?>>();
        var worker = Thread.ofPlatform().name("controlled-original-generation").start(() -> {
            try { dispatched.complete(apply(cache.get(0, 0), step, f, cache)); }
            catch (Throwable failure) { dispatched.completeExceptionally(failure); }
        });
        try {
            awaitWaiting(f, dispatched);
            assertEquals(initialCalls, f.mapCalls, "the original FEATURES body has not started");
            assertFalse(dispatched.isDone());
            assertNull(f.runtime.structureMutations.tryRead(operation, List.of(referenced)),
                    "a waiting original writer gets progress before another overlapping read");
            try (var independent = f.runtime.structureMutations.tryRead(operation, List.of(outside))) {
                assertNotNull(independent, "unrelated holders are not serialized behind the structure read");
            }
            read.require(operation, referenced); read.close();
            var originalResult = dispatched.get(5, TimeUnit.SECONDS);
            assertEquals(initialCalls + 1, f.mapCalls); assertFalse(originalResult.isDone()); assertNotNull(f.producer);
            assertNull(f.runtime.structureMutations.tryRead(operation, List.of(referenced)),
                    "the writer stays active until the original producer future settles");
            f.producer.complete(f.input(ChunkPos.ZERO)); originalResult.get(5, TimeUnit.SECONDS);
            try (var next = f.runtime.structureMutations.tryRead(operation, List.of(referenced))) { assertNotNull(next); }
            assertThrows(ChunkProtocolException.class, () -> read.require(operation, referenced));
        } finally {
            read.close();
            if (f.producer != null) f.producer.complete(f.input(ChunkPos.ZERO));
            worker.join(Duration.ofSeconds(5)); assertFalse(worker.isAlive());
            cache.forEach(f::releaseGeneration); f.stop();
        }
        assertTrue(f.runtime.structureMutations.measurements().values().stream().allMatch(value -> value == 0));
    }

    @Test void stoppingRetainsTheAlreadyRegisteredOriginalContinuationUntilItsReadAcknowledgement() throws Exception {
        var f = new FullRegistrationFixture(true); var cache = cache(f);
        prepareParent(f, cache); int initialCalls = f.mapCalls;
        var step = ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.FEATURES);
        var operation = operation(f); var referenced = cache.get(8, 0);
        var read = f.runtime.structureMutations.tryRead(operation, List.of(referenced)); assertNotNull(read);
        var dispatched = new CompletableFuture<CompletableFuture<?>>();
        var worker = Thread.ofPlatform().name("controlled-stopping-generation").start(() -> {
            try { dispatched.complete(apply(cache.get(0, 0), step, f, cache)); }
            catch (Throwable failure) { dispatched.completeExceptionally(failure); }
        });
        try {
            awaitWaiting(f, dispatched); f.runtime.shutdown();
            assertFalse(dispatched.isDone()); assertEquals(initialCalls, f.mapCalls);
            assertNull(f.runtime.structureMutations.tryRead(operation, List.of(referenced)));
            read.require(operation, referenced); // An in-flight read survives its source's terminal fence.
            read.close();
            var original = dispatched.get(5, TimeUnit.SECONDS); assertTrue(original.isDone());
            assertFalse(original.isCompletedExceptionally()); assertEquals(initialCalls + 1, f.mapCalls);
            assertNotNull(((FullHolderEvidence) referenced).phoenium$generationSaveSync());
        } finally {
            read.close(); worker.join(Duration.ofSeconds(5)); assertFalse(worker.isAlive());
            cache.forEach(f::releaseGeneration); f.stop();
        }
        assertTrue(f.runtime.structureMutations.measurements().values().stream().allMatch(value -> value == 0));
        assertEquals(0L, f.runtime.measurements().get("generationHolders"));
    }

    @Test void anObservationThreadCannotWaitForAReadItMustItselfAcknowledge() throws Exception {
        var f = new FullRegistrationFixture(true); var cache = cache(f);
        var read = f.runtime.structureMutations.tryRead(operation(f), List.of(cache.get(8, 0))); assertNotNull(read);
        try (read; var writer = f.runtime.structureMutations.beginWriter(cache.get(0, 0),
                ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.FEATURES), cache)) {
            assertNotNull(writer);
            assertTrue(assertThrows(ChunkProtocolException.class, writer::awaitReady).getMessage().contains("cannot wait"));
            assertEquals(0, f.mapCalls);
        } finally { cache.forEach(f::releaseGeneration); f.stop(); }
        assertTrue(f.runtime.structureMutations.measurements().values().stream().allMatch(value -> value == 0));
    }

    @Test void aDeferredOriginalSynchronousExceptionKeepsItsIdentityAndDoesNotBecomeAFutureFailure() throws Exception {
        var f = new FullRegistrationFixture(true); var cache = cache(f); prepareParent(f, cache);
        var expected = new IllegalStateException("original synchronous feature failure");
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        class FailingMap implements GeneratingChunkMap, FullGenerationSource {
            public FullRegistrationRuntime phoenium$fullRegistration() { return f.runtime; }
            public ChunkGenerationTask scheduleGenerationTask(ChunkStatus status, ChunkPos position) { return f.scheduleGenerationTask(status, position); }
            public void runGenerationTasks() { f.runGenerationTasks(); }
            public GenerationChunkHolder acquireGeneration(long position) { return f.acquireGeneration(position); }
            public void releaseGeneration(GenerationChunkHolder holder) { f.releaseGeneration(holder); }
            public CompletableFuture<ChunkAccess> applyStep(GenerationChunkHolder holder, ChunkStep step, StaticCache2D<GenerationChunkHolder> originalCache) {
                assertTrue(dev.phoenium.ownership.OwnershipContext.current().isEmpty());
                calls.incrementAndGet(); throw expected;
            }
        }
        var read = f.runtime.structureMutations.tryRead(operation(f), List.of(cache.get(8, 0))); assertNotNull(read);
        var thrown = new CompletableFuture<Throwable>();
        var worker = Thread.ofPlatform().name("controlled-throwing-generation").start(() -> {
            try {
                apply(cache.get(0, 0), ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.FEATURES), new FailingMap(), cache);
                thrown.completeExceptionally(new AssertionError("original synchronous exception was converted to a returned future"));
            } catch (java.lang.reflect.InvocationTargetException failure) { thrown.complete(failure.getCause()); }
            catch (Throwable failure) { thrown.completeExceptionally(failure); }
        });
        try {
            awaitWaiting(f, thrown); assertEquals(0, calls.get()); read.close();
            assertSame(expected, thrown.get(5, TimeUnit.SECONDS)); assertEquals(1, calls.get());
        } finally {
            read.close(); worker.join(Duration.ofSeconds(5)); assertFalse(worker.isAlive());
            cache.forEach(f::releaseGeneration); f.stop();
        }
        assertTrue(f.runtime.structureMutations.measurements().values().stream().allMatch(value -> value == 0));
    }

    private static void awaitWaiting(FullRegistrationFixture f, CompletableFuture<?> dispatched) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while ((f.runtime.structureMutations.measurements().get("waitingWriters") == 0 || !registeredFeature(f))
                && !dispatched.isDone() && System.nanoTime() < deadline)
            Thread.sleep(1);
        if (dispatched.isCompletedExceptionally()) dispatched.get(5, TimeUnit.SECONDS);
        assertTrue(f.runtime.structureMutations.measurements().get("waitingWriters") > 0);
        assertTrue(registeredFeature(f), "terminal tests begin after the original winning producer is registered");
    }
    private static boolean registeredFeature(FullRegistrationFixture f) throws Exception {
        f.services.custody().gate().lock(); try {
            var generations = (java.util.Map<?, ?>) FullDecisionWitnessSupport.field(f.runtime, FullRegistrationRuntime.class, "generations");
            var generation = generations.get(f.holder(ChunkPos.ZERO));
            return generation != null && ((java.util.Map<?, ?>) FullDecisionWitnessSupport.field(generation, generation.getClass(), "steps"))
                    .containsKey(ChunkStatus.FEATURES);
        } finally { f.services.custody().gate().unlock(); }
    }
    private static void prepareParent(FullRegistrationFixture f, StaticCache2D<GenerationChunkHolder> cache) throws Exception {
        for (var status : ChunkStatus.getStatusList()) {
            if (!status.isBefore(ChunkStatus.FEATURES)) break;
            apply(cache.get(0, 0), ChunkPyramid.LOADING_PYRAMID.getStepTo(status), f, cache).get(5, TimeUnit.SECONDS);
        }
    }
    private static StaticCache2D<GenerationChunkHolder> cache(FullRegistrationFixture f) {
        return StaticCache2D.create(0, 0, 8, (x, z) -> f.acquireGeneration(ChunkPos.pack(x, z)));
    }
    private static ChunkCustodyModel.StageId operation(FullRegistrationFixture f) {
        var life = new ChunkCustodyModel.LifecycleId("structure-turn-test", 1, f.worldId, new RoutingKey(f.worldId, 0, 0), 1);
        return new ChunkCustodyModel.StageId(new ChunkCustodyModel.WorkId(life, 1), ChunkStageKind.FULL_FINISH, "FULL", 0);
    }
}
