package dev.phoenium.chunk;

import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ChunkGenerationTask;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static dev.phoenium.chunk.FullDecisionWitnessSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Reproduces the vanilla generation task crossing the FULL runtime's stopping
 * boundary while an earlier, real lower-status future is still pending.
 */
final class FullGenerationShutdownContinuationTest {
    @BeforeAll static void bootstrap() { FullProductionRegistrationTest.bootstrap(); }

    @Test void lowerStageContinuationDoesNotLoseItsOriginalHolderFutureAtShutdown() throws Exception {
        var fixture = new FullRegistrationFixture(true);
        try {
            ChunkPos position = ChunkPos.ZERO;
            fixture.delayedPosition = position;
            fixture.delayedStatus = ChunkStatus.SPAWN;

            var holder = fixture.holder(position);
            holder.eligibility(33, fixture.fixtureMap);
            var publicFull = holder.scheduleChunkGenerationTask(ChunkStatus.FULL, fixture.fixtureMap);
            assertFalse(publicFull.isDone());
            fixture.runGenerationTasks();
            ChunkGenerationTask task = fixture.tasks.iterator().next();
            assertNotNull(fixture.producer, "the original SPAWN producer must be reached by the vanilla task");
            assertFalse(fixture.producer.isDone());
            CompletableFuture<?> continuation = task.runUntilWait();
            assertNotNull(continuation);
            assertFalse(continuation.isDone());

            assertTrue(((FullHolderEvidence) (Object) holder).phoenium$generationRefs().get() > 0,
                    "the original task still owns a generation retention reference");

            fixture.runtime.shutdown();
            assertFalse(continuation.isDone(), "stopping must retain the original task continuation until its producer settles");
            assertFalse(publicFull.isDone(), "stopping must not strand or silently complete the original public FULL future");

            fixture.producer.complete(fixture.input(position));
            assertTrue(continuation.isDone(), "the original lower-stage completion must wake the original task");

            ChunkProtocolException failure = assertThrows(ChunkProtocolException.class, () -> {
                // The next real vanilla step is FULL. Before the lifecycle fix this
                // is the exact production failure seen during server shutdown.
                fixture.resume(task);
            });
            assertTrue(failure.getMessage().contains("generation registration after shutdown"));
            assertFalse(fixture.producer.isCompletedExceptionally(), "the lower stage itself completed normally");
            assertFalse(publicFull.isDone(), "the pre-existing public FULL future is stranded by the old ordering");
            assertTrue(fixture.runtime.measurements().get("generationHolders") > 0,
                    "the uncompleted original task must remain visible as a held generation");
        } finally {
            fixture.stop();
        }
    }

    @Test void originalStopDrainsLowerContinuationBeforeAcknowledgingFullRuntime() throws Exception {
        var fixture = new FullRegistrationFixture(true);
        var server = fixture.world.level.getServer();
        try {
            ChunkPos position = ChunkPos.ZERO;
            fixture.delayedPosition = position;
            fixture.delayedStatus = ChunkStatus.SPAWN;
            var holder = fixture.holder(position);
            holder.eligibility(33, fixture.fixtureMap);
            var publicFull = holder.scheduleChunkGenerationTask(ChunkStatus.FULL, fixture.fixtureMap);
            fixture.runGenerationTasks();
            ChunkGenerationTask task = fixture.tasks.iterator().next();
            CompletableFuture<?> continuation = task.runUntilWait();
            assertNotNull(continuation);
            assertFalse(continuation.isDone());
            assertNotNull(fixture.producer);

            FullServerShutdown.run(server, () -> {
                // This is the original lower-stage completion and the original
                // ChunkGenerationTask continuation. It must run before the runtime
                // is acknowledged as stopped.
                fixture.producer.complete(fixture.input(position));
                assertTrue(continuation.isDone(), "the real lower-stage continuation must settle before runtime acknowledgement");
                assertDoesNotThrow(continuation::join);
                fixture.resume(task);
                assertNotNull(fixture.runtime.graph(holder), "the resumed task must register its next winning FULL step");
                fixture.drain();
            }, fixture.runtime::shutdown);

            assertTrue(publicFull.isDone(), "the original public FULL future must settle during terminal acknowledgement");
            assertSame(GenerationChunkHolder.UNLOADED_CHUNK, publicFull.join(),
                    "terminal draining must use vanilla's cancellation result, never a fallback chunk");
            // The original stop body has drained the continuation; terminal
            // acknowledgement then releases the task's final vanilla claim.
            task.markForCancellation();
            fixture.resume(task);
            assertEquals(0, fixture.runtime.measurements().get("returnedIndex"));
            assertEquals(0, fixture.runtime.measurements().get("graphs"));
            assertEquals(0, fixture.runtime.measurements().get("generationHolders"));
            assertEquals(0, ((FullHolderEvidence) (Object) holder).phoenium$generationRefs().get(),
                    "terminal draining must release the original holder generation reference");
        } finally {
            fixture.stop();
        }
    }
}
