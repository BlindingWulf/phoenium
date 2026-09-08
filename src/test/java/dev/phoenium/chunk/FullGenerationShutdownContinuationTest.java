package dev.phoenium.chunk;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
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

            ChunkGenerationTask task = fixture.start(position, ChunkStatus.FULL);
            assertNotNull(fixture.producer, "the original SPAWN producer must be reached by the vanilla task");
            assertFalse(fixture.producer.isDone());
            CompletableFuture<?> continuation = task.runUntilWait();
            assertNotNull(continuation);
            assertFalse(continuation.isDone());

            GenerationChunkHolder holder = fixture.holder(position);
            var lower = holder.getAllFutures().stream()
                    .filter(entry -> entry.getFirst() == ChunkStatus.SPAWN)
                    .findFirst().orElseThrow();
            assertFalse(lower.getSecond().isDone(), "the real holder SPAWN future is still pending");
            assertTrue(((FullHolderEvidence) (Object) holder).phoenium$generationRefs().get() > 0,
                    "the original task still owns a generation retention reference");

            fixture.runtime.shutdown();
            assertFalse(continuation.isDone(), "stopping must retain the original task continuation until its producer settles");
            assertFalse(lower.getSecond().isDone(), "stopping must not strand or silently complete the original holder future");

            fixture.producer.complete(fixture.input(position));
            assertTrue(continuation.isDone(), "the original lower-stage completion must wake the original task");

            CompletionException failure = assertThrows(CompletionException.class, () -> {
                // The next real vanilla step is FULL. Before the lifecycle fix this
                // is the exact production failure seen during server shutdown.
                fixture.resume(task);
            });
            assertInstanceOf(ChunkProtocolException.class, failure.getCause());
            assertTrue(failure.getCause().getMessage().contains("generation registration after shutdown"));
            assertFalse(lower.getSecond().isCompletedExceptionally(), "the lower stage itself completed normally");
            assertTrue(fixture.runtime.measurements().get("generationHolders") > 0,
                    "the uncompleted original task must remain visible as a held generation");
        } finally {
            fixture.stop();
        }
    }
}
