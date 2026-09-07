package dev.phoenium.chunk;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.storage.CommandStorage;
import net.minecraft.world.level.storage.SavedDataStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static dev.phoenium.chunk.FullDecisionWitnessSupport.*;
import static dev.phoenium.chunk.FullInitializedInputBindingTest.*;
import static org.junit.jupiter.api.Assertions.*;

class FullSavedContextInputTest {
    @TempDir Path directory;
    @BeforeAll static void bootstrap() { FullSavedVillagerInputTest.bootstrap(); }
    private static final Identifier FIRST = Identifier.parse("phoenium:first"), SECOND = Identifier.parse("phoenium:second");

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void productionInterpretedStorageDiscoversRecursiveSourcesAndOriginalReaderKeepsTheirText(boolean reload) throws Exception {
        var f = new FullRegistrationFixture(true);
        var data = new SavedDataStorage(directory, net.minecraft.util.datafix.DataFixers.getDataFixer(), f.world.level.registryAccess());
        var storage = new CommandStorage(data);
        set(f.world.level.getServer(), MinecraftServer.class, "commandStorage", storage);
        var first = new CompoundTag(); first.put("message", storageText(SECOND)); storage.set(FIRST, first);
        var second = new CompoundTag(); second.putString("message", "resolved saved storage"); storage.set(SECOND, second);
        try {
            saved(f, ChunkPos.ZERO, reload, text(storageText(FIRST)));
            f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain(); var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
            assertEquals(FullInitializedInputs.State.SATISFIED, graph.inputEvaluation().state(), graph.inputEvaluation().obligation());
            var contexts = graph.inputReceipt().contexts(); assertNotNull(contexts);
            assertEquals(3, contexts.requirements("root[0]").size());
            assertTrue(contexts.valid(graph.savedSnapshot())); assertEquals(0, f.world.nextId);

            FullAuthoritativeFinishingTest.context(f); f.runtime.inputs.changed(); f.drain();
            assertInstanceOf(ChunkStageOutcome.Applied.class, graph.finishing.outcome().join());
            assertInstanceOf(ChunkStageOutcome.Applied.class, graph.publication.outcome().join());
            assertEquals(1, f.world.inserted.size());
            assertEquals("resolved saved storage", FullSavedContextTestHooks.text(assertInstanceOf(Display.TextDisplay.class, f.world.inserted.getFirst())).getString());
            assertEquals(1L, f.runtime.measurements().get("finishingCalls"));
            assertEquals(1L, f.runtime.measurements().get("publicationCalls"));
            assertEquals(0, f.world.localReads); assertFalse(f.barrier.tripped());
        } finally { f.stop(); FullFinishingServices.stopped(f.world.level.getServer()); data.close(); }
        assertCleanup(f);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void productionBlockNbtRunsOriginalCoordinatesPathAndSerializerAgainstTheRetainedPreparedChunk(boolean reload) throws Exception {
        var f = new FullRegistrationFixture(true); var position = new BlockPos(8, 8, 8);
        var state = Blocks.BEACON.defaultBlockState();
        f.input(ChunkPos.ZERO).getSection(0).setBlockState(8, 8, 8, state);
        var beacon = new BeaconBlockEntity(position, state); beacon.setCustomName(Component.literal("retained block context"));
        f.input(ChunkPos.ZERO).setBlockEntity(beacon);
        try {
            saved(f, ChunkPos.ZERO, reload, text(blockText(position)));
            f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain(); var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
            assertEquals(FullInitializedInputs.State.SATISFIED, graph.inputEvaluation().state(), graph.inputEvaluation().obligation());
            assertTrue(graph.inputReceipt().targets().stream().anyMatch(target -> target.position().equals(ChunkPos.ZERO)
                    && target.purposes().contains(FullInitializedInputs.Purpose.BLOCK_CONTEXT)));
            assertEquals(0, f.world.nextId);

            FullAuthoritativeFinishingTest.context(f); f.runtime.inputs.changed(); f.drain();
            assertInstanceOf(ChunkStageOutcome.Applied.class, graph.finishing.outcome().join());
            assertInstanceOf(ChunkStageOutcome.Applied.class, graph.publication.outcome().join());
            var display = assertInstanceOf(Display.TextDisplay.class, f.world.inserted.getFirst());
            assertEquals("retained block context", FullSavedContextTestHooks.text(display).getString());
            assertSame(beacon, graph.prepared().orElseThrow().actual().getBlockEntity(position));
            assertFalse(f.barrier.tripped());
        } finally { f.stop(); FullFinishingServices.stopped(f.world.level.getServer()); }
        assertCleanup(f);
    }

    @Test void absentBlockNbtKeepsTheOriginalIsLoadedEmptyBranchWithoutDemandingAChunk() throws Exception {
        var f = new FullRegistrationFixture(true); var absent = new BlockPos(1600, 8, 8);
        var chunk = new ChunkPos(absent.getX() >> 4, absent.getZ() >> 4);
        try {
            saved(f, ChunkPos.ZERO, false, text(blockText(absent)));
            f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain(); var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
            assertEquals(FullInitializedInputs.State.SATISFIED, graph.inputEvaluation().state(), graph.inputEvaluation().obligation());
            var target = graph.inputReceipt().targets().stream().filter(input -> input.position().equals(chunk)).findFirst().orElseThrow();
            assertEquals(FullInitializedInputs.Availability.ABSENT, target.availability());
            assertFalse(f.holders.containsKey(chunk.pack()), "context observation must not demand an originally absent block chunk");

            FullAuthoritativeFinishingTest.context(f); f.runtime.inputs.changed(); f.drain();
            assertInstanceOf(ChunkStageOutcome.Applied.class, graph.finishing.outcome().join());
            assertEquals("", FullSavedContextTestHooks.text(assertInstanceOf(Display.TextDisplay.class, f.world.inserted.getFirst())).getString());
            assertFalse(f.holders.containsKey(chunk.pack())); assertFalse(f.barrier.tripped());
        } finally { f.stop(); FullFinishingServices.stopped(f.world.level.getServer()); }
        assertCleanup(f);
    }

    @Test void originalPersistedCommandStorageLoadsBeforeRegionalAdmissionAndIsReadWithoutConstruction() throws Exception {
        var f = new FullRegistrationFixture(true);
        var firstStore = new SavedDataStorage(directory, net.minecraft.util.datafix.DataFixers.getDataFixer(), f.world.level.registryAccess());
        var written = new CommandStorage(firstStore); var payload = new CompoundTag(); payload.putString("message", "from disk");
        written.set(FIRST, payload); firstStore.close();
        var loadedStore = new SavedDataStorage(directory, net.minecraft.util.datafix.DataFixers.getDataFixer(), f.world.level.registryAccess());
        var loaded = new CommandStorage(loadedStore); set(f.world.level.getServer(), MinecraftServer.class, "commandStorage", loaded);
        try {
            saved(f, ChunkPos.ZERO, false, text(storageText(FIRST))); f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain();
            var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
            assertEquals(FullInitializedInputs.State.SATISFIED, graph.inputEvaluation().state(), graph.inputEvaluation().obligation());
            assertEquals(2, graph.inputReceipt().contexts().requirements("root[0]").size());
            assertEquals(0, f.world.nextId); assertFalse(f.owner.leases().hasActiveLease());
            try (var serial = FullFinishingServices.forServer(f.world.level.getServer()).serial(FullFinishingServices.Phase.SERVER_TASK)) {
                assertEquals("from disk", loaded.get(FIRST).getStringOr("message", ""));
            }
        } finally { f.stop(); FullFinishingServices.stopped(f.world.level.getServer()); loadedStore.close(); }
        assertCleanup(f);
    }

    @Test void nestedStorageAliasWriteInvalidatesReceiptAndIndependentPlansAcknowledgeSeparately() throws Exception {
        var f = new FullRegistrationFixture(true);
        var data = new SavedDataStorage(directory, net.minecraft.util.datafix.DataFixers.getDataFixer(), f.world.level.registryAccess());
        var storage = new CommandStorage(data); set(f.world.level.getServer(), MinecraftServer.class, "commandStorage", storage);
        var value = new CompoundTag(); var nested = new CompoundTag(); nested.putString("text", "before"); value.put("message", nested);
        storage.set(FIRST, value);
        try {
            saved(f, ChunkPos.ZERO, false, text(storageText(FIRST)));
            saved(f, new ChunkPos(1, 0), false, text(storageText(FIRST)));
            f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.start(new ChunkPos(1, 0), ChunkStatus.FULL); f.drain();
            var a = f.runtime.graph(f.holder(ChunkPos.ZERO)); var b = f.runtime.graph(f.holder(new ChunkPos(1, 0)));
            var oldA = a.inputReceipt(); var oldB = b.inputReceipt(); assertNotNull(oldA); assertNotNull(oldB);
            assertThrows(ChunkProtocolException.class, () -> nested.putString("text", "unauthorized"));
            assertEquals("before", nested.getStringOr("text", ""));
            try (var serial = FullFinishingServices.forServer(f.world.level.getServer()).serial(FullFinishingServices.Phase.SERVER_TASK)) {
                nested.putString("text", "after");
            }
            assertEquals(FullInitializedInputs.State.STALE, a.validateInputs(a.finishing.stage(), oldA).state());
            assertEquals(FullInitializedInputs.State.STALE, b.validateInputs(b.finishing.stage(), oldB).state());
            f.drain(); assertNotSame(oldA, a.inputReceipt()); assertNotSame(oldB, b.inputReceipt());
            var remaining = b.inputReceipt(); a.acknowledgeInputs();
            assertTrue(remaining.contexts().valid(b.savedSnapshot())); assertEquals(0, f.world.nextId);
        } finally { f.stop(); FullFinishingServices.stopped(f.world.level.getServer()); data.close(); }
        assertCleanup(f);
    }

    @Test void exactOriginalSetterRunsOnceAndInvalidatesItsPreparedDataCut() throws Exception {
        var f = new FullTadpoleDecisionTest.Fixture(1);
        var data = new SavedDataStorage(directory, null, f.level.registryAccess());
        var storage = new CommandStorage(data); set(f.level.getServer(), MinecraftServer.class, "commandStorage", storage);
        var first = new CompoundTag(); first.putString("message", "first"); storage.set(FIRST, first);
        try {
            var cut = FullCommandStorageInputs.observe(f.level.getServer(), FIRST); assertTrue(cut.valid());
            var calls = new AtomicInteger(); var replacement = new CompoundTag(); replacement.putString("message", "second");
            try (var serial = FullFinishingServices.forServer(f.level.getServer()).serial(FullFinishingServices.Phase.SERVER_TASK)) {
                FullCommandStorageInputs.set(storage, FIRST, replacement, () -> { calls.incrementAndGet(); storage.set(FIRST, replacement); });
            }
            assertEquals(1, calls.get()); assertFalse(cut.valid());
        } finally { FullFinishingServices.stopped(f.level.getServer()); data.close(); }
    }

    @Test void duplicateStorageAcknowledgementCannotRemoveAnIndependentSubscription() throws Exception {
        var f = new FullTadpoleDecisionTest.Fixture(1);
        var data = new SavedDataStorage(directory, null, f.level.registryAccess());
        var storage = new CommandStorage(data); set(f.level.getServer(), MinecraftServer.class, "commandStorage", storage);
        try {
            var capture = FullCommandStorageInputs.observe(f.level.getServer(), FIRST);
            var notices = new AtomicInteger(); Runnable shared = notices::incrementAndGet;
            var first = capture.retain(shared); var second = capture.retain(shared);
            first.close(); first.close();
            try (var serial = FullFinishingServices.forServer(f.level.getServer()).serial(FullFinishingServices.Phase.SERVER_TASK)) {
                var replacement = new CompoundTag(); replacement.putString("message", "changed");
                storage.set(FIRST, replacement);
            }
            assertEquals(1, notices.get(), "only the still-live independent consumer receives the change");
            second.close(); second.close();
            FullCommandStorageInputs.stopped(f.level.getServer());
            assertEquals(1, notices.get()); assertFalse(capture.valid());
        } finally { FullFinishingServices.stopped(f.level.getServer()); data.close(); }
    }

    @Test void storageShutdownAcknowledgesLaterConsumersAfterAnEarlierFailureAndCannotResurrect() throws Exception {
        var f = new FullTadpoleDecisionTest.Fixture(1);
        var data = new SavedDataStorage(directory, null, f.level.registryAccess());
        var storage = new CommandStorage(data); set(f.level.getServer(), MinecraftServer.class, "commandStorage", storage);
        var exact = new IllegalStateException("first storage consumer failed");
        var second = new AtomicInteger();
        var capture = FullCommandStorageInputs.observe(f.level.getServer(), FIRST);
        var firstUse = capture.retain(() -> { throw exact; });
        var secondUse = capture.retain(second::incrementAndGet);
        try {
            assertSame(exact, assertThrows(IllegalStateException.class, () -> FullFinishingServices.stopped(f.level.getServer())));
            assertEquals(1, second.get()); assertFalse(capture.valid());
            assertThrows(ChunkProtocolException.class, () -> FullCommandStorageInputs.observe(f.level.getServer(), FIRST));
            FullFinishingServices.stopped(f.level.getServer());
            assertEquals(1, second.get());
        } finally { firstUse.close(); secondUse.close(); data.close(); }
    }

    private static CompoundTag text(CompoundTag contents) { var tag = tag(8, 8, 0); tag.putString("id", "minecraft:text_display"); tag.put("text", contents); return tag; }
    private static CompoundTag storageText(Identifier id) {
        var text = new CompoundTag(); text.putString("nbt", "message"); text.putString("storage", id.toString()); text.putBoolean("interpret", true); return text;
    }
    private static CompoundTag blockText(BlockPos position) {
        var text = new CompoundTag(); text.putString("nbt", "CustomName");
        text.putString("block", position.getX() + " " + position.getY() + " " + position.getZ());
        text.putBoolean("interpret", true); return text;
    }
}
