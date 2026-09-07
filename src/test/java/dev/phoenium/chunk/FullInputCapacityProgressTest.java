package dev.phoenium.chunk;

import java.util.ArrayList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static dev.phoenium.chunk.FullInitializedInputBindingTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Finite oversized input has an acknowledged execution path, without multiplying expansion slots. */
final class FullInputCapacityProgressTest {
    @BeforeAll static void bootstrap() { FullProductionRegistrationTest.bootstrap(); }

    @Test void twoOversizedSavedPayloadsShareOneExpansionSlotAndBothProgressAfterAcknowledgement() throws Exception {
        var f = new FullRegistrationFixture(true,
                new FullInitializedInputs.Limits(2, 8, 8, 8, FullSavedInput.MAX_FACT_BYTES * 2));
        try {
            var display = tag(8, 8, 0); display.putString("id", "minecraft:text_display");
            display.put("text", StringTag.valueOf("finite text ".repeat(10_000)));
            saved(f, ChunkPos.ZERO, false, display);
            saved(f, new ChunkPos(1, 0), false, display.copy());
            f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain();
            f.start(new ChunkPos(1, 0), ChunkStatus.FULL); f.drain();
            var first = f.runtime.graph(f.holder(ChunkPos.ZERO));
            var second = f.runtime.graph(f.holder(new ChunkPos(1, 0)));
            assertEquals(FullInitializedInputs.State.SATISFIED, first.inputEvaluation().state());
            assertEquals(FullInitializedInputs.State.CAPACITY, second.inputEvaluation().state());
            assertNull(second.savedSnapshot(), "waiting demand must not retain another copied oversized payload");
            assertEquals((long) first.savedSnapshot().payloadBytes(), f.runtime.inputs.measurements().get("factBytes"));
            assertEquals(1L, f.runtime.inputs.measurements().get("expandedOperations"));
            var previous = first.inputReceipt();
            first.acknowledgeInputs(); f.drain();
            assertEquals(FullInitializedInputs.State.TERMINAL, first.validateInputs(first.finishing.stage(), previous).state());
            assertEquals(FullInitializedInputs.State.SATISFIED, second.inputEvaluation().state());
            assertEquals(1L, f.runtime.inputs.measurements().get("expandedOperations"));
            second.acknowledgeInputs(); f.drain();
            assertEquals(0L, f.runtime.inputs.measurements().get("factBytes"));
            assertEquals(0L, f.runtime.inputs.measurements().get("expandedOperations"));
        } finally { f.stop(); }
        assertCleanup(f);
    }

    @Test void actualOriginalFinishingPublishesPayloadLargerThanTheNormalFactAllowanceOnce() throws Exception {
        var f = new FullRegistrationFixture(true,
                new FullInitializedInputs.Limits(1, 1, 1, 1, FullSavedInput.MAX_FACT_BYTES));
        try {
            var roots = new CompoundTag[600];
            for (int i = 0; i < roots.length; i++) roots[i] = tag(8, 8, 1);
            saved(f, ChunkPos.ZERO, false, roots[0]);
            for (int i = 1; i < roots.length; i++) f.input(ChunkPos.ZERO).addEntity(roots[i]);
            f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain();
            var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
            assertEquals(600, graph.savedSnapshot().entities().size());
            assertTrue(graph.savedSnapshot().payloadBytes() > FullSavedInput.MAX_FACT_BYTES);
            assertEquals(1L, f.runtime.inputs.measurements().get("expandedOperations"));
            FullAuthoritativeFinishingTest.context(f); f.runtime.inputs.changed(); f.drain();
            assertTrue(graph.adjacent().isDone());
            assertFalse(graph.conversion().isCompletedExceptionally(), graph.conversion().toString());
            assertEquals(600, f.world.inserted.size());
            assertInstanceOf(ChunkStageOutcome.Applied.class, graph.finishing.outcome().join());
            assertInstanceOf(ChunkStageOutcome.Applied.class, graph.publication.outcome().join());
            assertEquals(1L, f.runtime.measurements().get("finishingCalls"));
            assertEquals(1L, f.runtime.measurements().get("publicationCalls"));
            assertEquals(0L, f.runtime.inputs.measurements().get("expandedOperations"));
            assertEquals(0L, f.runtime.inputs.measurements().get("factBytes"));
        } finally { f.stop(); FullFinishingServices.stopped(f.world.level.getServer()); }
        assertCleanup(f);
    }

    @Test void moreThan4096ValidSavedOccurrencesKeepTheirOriginalOrder() {
        var roots = new ArrayList<CompoundTag>();
        for (int i = 0; i < 4097; i++) roots.add(tag(8, 8, i));
        var input = new FullSavedInput();
        var saved = input.capture(roots);
        assertNull(saved.unmet()); assertEquals(4097, saved.entities().size());
        assertEquals("root[4096]", saved.entities().getLast().path());
        assertEquals(4096, saved.entities().getLast().age());
        assertTrue(saved.payloadBytes() > FullSavedInput.MAX_FACT_BYTES);
        input.discardSnapshot(); assertNull(input.snapshot()); assertNull(input.pathOf(roots.getLast()));
    }
}
