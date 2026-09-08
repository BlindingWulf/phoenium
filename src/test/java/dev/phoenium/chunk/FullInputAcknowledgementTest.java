package dev.phoenium.chunk;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static dev.phoenium.chunk.FullInitializedInputBindingTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual original generation references and independently retained publication consumers. */
final class FullInputAcknowledgementTest {
    @BeforeAll static void bootstrap() { FullProductionRegistrationTest.bootstrap(); }

    @Test void publicationListenersAreIndependentAndKeepTheFirstFailure() throws Exception {
        var changes = new FullInputNotifications();
        var calls = new java.util.ArrayList<String>();
        var first = new IllegalStateException("first listener failed");
        var second = new AssertionError("second listener failed");
        try (var a = changes.subscribe(() -> { calls.add("a"); throw first; });
             var b = changes.subscribe(() -> { calls.add("b"); throw second; });
             var c = changes.subscribe(() -> calls.add("c"))) {
            assertSame(first, assertThrows(IllegalStateException.class, changes::published));
            assertEquals(List.of("a", "b", "c"), calls);
            assertArrayEquals(new Throwable[] {second}, first.getSuppressed());
        }
        assertEquals(0, changes.retainedListeners());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void publicationRetirementFailureStillReleasesEveryResidentRecord(boolean reentrant) throws Exception {
        var f = new FullRegistrationFixture(true);
        AutoCloseable listener = null;
        try {
            var positions = List.of(new ChunkPos(1, 0), new ChunkPos(0, 1), new ChunkPos(1, 1));
            for (var position : positions) { f.startBaseline(position); f.runGenerationTasks(); }
            assertEquals(3L, f.runtime.published.measurements().get("records"));
            var first = new IllegalStateException("first resident retirement notification failed");
            var calls = new AtomicInteger();
            listener = f.runtime.published.changes().subscribe(() -> {
                if (calls.incrementAndGet() == 1) {
                    if (reentrant) f.runtime.shutdown();
                    throw first;
                }
            });
            assertSame(first, assertThrows(IllegalStateException.class, f.runtime::shutdown));
            assertEquals(3, calls.get(), "each independent resident retires despite the first failed notification");
            assertEquals(0L, f.runtime.published.measurements().get("records"));
            assertEquals(0, ((FullInputView) f.fixtureMap).phoenium$inputChanges().retainedListeners(),
                    "runtime subscriptions detach even when publication retirement reports a failure");
            for (var position : positions)
                assertNull(((FullHolderEvidence) (Object) f.holder(position)).phoenium$publication());
            assertCleanup(f);
        } finally { if (listener != null) listener.close(); f.stop(); }
        assertCleanup(f);
    }

    @Test void holderRetirementStillAcknowledgesItsGraphAfterPublicationNotificationFails() throws Exception {
        var f = new FullRegistrationFixture(true);
        AutoCloseable listener = null;
        try {
            saved(f, ChunkPos.ZERO, false, tag(8, 8, 1));
            f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain();
            var holder = f.holder(ChunkPos.ZERO); var graph = f.runtime.graph(holder);
            assertNotNull(graph.inputReceipt()); assertFalse(graph.conversion().isDone());
            var exact = new IllegalStateException("retiring holder publication listener failed");
            var notices = new AtomicInteger();
            listener = f.runtime.published.changes().subscribe(() -> {
                if (notices.incrementAndGet() == 1) throw exact;
            });
            assertSame(exact, assertThrows(IllegalStateException.class, () -> f.runtime.retire(holder)));
            assertTrue(graph.conversion().isDone(), "retirement must finish its terminal graph acknowledgement before reporting listener failure");
            assertTrue(graph.conversion().isCompletedExceptionally());
            assertSame(net.minecraft.server.level.GenerationChunkHolder.UNLOADED_CHUNK, graph.adjacent().join());
            assertNull(graph.inputReceipt());
            assertEquals(0L, f.runtime.inputs.measurements().get("consumers"));
            assertEquals(0L, f.runtime.inputs.measurements().get("factBytes"));
            assertEquals(0L, f.runtime.measurements().get("finishingCalls"));
            assertFalse(f.barrier.tripped());
            f.runtime.retire(holder);
        } finally { if (listener != null) listener.close(); f.stop(); }
        assertCleanup(f);
    }

    @Test void failedPublicationNotificationCannotAbandonLaterOperationConsumers() throws Exception {
        var f = new FullRegistrationFixture(true);
        var neighbors = List.of(new ChunkPos(1, 0), new ChunkPos(0, 1), new ChunkPos(1, 1));
        AutoCloseable listener = null;
        try {
            for (var position : neighbors) { f.startBaseline(position); f.runGenerationTasks(); }
            saved(f, ChunkPos.ZERO, false, tag(15.8, 15.8, 24000));
            f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain();
            var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
            assertEquals(FullInitializedInputs.State.SATISFIED, graph.inputEvaluation().state());
            assertEquals(3L, f.runtime.published.measurements().get("uses"));
            var borrowed = neighbors.stream().map(position -> ((FullHolderEvidence) (Object) f.holder(position)).phoenium$generationRefs().get()).toList();
            var notices = new AtomicInteger(); var exact = new IllegalStateException("first publication acknowledgement notification failed");
            listener = f.runtime.published.changes().subscribe(() -> {
                if (notices.incrementAndGet() == 1) throw exact;
            });
            Throwable failure = assertThrows(RuntimeException.class, graph::acknowledgeInputs);
            assertEquals(0L, f.runtime.published.measurements().get("uses"), "later actual pins must still release after the first notification fails");
            assertSame(exact, failure);
            assertEquals(3, notices.get());
            assertEquals(0L, f.runtime.inputs.measurements().get("consumers"));
            assertEquals(0L, f.runtime.inputs.measurements().get("factBytes"));
            assertEquals(1L, f.runtime.inputs.measurements().get("pendingNotifications"));
            for (int i = 0; i < neighbors.size(); i++)
                assertEquals(borrowed.get(i) - 1, ((FullHolderEvidence) (Object) f.holder(neighbors.get(i))).phoenium$generationRefs().get(),
                        "each input releases its own borrow while the original generation cache keeps its independent references");
            graph.acknowledgeInputs(); assertEquals(3, notices.get());
        } finally { if (listener != null) listener.close(); f.stop(); }
        assertCleanup(f);
    }

    @Test void biomeBorrowAcknowledgesTheOriginalReferenceWhenItsCommittedSaveCallbackFails() throws Exception {
        var f = new FullRegistrationFixture(true);
        var position = new ChunkPos(1, 0);
        try {
            f.startBaseline(position); f.runGenerationTasks();
            var holder = f.holder(position); var hooks = (FullHolderEvidence) (Object) holder;
            var evidence = FullBiomeInputs.capture(holder); assertNotNull(evidence);
            var target = new FullInitializedInputs.Target(position, Set.of(FullInitializedInputs.Purpose.ENVIRONMENT),
                    FullInitializedInputs.Availability.BIOMES, holder, null, null, null, null, evidence.slot(), null,
                    null, ChunkStatus.BIOMES, null, holder.getTicketLevel(), null, evidence);
            var exact = new IllegalStateException("original save dependency failed after reference increment");
            holder.afterSave = () -> { throw exact; };
            assertSame(exact, assertThrows(IllegalStateException.class, () -> FullBiomeInputs.retain(f.runtime, target)));
            assertEquals(0, hooks.phoenium$generationRefs().get(), "the original committed borrow must be acknowledged");
            assertTrue(hooks.phoenium$generationSaveSync().isDone());
            holder.afterSave = () -> { };
            var use = FullBiomeInputs.retain(f.runtime, target); assertNotNull(use);
            assertEquals(1, hooks.phoenium$generationRefs().get());
            use.close(); use.close(); assertEquals(0, hooks.phoenium$generationRefs().get());
        } finally { f.holder(position).afterSave = () -> { }; f.stop(); }
        assertCleanup(f);
    }
}
