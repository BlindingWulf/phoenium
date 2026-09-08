package dev.phoenium.chunk;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.entity.EntityLookup;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.storage.CommandStorage;
import net.minecraft.world.level.storage.SavedDataStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTypes;
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

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void pendingBlockEntityCustomNamePublishesNestedStorageBlockAndSelfSelectorDependencies(boolean reload) throws Exception {
        var f = new FullRegistrationFixture(true); var firstPosition = new BlockPos(8, 8, 8); var secondPosition = new BlockPos(9, 8, 8);
        var data = new SavedDataStorage(directory, net.minecraft.util.datafix.DataFixers.getDataFixer(), f.world.level.registryAccess());
        var storage = new CommandStorage(data); set(f.world.level.getServer(), MinecraftServer.class, "commandStorage", storage);
        var stored = new CompoundTag(); stored.putString("message", "storage "); storage.set(SECOND, stored);
        f.input(ChunkPos.ZERO).getSection(0).setBlockState(8, 8, 8, Blocks.BEACON.defaultBlockState());
        f.input(ChunkPos.ZERO).getSection(0).setBlockState(9, 8, 8, Blocks.BEACON.defaultBlockState());
        var customName = new CompoundTag(); customName.putString("text", "root "); var extra = new net.minecraft.nbt.ListTag();
        extra.add(storageText(SECOND)); extra.add(blockText(secondPosition));
        var selector = new CompoundTag(); selector.putString("selector", "@s"); extra.add(selector); customName.put("extra", extra);
        f.input(ChunkPos.ZERO).setBlockEntityNbt(pendingBeacon(firstPosition, customName));
        f.input(ChunkPos.ZERO).setBlockEntityNbt(pendingBeacon(secondPosition, net.minecraft.nbt.StringTag.valueOf("block ")));
        var source = text(blockText(firstPosition)); source.putString("CustomName", "self");
        try {
            saved(f, ChunkPos.ZERO, reload, source);
            f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain(); var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
            assertEquals(FullInitializedInputs.State.SATISFIED, graph.inputEvaluation().state(), graph.inputEvaluation().obligation());
            assertTrue(graph.inputReceipt().contexts().requirements("root[0]").size() >= 4);
            assertEquals(0, f.world.nextId); assertTrue(f.world.inserted.isEmpty());

            FullAuthoritativeFinishingTest.context(f); f.runtime.inputs.changed(); f.drain();
            var outcome = graph.finishing.outcome().join();
            assertInstanceOf(ChunkStageOutcome.Applied.class, outcome,
                    outcome instanceof ChunkStageOutcome.BarrierFailure failed ? failed.failure().toString() : outcome.toString());
            assertEquals("root storage block self", FullSavedContextTestHooks.text(
                    assertInstanceOf(Display.TextDisplay.class, f.world.inserted.getFirst())).getString());
            var prepared = graph.prepared().orElseThrow().actual();
            assertInstanceOf(BeaconBlockEntity.class, prepared.getBlockEntities().get(firstPosition));
            assertInstanceOf(BeaconBlockEntity.class, prepared.getBlockEntities().get(secondPosition));
            assertFalse(f.barrier.tripped());
        } finally { f.stop(); FullFinishingServices.stopped(f.world.level.getServer()); data.close(); }
        assertCleanup(f);
    }

    @Test void pendingBlockEntityNestedAliasMutationInvalidatesAndRebuildsItsRefinedContextPlan() throws Exception {
        var f = new FullRegistrationFixture(true); var position = new BlockPos(8, 8, 8);
        var data = new SavedDataStorage(directory, net.minecraft.util.datafix.DataFixers.getDataFixer(), f.world.level.registryAccess());
        var storage = new CommandStorage(data); set(f.world.level.getServer(), MinecraftServer.class, "commandStorage", storage);
        var first = new CompoundTag(); first.putString("message", "first pending result"); storage.set(FIRST, first);
        var second = new CompoundTag(); second.putString("message", "second pending result"); storage.set(SECOND, second);
        f.input(ChunkPos.ZERO).getSection(0).setBlockState(8, 8, 8, Blocks.BEACON.defaultBlockState());
        var customName = storageText(FIRST); var pending = pendingBeacon(position, customName);
        f.input(ChunkPos.ZERO).setBlockEntityNbt(pending);
        try {
            saved(f, ChunkPos.ZERO, false, text(blockText(position)));
            f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain(); var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
            var old = graph.inputReceipt(); assertNotNull(old);
            customName.putString("storage", SECOND.toString());
            assertEquals(FullInitializedInputs.State.STALE, graph.validateInputs(graph.finishing.stage(), old).state());
            f.drain(); assertNotSame(old, graph.inputReceipt());
            assertTrue(graph.inputReceipt().contexts().valid(graph.savedSnapshot()));

            FullAuthoritativeFinishingTest.context(f); f.runtime.inputs.changed(); f.drain();
            assertInstanceOf(ChunkStageOutcome.Applied.class, graph.finishing.outcome().join());
            assertEquals("second pending result", FullSavedContextTestHooks.text(
                    assertInstanceOf(Display.TextDisplay.class, f.world.inserted.getFirst())).getString());
            assertFalse(f.barrier.tripped());
        } finally { f.stop(); FullFinishingServices.stopped(f.world.level.getServer()); data.close(); }
        assertCleanup(f);
    }

    @Test void failedPendingTagRetentionDoesNotLeakAndLaterConsumerReceivesRemovalInvalidation() throws Exception {
        var f = new FullRegistrationFixture(true); var position = new BlockPos(8, 8, 8);
        var customName = storageText(FIRST); f.input(ChunkPos.ZERO).setBlockEntityNbt(pendingBeacon(position, customName));
        var failedCalls = new AtomicInteger(); var laterCalls = new AtomicInteger();
        var exact = new IllegalStateException("controlled pending tag retention failure");
        AutoCloseable later = null;
        try {
            var stale = FullBlockEntityTagInputs.observe(f.input(ChunkPos.ZERO), position, f.world.level.registryAccess(), () -> { }, ignored -> true);
            customName.putString("storage", SECOND.toString());
            assertSame(exact, assertThrows(IllegalStateException.class,
                    () -> stale.retain(() -> { failedCalls.incrementAndGet(); throw exact; })));
            var current = FullBlockEntityTagInputs.observe(f.input(ChunkPos.ZERO), position, f.world.level.registryAccess(), () -> { }, ignored -> true);
            later = current.retain(laterCalls::incrementAndGet); assertTrue(current.valid());
            f.input(ChunkPos.ZERO).setBlockEntityNbt(pendingBeacon(position, net.minecraft.nbt.StringTag.valueOf("replacement")));
            assertFalse(current.valid()); assertEquals(1, failedCalls.get()); assertEquals(1, laterCalls.get());
            later.close(); later.close(); later = null;
        } finally { if (later != null) later.close(); f.stop(); FullFinishingServices.stopped(f.world.level.getServer()); }
        assertCleanup(f);
    }

    @Test void protoAndLevelMapOccurrencesShareCustodyUntilTheLastRemovalAndThenPermitExactTagReuse() throws Exception {
        var f = new FullRegistrationFixture(true); var position = new BlockPos(8, 8, 8);
        var customName = storageText(FIRST); var original = pendingBeacon(position, customName);
        var proto = f.input(ChunkPos.ZERO); proto.setBlockEntityNbt(original);
        var level = new LevelChunk(f.world.level, proto, null);
        var changed = new AtomicInteger(); AutoCloseable use = null;
        try {
            var shared = FullBlockEntityTagInputs.observe(level, position, f.world.level.registryAccess(), changed::incrementAndGet, ignored -> true);
            assertNotNull(shared); use = shared.retain(changed::incrementAndGet);
            proto.setBlockEntityNbt(pendingBeacon(position, net.minecraft.nbt.StringTag.valueOf("proto replacement")));
            assertTrue(shared.valid(), "the surviving LevelChunk map occurrence retains the shared source");
            customName.putString("storage", SECOND.toString());
            assertFalse(shared.valid()); assertEquals(1, changed.get());
            use.close(); use = null;

            var levelCapture = FullBlockEntityTagInputs.observe(level, position, f.world.level.registryAccess(), () -> { }, ignored -> true);
            assertNotNull(levelCapture); use = levelCapture.retain(changed::incrementAndGet);
            level.setBlockEntityNbt(pendingBeacon(position, net.minecraft.nbt.StringTag.valueOf("level replacement")));
            assertFalse(levelCapture.valid()); assertEquals(2, changed.get());
            use.close(); use = null;

            proto.setBlockEntityNbt(original);
            var reused = FullBlockEntityTagInputs.observe(proto, position, f.world.level.registryAccess(), () -> { }, ignored -> true);
            assertNotNull(reused); assertTrue(reused.valid(), "last-owner retirement does not poison later exact-tag custody reuse");
        } finally { if (use != null) use.close(); f.stop(); FullFinishingServices.stopped(f.world.level.getServer()); }
        assertCleanup(f);
    }

    @Test void independentCapturesReuseOneSnapshotAndTrackTheirExactMapOccurrence() throws Exception {
        var f = new FullRegistrationFixture(true); var position = new BlockPos(8, 8, 8);
        var original = pendingBeacon(position, storageText(FIRST)); var proto = f.input(ChunkPos.ZERO);
        proto.setBlockEntityNbt(original); var level = new LevelChunk(f.world.level, proto, null);
        AutoCloseable protoUse = null, promotingUse = null, otherUse = null;
        try {
            var protoCapture = FullBlockEntityTagInputs.observe(proto, position, f.world.level.registryAccess(), () -> { }, ignored -> true);
            var promoting = FullBlockEntityTagInputs.observe(level, position, f.world.level.registryAccess(), () -> { }, ignored -> true);
            var other = FullBlockEntityTagInputs.observe(level, position, f.world.level.registryAccess(), () -> { }, ignored -> true);
            protoUse = protoCapture.retain(() -> { }); promotingUse = promoting.retain(() -> { }); otherUse = other.retain(() -> { });
            assertTrue(protoCapture.valid()); assertTrue(promoting.valid());
            assertTrue(other.valid(), "independent unchanged observations reuse the current immutable snapshot");
            promoting.consuming();
            level.setBlockEntityNbt(pendingBeacon(position, net.minecraft.nbt.StringTag.valueOf("promoted replacement")));
            assertTrue(promoting.valid(), "the exact plan that entered original promotion retains its capture");
            assertFalse(other.valid(), "another plan captured from the replaced Level occurrence becomes stale");
            assertTrue(protoCapture.valid(), "the still-retained Proto occurrence remains valid");
            proto.setBlockEntityNbt(pendingBeacon(position, net.minecraft.nbt.StringTag.valueOf("proto replacement")));
            assertFalse(protoCapture.valid(), "replacing the exact Proto occurrence stales only its remaining capture");
        } finally {
            if (protoUse != null) protoUse.close(); if (promotingUse != null) promotingUse.close();
            if (otherUse != null) otherUse.close();
            f.stop(); FullFinishingServices.stopped(f.world.level.getServer());
        }
        assertCleanup(f);
    }

    @Test void pendingTagNotificationsAndMapClearCompleteEveryListenerAndSourceAfterFailures() throws Exception {
        var f = new FullRegistrationFixture(true); var firstPosition = new BlockPos(8, 8, 8);
        var secondPosition = new BlockPos(9, 8, 8); var firstName = storageText(FIRST);
        f.input(ChunkPos.ZERO).setBlockEntityNbt(pendingBeacon(firstPosition, firstName));
        f.input(ChunkPos.ZERO).setBlockEntityNbt(pendingBeacon(secondPosition, net.minecraft.nbt.StringTag.valueOf("second")));
        var firstFailure = new IllegalStateException("first pending listener failure");
        var secondFailure = new IllegalArgumentException("second pending listener failure");
        AutoCloseable firstUse = null, secondUse = null;
        try {
            var firstCapture = FullBlockEntityTagInputs.observe(f.input(ChunkPos.ZERO), firstPosition,
                    f.world.level.registryAccess(), () -> { }, ignored -> true);
            firstUse = firstCapture.retain(() -> { throw firstFailure; });
            secondUse = firstCapture.retain(() -> { throw secondFailure; });
            var thrown = assertThrows(IllegalStateException.class, () -> firstName.putString("storage", SECOND.toString()));
            assertSame(firstFailure, thrown); assertArrayEquals(new Throwable[]{secondFailure}, thrown.getSuppressed());
            firstUse.close(); firstUse = null; secondUse.close(); secondUse = null;

            var currentFirst = FullBlockEntityTagInputs.observe(f.input(ChunkPos.ZERO), firstPosition,
                    f.world.level.registryAccess(), () -> { }, ignored -> true);
            var currentSecond = FullBlockEntityTagInputs.observe(f.input(ChunkPos.ZERO), secondPosition,
                    f.world.level.registryAccess(), () -> { }, ignored -> true);
            var later = new AtomicInteger();
            firstUse = currentFirst.retain(() -> { throw firstFailure; });
            secondUse = currentSecond.retain(later::incrementAndGet);
            var state = ((FullBlockEntityTagInputs.Access) f.input(ChunkPos.ZERO)).phoenium$blockEntityTags();
            @SuppressWarnings("unchecked") var guarded = (Map<BlockPos, CompoundTag>) field(state,
                    FullBlockEntityTagInputs.State.class, "guarded");
            assertSame(firstFailure, assertThrows(IllegalStateException.class, guarded::clear));
            assertEquals(1, later.get()); assertFalse(currentFirst.valid()); assertFalse(currentSecond.valid());
        } finally {
            if (firstUse != null) firstUse.close(); if (secondUse != null) secondUse.close();
            f.stop(); FullFinishingServices.stopped(f.world.level.getServer());
        }
        assertCleanup(f);
    }

    @Test void repeatedInterpretedReadsOfOnePendingBlockReuseOneCaptureAndBothOriginalSuffixes() throws Exception {
        var f = new FullRegistrationFixture(true); var position = new BlockPos(8, 8, 8);
        f.input(ChunkPos.ZERO).getSection(0).setBlockState(8, 8, 8, Blocks.BEACON.defaultBlockState());
        f.input(ChunkPos.ZERO).setBlockEntityNbt(pendingBeacon(position, net.minecraft.nbt.StringTag.valueOf("repeat")));
        var root = new CompoundTag(); root.putString("text", ""); var extra = new net.minecraft.nbt.ListTag();
        extra.add(blockText(position)); extra.add(blockText(position)); root.put("extra", extra);
        try {
            saved(f, ChunkPos.ZERO, false, text(root)); f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain();
            var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
            assertEquals(FullInitializedInputs.State.SATISFIED, graph.inputEvaluation().state(), graph.inputEvaluation().obligation());
            assertEquals(1, graph.inputReceipt().contexts().blockTagCaptures());
            FullAuthoritativeFinishingTest.context(f); f.runtime.inputs.changed(); f.drain();
            assertInstanceOf(ChunkStageOutcome.Applied.class, graph.finishing.outcome().join());
            assertEquals("repeatrepeat", FullSavedContextTestHooks.text(
                    assertInstanceOf(Display.TextDisplay.class, f.world.inserted.getFirst())).getString());
            assertFalse(f.barrier.tripped());
        } finally { f.stop(); FullFinishingServices.stopped(f.world.level.getServer()); }
        assertCleanup(f);
    }

    @Test void overlappingPendingTagProducerLeavesRefinementPendingAndItsCompletionRetriesThePlan() throws Exception {
        var f = new FullRegistrationFixture(true); var position = new BlockPos(8, 8, 8);
        f.input(ChunkPos.ZERO).getSection(0).setBlockState(8, 8, 8, Blocks.BEACON.defaultBlockState());
        var pending = pendingBeacon(position, net.minecraft.nbt.StringTag.valueOf("after producer"));
        f.input(ChunkPos.ZERO).setBlockEntityNbt(pending); var producer = FullSavedCollections.source(pending);
        producer.beforeWrite(); boolean producerActive = true;
        try {
            saved(f, ChunkPos.ZERO, false, text(blockText(position)));
            f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain(); var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
            assertEquals(FullInitializedInputs.State.PENDING, graph.inputEvaluation().state());
            assertTrue(graph.inputEvaluation().obligation().contains("dependency refinement"));
            assertNull(graph.inputReceipt()); assertFalse(f.barrier.tripped());

            Runnable notify = producer.written(List.of(pending)); producer.endWrite(); producerActive = false;
            if (notify != null) notify.run();
            f.drain(); assertEquals(FullInitializedInputs.State.SATISFIED,
                    graph.inputEvaluation().state(), graph.inputEvaluation().obligation());
            FullAuthoritativeFinishingTest.context(f); f.runtime.inputs.changed(); f.drain();
            assertInstanceOf(ChunkStageOutcome.Applied.class, graph.finishing.outcome().join());
            assertEquals("after producer", FullSavedContextTestHooks.text(
                    assertInstanceOf(Display.TextDisplay.class, f.world.inserted.getFirst())).getString());
            assertFalse(f.barrier.tripped());
        } finally {
            if (producerActive) { Runnable notify = producer.written(List.of(pending)); producer.endWrite(); if (notify != null) notify.run(); }
            f.stop(); FullFinishingServices.stopped(f.world.level.getServer());
        }
        assertCleanup(f);
    }

    @Test void pendingTagCapacityIsReservedBeforeDetachedCaptureAndOnlyOneExpandedPlanCopiesAtATime() throws Exception {
        var f = new FullRegistrationFixture(true, new FullInitializedInputs.Limits(4, 8, 32, 32, 1024 * 1024));
        var x = ChunkPos.ZERO; var y = new ChunkPos(1, 0); var xBlock = new BlockPos(8, 8, 8); var yBlock = new BlockPos(24, 8, 8);
        var xTag = pendingBeacon(xBlock, net.minecraft.nbt.StringTag.valueOf("x"));
        var yTag = pendingBeacon(yBlock, net.minecraft.nbt.StringTag.valueOf("y"));
        f.input(x).getSection(0).setBlockState(8, 8, 8, Blocks.BEACON.defaultBlockState()); f.input(x).setBlockEntityNbt(xTag);
        f.input(y).getSection(0).setBlockState(8, 8, 8, Blocks.BEACON.defaultBlockState()); f.input(y).setBlockEntityNbt(yTag);
        var xSource = FullSavedCollections.source(xTag); var ySource = FullSavedCollections.source(yTag);
        assertNull(xSource.snapshot()); assertNull(ySource.snapshot());
        assertThrows(FullBlockEntityTagInputs.Capacity.class, () -> FullBlockEntityTagInputs.observe(
                f.input(x), xBlock, f.world.level.registryAccess(), () -> { }, ignored -> false));
        assertNull(xSource.snapshot(), "capacity refusal precedes detached syntax allocation");
        try {
            saved(f, x, false, text(blockText(xBlock))); saved(f, y, false, text(blockText(yBlock)));
            f.start(x, ChunkStatus.FULL); f.start(y, ChunkStatus.FULL); f.drain();
            var first = f.runtime.graph(f.holder(x)); var second = f.runtime.graph(f.holder(y));
            assertEquals(1L, f.runtime.inputs.measurements().get("expandedOperations"));
            assertNotEquals(first.inputReceipt() == null, second.inputReceipt() == null,
                    "exactly one plan owns the expanded-operation capture allowance");
            var admitted = first.inputReceipt() != null ? first : second;
            var waiting = admitted == first ? second : first;
            var admittedSource = admitted == first ? xSource : ySource;
            var waitingSource = admitted == first ? ySource : xSource;
            assertNotNull(admittedSource.snapshot());
            assertNull(waitingSource.snapshot(), "waiting plans allocate no detached pending-tag capture");
            admitted.acknowledgeInputs(); f.drain();
            assertNotNull(waiting.inputReceipt()); assertNotNull(waitingSource.snapshot());
        } finally { f.stop(); FullFinishingServices.stopped(f.world.level.getServer()); }
        assertCleanup(f);
    }

    @Test void actualReloadParsedBlockTagsAreRetainedForDependencyRefinementWithoutEarlyInstallation() throws Exception {
        var f = new FullRegistrationFixture(true); var position = new BlockPos(8, 8, 8);
        var data = new SavedDataStorage(directory, net.minecraft.util.datafix.DataFixers.getDataFixer(), f.world.level.registryAccess());
        var storage = new CommandStorage(data); set(f.world.level.getServer(), MinecraftServer.class, "commandStorage", storage);
        var stored = new CompoundTag(); stored.putString("message", "parsed dependency"); storage.set(SECOND, stored);
        var proto = f.input(ChunkPos.ZERO); proto.getSection(0).setBlockState(8, 8, 8, Blocks.BEACON.defaultBlockState());
        var blockTag = pendingBeacon(position, storageText(SECOND)); blockTag.putBoolean("keepPacked", true);
        var entityTag = text(blockText(position));
        var factory = net.minecraft.world.level.chunk.storage.SerializableChunkData.class.getDeclaredMethod(
                "postLoadChunk", net.minecraft.server.level.ServerLevel.class, List.class, List.class);
        factory.setAccessible(true);
        var callback = (LevelChunk.PostLoadProcessor) factory.invoke(null, f.world.level, List.of(entityTag), List.of(blockTag));
        var blockSource = FullSavedCollections.source(blockTag); assertNotNull(blockSource);
        var loaded = new LevelChunk(f.world.level, proto, callback);
        assertNull(loaded.getBlockEntityNbt(position), "the parsed tag remains in the original post-load callback before FULL");
        f.inputs.put(ChunkPos.ZERO.pack(), new net.minecraft.world.level.chunk.ImposterProtoChunk(loaded, false));
        try {
            f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain(); var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
            assertEquals(FullInitializedInputs.State.SATISFIED, graph.inputEvaluation().state(), graph.inputEvaluation().obligation());
            assertEquals(1, graph.inputReceipt().contexts().blockTagCaptures());
            assertTrue(graph.inputReceipt().contexts().requirements("root[0]").size() >= 3,
                    "the retained parsed CustomName contributes its nested storage dependency");
            assertNull(loaded.getBlockEntityNbt(position), "dependency discovery does not install the parsed tag early");
            FullAuthoritativeFinishingTest.context(f); f.runtime.inputs.changed(); f.drain();
            assertInstanceOf(ChunkStageOutcome.Applied.class, graph.finishing.outcome().join());
            assertEquals("", FullSavedContextTestHooks.text(
                    assertInstanceOf(Display.TextDisplay.class, f.world.inserted.getFirst())).getString(),
                    "the original entity-before-block-entity post-load order remains observable");
            assertNull(blockSource.snapshot(), "the original post-load callback retires parsed-list custody after its exact installation loop");
            assertFalse(f.barrier.tripped());
        } finally { f.stop(); FullFinishingServices.stopped(f.world.level.getServer()); data.close(); }
        assertCleanup(f);
    }

    @Test void parsedBlockTagCustodyRetiresAfterOriginalReloadFailureAndSuppressesLaterListenerFailure() throws Exception {
        var f = new FullRegistrationFixture(true); var position = new BlockPos(8, 8, 8);
        var blockTag = pendingBeacon(position, storageText(SECOND));
        var blocks = FullBlockEntityTagInputs.reloadRoots(new java.util.ArrayList<>(List.of(blockTag)));
        var entities = FullSavedInput.reloadRoots(new java.util.ArrayList<CompoundTag>());
        var reload = FullBlockEntityTagInputs.reload(blocks);
        var capture = reload.observe(ChunkPos.ZERO, position, f.world.level.registryAccess(), () -> { }, ignored -> true);
        var originalFailure = new IllegalStateException("original parsed reload failed");
        var listenerFailure = new IllegalArgumentException("parsed block-tag listener failed during cleanup");
        var use = capture.retain(() -> { throw listenerFailure; });
        try {
            var processor = FullSavedInput.reload(chunk -> { throw originalFailure; }, entities, blocks);
            var thrown = assertThrows(IllegalStateException.class, () -> processor.run(null));
            assertSame(originalFailure, thrown); assertArrayEquals(new Throwable[]{listenerFailure}, thrown.getSuppressed());
            assertFalse(capture.valid(), "parsed-list custody retires despite the original post-load failure");
        } finally {
            use.close(); f.stop(); FullFinishingServices.stopped(f.world.level.getServer());
        }
        assertCleanup(f);
    }

    @Test void interpretedSelfAtTheOriginalResolutionDepthCutoffDoesNotBecomePermanentlyPending() throws Exception {
        var f = new FullRegistrationFixture(true);
        var root = new CompoundTag(); root.putString("text", ""); var current = root;
        for (int depth = 1; depth <= 100; depth++) {
            var next = depth == 100 ? entityText("@s", "CustomName", true) : new CompoundTag();
            if (depth != 100) next.putString("text", "");
            var extra = new net.minecraft.nbt.ListTag(); extra.add(next); current.put("extra", extra); current = next;
        }
        var source = text(root); source.put("CustomName", storageText(FIRST));
        try {
            saved(f, ChunkPos.ZERO, false, source); f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain();
            var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
            assertEquals(FullInitializedInputs.State.SATISFIED, graph.inputEvaluation().state(), graph.inputEvaluation().obligation());
            FullAuthoritativeFinishingTest.context(f); f.runtime.inputs.changed(); f.drain();
            assertInstanceOf(ChunkStageOutcome.Applied.class, graph.finishing.outcome().join());
            assertFalse(f.barrier.tripped());
        } finally { f.stop(); FullFinishingServices.stopped(f.world.level.getServer()); }
        assertCleanup(f);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void ownedSelfCustomNamePublishesNestedStorageDependenciesBeforeOriginalEntitySerialization(boolean reload) throws Exception {
        var f = new FullRegistrationFixture(true);
        var data = new SavedDataStorage(directory, net.minecraft.util.datafix.DataFixers.getDataFixer(), f.world.level.registryAccess());
        var storage = new CommandStorage(data); set(f.world.level.getServer(), MinecraftServer.class, "commandStorage", storage);
        var value = new CompoundTag(); value.putString("message", "resolved through saved custom name"); storage.set(SECOND, value);
        var source = text(entityText("@s", "CustomName", true));
        var customName = storageText(SECOND); source.put("CustomName", customName);
        try {
            saved(f, ChunkPos.ZERO, reload, source);
            f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain(); var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
            assertEquals(FullInitializedInputs.State.SATISFIED, graph.inputEvaluation().state(), graph.inputEvaluation().obligation());
            assertEquals(3, graph.inputReceipt().contexts().requirements("root[0]").size());
            assertEquals(0, f.world.nextId); assertTrue(f.world.inserted.isEmpty());

            FullAuthoritativeFinishingTest.context(f); f.runtime.inputs.changed(); f.drain();
            assertInstanceOf(ChunkStageOutcome.Applied.class, graph.finishing.outcome().join());
            var display = assertInstanceOf(Display.TextDisplay.class, f.world.inserted.getFirst());
            assertEquals("resolved through saved custom name", FullSavedContextTestHooks.text(display).getString());
            assertInstanceOf(net.minecraft.network.chat.contents.NbtContents.class, display.getCustomName().getContents(),
                    "Entity.load retains the unresolved saved component; only the reached outer NBT resolution resolves it");
            assertFalse(f.barrier.tripped());
        } finally { f.stop(); FullFinishingServices.stopped(f.world.level.getServer()); data.close(); }
        assertCleanup(f);
    }

    @Test void customNameAliasWriteInvalidatesItsDependencyPlanAndRediscoversTheNewStorageSource() throws Exception {
        var f = new FullRegistrationFixture(true);
        var data = new SavedDataStorage(directory, net.minecraft.util.datafix.DataFixers.getDataFixer(), f.world.level.registryAccess());
        var storage = new CommandStorage(data); set(f.world.level.getServer(), MinecraftServer.class, "commandStorage", storage);
        var first = new CompoundTag(); first.putString("message", "first"); storage.set(FIRST, first);
        var second = new CompoundTag(); second.putString("message", "second"); storage.set(SECOND, second);
        var source = text(entityText("@s", "CustomName", true));
        var customName = storageText(FIRST); source.put("CustomName", customName);
        try {
            saved(f, ChunkPos.ZERO, false, source);
            f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain(); var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
            var old = graph.inputReceipt(); assertNotNull(old);
            var retainedName = f.input(ChunkPos.ZERO).getEntities().getFirst().getCompound("CustomName").orElseThrow();
            retainedName.putString("storage", SECOND.toString());
            assertEquals(FullInitializedInputs.State.STALE, graph.validateInputs(graph.finishing.stage(), old).state());
            f.drain(); assertNotSame(old, graph.inputReceipt());
            assertTrue(graph.inputReceipt().contexts().valid(graph.savedSnapshot()));
            assertEquals(3, graph.inputReceipt().contexts().requirements("root[0]").size());

            FullAuthoritativeFinishingTest.context(f); f.runtime.inputs.changed(); f.drain();
            assertInstanceOf(ChunkStageOutcome.Applied.class, graph.finishing.outcome().join());
            assertEquals("second", FullSavedContextTestHooks.text(
                    assertInstanceOf(Display.TextDisplay.class, f.world.inserted.getFirst())).getString());
            assertFalse(f.barrier.tripped());
        } finally { f.stop(); FullFinishingServices.stopped(f.world.level.getServer()); data.close(); }
        assertCleanup(f);
    }

    @Test void originalInvalidCustomNameDecodeDoesNotLeakIntoTheNextInterpretedEntityOccurrence() throws Exception {
        var f = new FullRegistrationFixture(true);
        var data = new SavedDataStorage(directory, net.minecraft.util.datafix.DataFixers.getDataFixer(), f.world.level.registryAccess());
        var storage = new CommandStorage(data); set(f.world.level.getServer(), MinecraftServer.class, "commandStorage", storage);
        var value = new CompoundTag(); value.putString("message", "valid second occurrence"); storage.set(SECOND, value);
        var invalid = text(entityText("@s", "CustomName", true));
        var invalidName = new CompoundTag(); invalidName.putString("type", "phoenium:missing_component_type");
        invalid.put("CustomName", invalidName);
        var valid = text(entityText("@s", "CustomName", true)); valid.put("CustomName", storageText(SECOND));
        try {
            saved(f, ChunkPos.ZERO, false, invalid); f.input(ChunkPos.ZERO).addEntity(valid);
            f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain(); var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
            assertEquals(FullInitializedInputs.State.SATISFIED, graph.inputEvaluation().state(), graph.inputEvaluation().obligation());
            assertEquals(1, graph.inputReceipt().contexts().requirements("root[0]").size());
            assertEquals(3, graph.inputReceipt().contexts().requirements("root[1]").size());

            FullAuthoritativeFinishingTest.context(f); f.runtime.inputs.changed(); f.drain();
            assertInstanceOf(ChunkStageOutcome.Applied.class, graph.finishing.outcome().join());
            assertEquals(2, f.world.inserted.size());
            assertEquals("", FullSavedContextTestHooks.text(
                    assertInstanceOf(Display.TextDisplay.class, f.world.inserted.getFirst())).getString());
            assertEquals("valid second occurrence", FullSavedContextTestHooks.text(
                    assertInstanceOf(Display.TextDisplay.class, f.world.inserted.get(1))).getString());
            assertFalse(f.barrier.tripped());
        } finally { f.stop(); FullFinishingServices.stopped(f.world.level.getServer()); data.close(); }
        assertCleanup(f);
    }

    @Test void retainedBlockNbtUsesTheOriginalImmediateLazyCreationAndSerializationSuffix() throws Exception {
        var f = new FullRegistrationFixture(true); var position = new BlockPos(8, 8, 8);
        f.input(ChunkPos.ZERO).getSection(0).setBlockState(8, 8, 8, Blocks.BEACON.defaultBlockState());
        assertFalse(f.input(ChunkPos.ZERO).getBlockEntities().containsKey(position));
        try {
            saved(f, ChunkPos.ZERO, false, text(blockText(position, "id", false)));
            f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain(); var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
            assertEquals(FullInitializedInputs.State.SATISFIED, graph.inputEvaluation().state(), graph.inputEvaluation().obligation());
            var prepared = graph.prepared().orElseThrow().actual();
            assertFalse(prepared.getBlockEntities().containsKey(position), "preparation must retain the original lazy state");

            FullAuthoritativeFinishingTest.context(f); f.runtime.inputs.changed(); f.drain();
            assertInstanceOf(ChunkStageOutcome.Applied.class, graph.finishing.outcome().join());
            assertInstanceOf(BeaconBlockEntity.class, prepared.getBlockEntities().get(position));
            assertEquals("\"minecraft:beacon\"", FullSavedContextTestHooks.text(
                    assertInstanceOf(Display.TextDisplay.class, f.world.inserted.getFirst())).getString());
            assertFalse(f.barrier.tripped());
        } finally { f.stop(); FullFinishingServices.stopped(f.world.level.getServer()); }
        assertCleanup(f);
    }

    @Test void originalCaughtBlockSerializerFailureReleasesTheScopeForTheNextSavedEntity() throws Exception {
        var f = new FullRegistrationFixture(true); var position = new BlockPos(8, 8, 8);
        var state = Blocks.BEACON.defaultBlockState(); var exact = new IllegalStateException("original block serializer failure");
        f.input(ChunkPos.ZERO).getSection(0).setBlockState(8, 8, 8, state);
        var failing = new FailingBlockEntity(position, state, exact);
        f.input(ChunkPos.ZERO).setBlockEntity(failing);
        try {
            saved(f, ChunkPos.ZERO, false, text(blockText(position, "id", false)));
            f.input(ChunkPos.ZERO).addEntity(text(blockText(position, "id", false)));
            f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain(); var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
            assertSame(failing, graph.prepared().orElseThrow().actual().getBlockEntities().get(position));
            assertEquals(FullInitializedInputs.State.SATISFIED, graph.inputEvaluation().state(), graph.inputEvaluation().obligation());
            FullAuthoritativeFinishingTest.context(f); f.runtime.inputs.changed(); f.drain();
            assertEquals(2, failing.saves);
            assertInstanceOf(ChunkStageOutcome.Applied.class, graph.finishing.outcome().join());
            assertInstanceOf(ChunkStageOutcome.Applied.class, graph.publication.outcome().join());
            assertEquals(2, f.world.inserted.size(), "the original loader catches the first entity failure and continues");
            assertEquals("", FullSavedContextTestHooks.text(
                    assertInstanceOf(Display.TextDisplay.class, f.world.inserted.getFirst())).getString());
            assertEquals("\"minecraft:beacon\"", FullSavedContextTestHooks.text(
                    assertInstanceOf(Display.TextDisplay.class, f.world.inserted.get(1))).getString());
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

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void sameWorldSelectorRunsOriginalPredicateOrderAndNameReadInsideTheConvergedRegion(boolean reload) throws Exception {
        var f = new FullRegistrationFixture(true); var lookup = new EntityLookup<Entity>();
        var first = EntityTypes.TEXT_DISPLAY.create(f.world.level, EntitySpawnReason.LOAD);
        var second = EntityTypes.TEXT_DISPLAY.create(f.world.level, EntitySpawnReason.LOAD);
        assertNotNull(first); assertNotNull(second);
        first.setId(701); first.setPos(1608, 8, 8); first.setCustomName(Component.literal("first indexed candidate"));
        second.setId(702); second.setPos(1609, 8, 8); second.setCustomName(Component.literal("second indexed candidate"));
        lookup.add(first); lookup.add(second); f.world.selectorEntities.add(first); f.world.selectorEntities.add(second);
        assertEquals(f.ownerId, f.directory.resolve(new dev.phoenium.topology.RoutingKey(f.worldId, 100, 0)).orElseThrow().region());
        var selector = new CompoundTag();
        selector.putString("selector", "@e[type=minecraft:text_display,distance=..2000,limit=1]");
        try {
            saved(f, ChunkPos.ZERO, reload, text(selector));
            f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain(); var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
            assertEquals(FullInitializedInputs.State.SATISFIED, graph.inputEvaluation().state(), graph.inputEvaluation().obligation());
            var routed = graph.inputReceipt().targets().stream().filter(target -> target.position().equals(new ChunkPos(100, 0))).findFirst().orElseThrow();
            assertEquals(FullInitializedInputs.Availability.ROUTED, routed.availability());
            assertEquals(java.util.Set.of(FullInitializedInputs.Purpose.ENTITY_CONTEXT), routed.purposes());
            assertNull(routed.holder()); assertFalse(f.holders.containsKey(new ChunkPos(100, 0).pack()));

            FullAuthoritativeFinishingTest.context(f); f.runtime.inputs.changed(); f.drain();
            assertInstanceOf(ChunkStageOutcome.Applied.class, graph.finishing.outcome().join());
            assertInstanceOf(ChunkStageOutcome.Applied.class, graph.publication.outcome().join());
            assertEquals("first indexed candidate", FullSavedContextTestHooks.text(assertInstanceOf(Display.TextDisplay.class, f.world.inserted.getFirst())).getString());
            assertFalse(f.barrier.tripped());
        } finally {
            lookup.remove(first); lookup.remove(second); f.stop(); FullFinishingServices.stopped(f.world.level.getServer());
        }
        assertCleanup(f);
    }

    @Test void filteredSelfSelectorRunsItsOriginalNbtPredicateWithoutAWorldCandidateGrant() throws Exception {
        var f = new FullRegistrationFixture(true); var selector = new CompoundTag();
        selector.putString("selector", "@s[nbt={}]");
        try {
            saved(f, ChunkPos.ZERO, false, text(selector));
            f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain(); var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
            assertEquals(FullInitializedInputs.State.SATISFIED, graph.inputEvaluation().state(), graph.inputEvaluation().obligation());
            assertTrue(graph.inputReceipt().targets().stream().noneMatch(target ->
                    target.purposes().contains(FullInitializedInputs.Purpose.ENTITY_CONTEXT)));

            FullAuthoritativeFinishingTest.context(f); f.runtime.inputs.changed(); f.drain();
            assertInstanceOf(ChunkStageOutcome.Applied.class, graph.finishing.outcome().join());
            assertEquals("Text Display", FullSavedContextTestHooks.text(
                    assertInstanceOf(Display.TextDisplay.class, f.world.inserted.getFirst())).getString());
            assertFalse(f.barrier.tripped());
        } finally { f.stop(); FullFinishingServices.stopped(f.world.level.getServer()); }
        assertCleanup(f);
    }

    @Test void selectorValuedSeparatorRestoresTheOuterSameWorldCandidateGrant() throws Exception {
        var f = new FullRegistrationFixture(true); var lookup = new EntityLookup<Entity>();
        var first = EntityTypes.TEXT_DISPLAY.create(f.world.level, EntitySpawnReason.LOAD);
        var second = EntityTypes.TEXT_DISPLAY.create(f.world.level, EntitySpawnReason.LOAD);
        assertNotNull(first); assertNotNull(second);
        first.setId(711); first.setPos(1608, 8, 8); first.setCustomName(Component.literal("first"));
        second.setId(712); second.setPos(1609, 8, 8); second.setCustomName(Component.literal("second"));
        lookup.add(first); lookup.add(second); f.world.selectorEntities.add(first); f.world.selectorEntities.add(second);
        var nested = new CompoundTag(); nested.putString("selector", "@e[type=minecraft:text_display,distance=..2000,limit=1]");
        var outer = new CompoundTag(); outer.putString("selector", "@e[type=minecraft:text_display,distance=..2000]"); outer.put("separator", nested);
        try {
            saved(f, ChunkPos.ZERO, false, text(outer));
            f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain(); var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
            assertEquals(FullInitializedInputs.State.SATISFIED, graph.inputEvaluation().state(), graph.inputEvaluation().obligation());
            FullAuthoritativeFinishingTest.context(f); f.runtime.inputs.changed(); f.drain();
            assertInstanceOf(ChunkStageOutcome.Applied.class, graph.finishing.outcome().join());
            assertEquals("firstfirstsecond", FullSavedContextTestHooks.text(
                    assertInstanceOf(Display.TextDisplay.class, f.world.inserted.getFirst())).getString());
            assertFalse(f.barrier.tripped());
        } finally {
            lookup.remove(first); lookup.remove(second); f.stop(); FullFinishingServices.stopped(f.world.level.getServer());
        }
        assertCleanup(f);
    }

    @Test void undeclaredNestedSelectorFailsBeforeItsOriginalBody() throws Exception {
        var f = new FullRegistrationFixture(true); var selector = new CompoundTag(); selector.putString("selector", "@s");
        var nestedOriginals = new AtomicInteger();
        try (var ignored = FullSavedContextTestHooks.selectorPrefix(() -> {
            try {
                FullSavedContextInputs.selectorName("@e[type=minecraft:pig]", () -> {
                    nestedOriginals.incrementAndGet(); return Component.empty();
                });
            } catch (com.mojang.brigadier.exceptions.CommandSyntaxException impossible) { throw new AssertionError(impossible); }
        })) {
            saved(f, ChunkPos.ZERO, false, text(selector));
            f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain(); var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
            FullAuthoritativeFinishingTest.context(f); f.runtime.inputs.changed(); f.drain();
            assertEquals(0, nestedOriginals.get());
            var failure = assertInstanceOf(ChunkStageOutcome.BarrierFailure.class, graph.finishing.outcome().join()).failure();
            assertTrue(failure.getMessage().contains("unbound saved text selector input"));
            assertTrue(f.barrier.tripped());
        } finally { f.stop(); FullFinishingServices.stopped(f.world.level.getServer()); }
        assertCleanup(f);
    }

    @Test void sameWorldEntityNbtRunsTheOriginalWorldLimitedPredicateAndSerializer() throws Exception {
        var f = new FullRegistrationFixture(true); var lookup = new EntityLookup<Entity>();
        var candidate = EntityTypes.TEXT_DISPLAY.create(f.world.level, EntitySpawnReason.LOAD); assertNotNull(candidate);
        candidate.setId(721); candidate.setPos(1608, 8, 8); candidate.setCustomName(Component.literal("serialized candidate"));
        lookup.add(candidate); f.world.selectorEntities.add(candidate);
        var source = "@e[type=minecraft:text_display,distance=..2000,nbt={},limit=1]";
        try {
            saved(f, ChunkPos.ZERO, false, text(entityText(source, "CustomName", false)));
            f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain(); var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
            assertEquals(FullInitializedInputs.State.SATISFIED, graph.inputEvaluation().state(), graph.inputEvaluation().obligation());
            var target = graph.inputReceipt().targets().stream().filter(input -> input.position().equals(new ChunkPos(100, 0))).findFirst().orElseThrow();
            assertEquals(FullInitializedInputs.Availability.ROUTED, target.availability());

            FullAuthoritativeFinishingTest.context(f); f.runtime.inputs.changed(); f.drain();
            assertInstanceOf(ChunkStageOutcome.Applied.class, graph.finishing.outcome().join());
            assertEquals("\"serialized candidate\"", FullSavedContextTestHooks.text(
                    assertInstanceOf(Display.TextDisplay.class, f.world.inserted.getFirst())).getString());
            assertFalse(f.barrier.tripped());
        } finally {
            lookup.remove(candidate); f.stop(); FullFinishingServices.stopped(f.world.level.getServer());
        }
        assertCleanup(f);
    }

    @Test void originalCaughtEntityNbtSerializerFailureRestoresTheNextExactSelectorScope() throws Exception {
        var f = new FullRegistrationFixture(true); var lookup = new EntityLookup<Entity>();
        var exact = new IllegalStateException("original entity serializer failure");
        var candidate = new FailingTextDisplay(f.world.level, exact);
        candidate.setId(722); candidate.setPos(1608, 8, 8); candidate.setCustomName(Component.literal("recovered candidate"));
        lookup.add(candidate); f.world.selectorEntities.add(candidate);
        var source = "@e[type=minecraft:text_display,distance=..2000,nbt={},limit=1]";
        try {
            saved(f, ChunkPos.ZERO, false, text(entityText(source, "CustomName", false)));
            f.input(ChunkPos.ZERO).addEntity(text(entityText(source, "CustomName", false)));
            f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain(); var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
            assertEquals(FullInitializedInputs.State.SATISFIED, graph.inputEvaluation().state(), graph.inputEvaluation().obligation());
            FullAuthoritativeFinishingTest.context(f); f.runtime.inputs.changed(); f.drain();

            assertEquals(3, candidate.saves, "predicate failure is caught once; the next predicate and data source serialize once each");
            assertInstanceOf(ChunkStageOutcome.Applied.class, graph.finishing.outcome().join());
            assertEquals(2, f.world.inserted.size());
            assertEquals("", FullSavedContextTestHooks.text(
                    assertInstanceOf(Display.TextDisplay.class, f.world.inserted.getFirst())).getString());
            assertEquals("\"recovered candidate\"", FullSavedContextTestHooks.text(
                    assertInstanceOf(Display.TextDisplay.class, f.world.inserted.get(1))).getString());
            assertFalse(f.barrier.tripped());
        } finally {
            lookup.remove(candidate); f.stop(); FullFinishingServices.stopped(f.world.level.getServer());
        }
        assertCleanup(f);
    }

    @Test void registeredSelectorPredicateRemainsUnresolvedBeforeItsOriginalBody() throws Exception {
        var f = new FullRegistrationFixture(true); var selector = new CompoundTag();
        selector.putString("selector", "@e[distance=..32,predicate=minecraft:missing]");
        try {
            saved(f, ChunkPos.ZERO, false, text(selector));
            f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain(); var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
            assertEquals(FullInitializedInputs.State.UNRESOLVED, graph.inputEvaluation().state());
            assertTrue(graph.inputEvaluation().obligation().contains("registered selector predicate context producer pending"));
            assertNull(graph.inputReceipt()); assertFalse(graph.finishing.outcome().isDone()); assertFalse(f.barrier.tripped());
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

    @Test void storageMutationNotifiesEveryRetainedPlanAndAggregatesListenerFailures() throws Exception {
        var f = new FullTadpoleDecisionTest.Fixture(1);
        var data = new SavedDataStorage(directory, null, f.level.registryAccess());
        var storage = new CommandStorage(data); set(f.level.getServer(), MinecraftServer.class, "commandStorage", storage);
        var capture = FullCommandStorageInputs.observe(f.level.getServer(), FIRST);
        var firstFailure = new IllegalStateException("first storage mutation listener failed");
        var secondFailure = new IllegalArgumentException("second storage mutation listener failed");
        var first = capture.retain(() -> { throw firstFailure; });
        var second = capture.retain(() -> { throw secondFailure; });
        try {
            var replacement = new CompoundTag(); replacement.putString("message", "committed");
            try (var serial = FullFinishingServices.forServer(f.level.getServer()).serial(FullFinishingServices.Phase.SERVER_TASK)) {
                var thrown = assertThrows(IllegalStateException.class, () -> storage.set(FIRST, replacement));
                assertSame(firstFailure, thrown); assertArrayEquals(new Throwable[]{secondFailure}, thrown.getSuppressed());
            }
            assertFalse(capture.valid());
        } finally {
            first.close(); second.close(); FullFinishingServices.stopped(f.level.getServer()); data.close();
        }
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
        return blockText(position, "CustomName", true);
    }
    private static CompoundTag blockText(BlockPos position, String path, boolean interpret) {
        var text = new CompoundTag(); text.putString("nbt", path);
        text.putString("block", position.getX() + " " + position.getY() + " " + position.getZ());
        text.putBoolean("interpret", interpret); return text;
    }
    private static CompoundTag entityText(String selector, String path, boolean interpret) {
        var text = new CompoundTag(); text.putString("nbt", path); text.putString("entity", selector);
        text.putBoolean("interpret", interpret); return text;
    }
    private static CompoundTag pendingBeacon(BlockPos position, net.minecraft.nbt.Tag customName) {
        var tag = new CompoundTag(); tag.putString("id", "minecraft:beacon");
        tag.putInt("x", position.getX()); tag.putInt("y", position.getY()); tag.putInt("z", position.getZ());
        tag.put("CustomName", customName); return tag;
    }
    private static final class FailingBlockEntity extends BlockEntity {
        private final RuntimeException failure;
        private int saves;
        private FailingBlockEntity(BlockPos position, net.minecraft.world.level.block.state.BlockState state, RuntimeException failure) {
            super(BlockEntityTypes.BEACON, position, state); this.failure = failure;
        }
        @Override protected void saveAdditional(ValueOutput output) { if (++saves == 1) throw failure; }
    }
    private static final class FailingTextDisplay extends Display.TextDisplay {
        private final RuntimeException failure;
        private int saves;
        private FailingTextDisplay(net.minecraft.world.level.Level level, RuntimeException failure) {
            super(EntityTypes.TEXT_DISPLAY, level); this.failure = failure;
        }
        @Override protected void addAdditionalSaveData(ValueOutput output) {
            if (++saves == 1) throw failure;
            super.addAdditionalSaveData(output);
        }
    }
}
