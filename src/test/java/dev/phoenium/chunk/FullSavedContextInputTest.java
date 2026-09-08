package dev.phoenium.chunk;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
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
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTypes;
import net.minecraft.world.level.block.entity.CommandBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.entity.decoration.Mannequin;
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

    @Test void repeatedBusyOccurrenceReplacementRetainsOnlyItsCurrentPlanWaiter() throws Exception {
        var f = new FullRegistrationFixture(true); var position = new BlockPos(8, 8, 8);
        f.input(ChunkPos.ZERO).getSection(0).setBlockState(8, 8, 8, Blocks.BEACON.defaultBlockState());
        CompoundTag currentTag = pendingBeacon(position, net.minecraft.nbt.StringTag.valueOf("initial"));
        f.input(ChunkPos.ZERO).setBlockEntityNbt(currentTag);
        FullSavedInput currentSource = FullSavedCollections.source(currentTag); currentSource.beforeWrite();
        boolean producerActive = true;
        try {
            saved(f, ChunkPos.ZERO, false, text(blockText(position)));
            f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain(); var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
            @SuppressWarnings("unchecked") var plans = (Map<Object, Object>) field(
                    f.runtime.inputs, FullInitializedInputs.class, "plans");
            Object inputPlan = plans.get(graph); var contexts = (FullSavedContextInputs.Plan) field(
                    inputPlan, inputPlan.getClass(), "contexts");
            @SuppressWarnings("unchecked") var consumers = (List<AutoCloseable>) field(
                    contexts, FullSavedContextInputs.Plan.class, "consumers");
            @SuppressWarnings("unchecked") var waiters = (Map<BlockPos, AutoCloseable>) field(
                    contexts, FullSavedContextInputs.Plan.class, "blockWaiters");
            int retainedConsumers = consumers.size(); assertEquals(1, waiters.size());
            LevelChunk actual = graph.inputTargets().stream().map(FullInitializedInputs.Target::actual)
                    .filter(java.util.Objects::nonNull).findFirst().orElseThrow();
            for (int replacement = 0; replacement < 4; replacement++) {
                CompoundTag nextTag = pendingBeacon(position,
                        net.minecraft.nbt.StringTag.valueOf("replacement " + replacement));
                actual.setBlockEntityNbt(nextTag); FullSavedInput nextSource = FullSavedCollections.source(nextTag);
                nextSource.beforeWrite();
                Runnable notify = currentSource.written(List.of(currentTag)); currentSource.endWrite();
                if (notify != null) notify.run();
                currentTag = nextTag; currentSource = nextSource; f.drain();
                assertEquals(1, waiters.size(), "only the currently observed occurrence retains a retry waiter");
                assertEquals(retainedConsumers, consumers.size(), "closed retry waiters do not accumulate as consumers");
            }
            Runnable notify = currentSource.written(List.of(currentTag)); currentSource.endWrite(); producerActive = false;
            if (notify != null) notify.run();
            f.drain(); assertEquals(FullInitializedInputs.State.SATISFIED,
                    graph.inputEvaluation().state(), graph.inputEvaluation().obligation());
            assertTrue(waiters.isEmpty()); assertEquals(retainedConsumers + 1, consumers.size(),
                    "the completed retry is replaced by the one live captured dependency");
        } finally {
            if (producerActive) {
                Runnable notify = currentSource.written(List.of(currentTag)); currentSource.endWrite();
                if (notify != null) notify.run();
            }
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

    @Test void pendingMapAbsenceInsertionInvalidatesPreflightAndBindsItsNestedContextBeforeClaim() throws Exception {
        var f = new FullRegistrationFixture(true); var position = new BlockPos(8, 8, 8);
        var data = new SavedDataStorage(directory, null, f.world.level.registryAccess());
        var storage = new CommandStorage(data); set(f.world.level.getServer(), MinecraftServer.class, "commandStorage", storage);
        var stored = new CompoundTag(); stored.putString("message", "late map dependency"); storage.set(SECOND, stored);
        f.input(ChunkPos.ZERO).getSection(0).setBlockState(8, 8, 8, Blocks.BEACON.defaultBlockState());
        try {
            saved(f, ChunkPos.ZERO, false, text(blockText(position))); f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain();
            var graph = f.runtime.graph(f.holder(ChunkPos.ZERO)); var first = graph.inputReceipt();
            assertNotNull(first); assertEquals(0, first.contexts().blockTagCaptures());
            var actual = graph.inputTargets().stream().map(FullInitializedInputs.Target::actual)
                    .filter(java.util.Objects::nonNull).findFirst().orElseThrow();
            actual.setBlockEntityNbt(pendingBeacon(position, storageText(SECOND))); f.drain();
            var rebound = graph.inputReceipt(); assertNotNull(rebound); assertNotSame(first, rebound);
            assertEquals(1, rebound.contexts().blockTagCaptures());
            assertTrue(rebound.contexts().requirements("root[0]").size() >= 3,
                    "the late pending tag's nested storage dependency is bound before the original claim");
        } finally {
            f.stop(); FullFinishingServices.stopped(f.world.level.getServer()); data.close();
        }
        assertCleanup(f);
    }

    @Test void parsedListAbsenceInsertionInvalidatesPreflightAndBindsItsNestedContextBeforeClaim() throws Exception {
        var f = new FullRegistrationFixture(true); var position = new BlockPos(8, 8, 8);
        var data = new SavedDataStorage(directory, null, f.world.level.registryAccess());
        var storage = new CommandStorage(data); set(f.world.level.getServer(), MinecraftServer.class, "commandStorage", storage);
        var stored = new CompoundTag(); stored.putString("message", "late parsed dependency"); storage.set(SECOND, stored);
        var proto = f.input(ChunkPos.ZERO); proto.getSection(0).setBlockState(8, 8, 8, Blocks.BEACON.defaultBlockState());
        var entities = FullSavedInput.reloadRoots(new java.util.ArrayList<>(List.of(text(blockText(position)))));
        var blocks = FullBlockEntityTagInputs.reloadRoots(new java.util.ArrayList<CompoundTag>());
        var loaded = new LevelChunk(f.world.level, proto, FullSavedInput.reload(chunk -> { }, entities, blocks));
        f.inputs.put(ChunkPos.ZERO.pack(), new net.minecraft.world.level.chunk.ImposterProtoChunk(loaded, false));
        try {
            f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain(); var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
            var first = graph.inputReceipt(); assertNotNull(first); assertEquals(0, first.contexts().blockTagCaptures());
            blocks.add(pendingBeacon(position, storageText(SECOND))); f.drain();
            var rebound = graph.inputReceipt(); assertNotNull(rebound); assertNotSame(first, rebound);
            assertEquals(1, rebound.contexts().blockTagCaptures());
            assertTrue(rebound.contexts().requirements("root[0]").size() >= 3,
                    "the late parsed tag's nested storage dependency is bound before the original claim");
        } finally {
            f.stop(); loaded.runPostLoad(); FullFinishingServices.stopped(f.world.level.getServer()); data.close();
        }
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

    @Test void parsedBlockListObservationKeepsDuplicateLastOrderAndVersionsRealListMutations() throws Exception {
        var f = new FullRegistrationFixture(true); var position = new BlockPos(8, 8, 8);
        var first = pendingBeacon(position, net.minecraft.nbt.StringTag.valueOf("first"));
        var second = pendingBeacon(position, net.minecraft.nbt.StringTag.valueOf("second"));
        var roots = FullBlockEntityTagInputs.reloadRoots(new java.util.ArrayList<>(List.of(first, second)));
        var reload = FullBlockEntityTagInputs.reload(roots); var changed = new AtomicInteger();
        AutoCloseable use = null;
        try {
            var capture = reload.observe(ChunkPos.ZERO, position, f.world.level.registryAccess(), () -> { }, ignored -> true);
            assertEquals("second", FullSavedTextInputs.decode(capture.customName(), f.world.level.registryAccess()).orElseThrow().getString());
            use = capture.retain(changed::incrementAndGet);
            var third = pendingBeacon(position, net.minecraft.nbt.StringTag.valueOf("third")); roots.add(third);
            assertFalse(capture.valid()); assertEquals(1, changed.get()); use.close(); use = null;

            capture = reload.observe(ChunkPos.ZERO, position, f.world.level.registryAccess(), () -> { }, ignored -> true);
            assertEquals("third", FullSavedTextInputs.decode(capture.customName(), f.world.level.registryAccess()).orElseThrow().getString());
            use = capture.retain(changed::incrementAndGet); assertSame(third, roots.remove(2));
            assertFalse(capture.valid()); assertEquals(2, changed.get()); use.close(); use = null;

            var restored = reload.observe(ChunkPos.ZERO, position, f.world.level.registryAccess(), () -> { }, ignored -> true);
            assertEquals("second", FullSavedTextInputs.decode(restored.customName(), f.world.level.registryAccess()).orElseThrow().getString());
        } finally {
            if (use != null) use.close(); reload.close(); f.stop(); FullFinishingServices.stopped(f.world.level.getServer());
        }
        assertCleanup(f);
    }

    @Test void parsedListMutationDuringCapacityCheckNotifiesRetryOutsideReloadMetadata() throws Exception {
        var f = new FullRegistrationFixture(true); var position = new BlockPos(8, 8, 8);
        var roots = FullBlockEntityTagInputs.reloadRoots(new java.util.ArrayList<>(List.of(
                pendingBeacon(position, net.minecraft.nbt.StringTag.valueOf("before mutation")))));
        var reload = FullBlockEntityTagInputs.reload(roots); var notified = new AtomicInteger();
        try {
            var replacement = pendingBeacon(position, net.minecraft.nbt.StringTag.valueOf("after mutation"));
            var retry = assertThrows(FullBlockEntityTagInputs.Retry.class, () -> reload.observe(
                    ChunkPos.ZERO, position, f.world.level.registryAccess(), () -> {
                        assertFalse(Thread.holdsLock(reload), "retry notification runs outside reload metadata");
                        notified.incrementAndGet();
                    }, ignored -> { roots.add(replacement); return true; }));
            assertEquals(1, notified.get()); retry.waiter.close();
        } finally {
            reload.close(); f.stop(); FullFinishingServices.stopped(f.world.level.getServer());
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

    @Test void missingRegisteredSelectorPredicateKeepsTheOriginalEmptyLookupBranch() throws Exception {
        var f = new FullRegistrationFixture(true); var selector = new CompoundTag();
        selector.putString("selector", "@e[distance=..32,predicate=minecraft:missing]");
        try {
            saved(f, ChunkPos.ZERO, false, text(selector));
            f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain(); var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
            assertEquals(FullInitializedInputs.State.SATISFIED, graph.inputEvaluation().state(), graph.inputEvaluation().obligation());
            FullAuthoritativeFinishingTest.context(f); f.runtime.inputs.changed(); f.drain();
            assertInstanceOf(ChunkStageOutcome.Applied.class, graph.finishing.outcome().join());
            assertEquals("", FullSavedContextTestHooks.text(
                    assertInstanceOf(Display.TextDisplay.class, f.world.inserted.getFirst())).getString());
            assertEquals(0, f.world.biomeReads); assertFalse(f.barrier.tripped());
        } finally { f.stop(); FullFinishingServices.stopped(f.world.level.getServer()); }
        assertCleanup(f);
    }

    @Test void registeredBiomePredicateRunsItsOriginalLookupAndWorldReadOnce() throws Exception {
        var f = new FullRegistrationFixture(true); var lookup = new EntityLookup<Entity>();
        var candidate = EntityTypes.TEXT_DISPLAY.create(f.world.level, EntitySpawnReason.LOAD); assertNotNull(candidate);
        candidate.setId(731); candidate.setPos(8, 8, 8); candidate.setCustomName(Component.literal("plains candidate"));
        lookup.add(candidate); f.world.selectorEntities.add(candidate);
        var selector = new CompoundTag();
        selector.putString("selector", "@e[type=minecraft:text_display,distance=..32,predicate=phoenium:plains,limit=1]");
        try {
            saved(f, ChunkPos.ZERO, false, text(selector));
            f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain(); var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
            assertEquals(FullInitializedInputs.State.SATISFIED, graph.inputEvaluation().state(), graph.inputEvaluation().obligation());
            assertTrue(graph.inputReceipt().targets().stream().anyMatch(target ->
                    target.position().equals(ChunkPos.ZERO) && target.purposes().contains(FullInitializedInputs.Purpose.ENVIRONMENT)));
            assertEquals(0, f.world.biomeReads, "registered predicate discovery must not perform the world read");

            FullAuthoritativeFinishingTest.context(f); f.runtime.inputs.changed(); f.drain();
            assertInstanceOf(ChunkStageOutcome.Applied.class, graph.finishing.outcome().join());
            assertEquals("plains candidate", FullSavedContextTestHooks.text(
                    assertInstanceOf(Display.TextDisplay.class, f.world.inserted.getFirst())).getString());
            assertEquals(1, f.world.biomeReads); assertFalse(f.barrier.tripped());
        } finally {
            lookup.remove(candidate); f.stop(); FullFinishingServices.stopped(f.world.level.getServer());
        }
        assertCleanup(f);
    }

    @Test void reloadablePredicateRegistryReplacementStalesAndRebuildsTheExactPlan() throws Exception {
        var f = new FullRegistrationFixture(true); var selector = new CompoundTag();
        selector.putString("selector", "@e[distance=..32,predicate=minecraft:missing]");
        try {
            saved(f, ChunkPos.ZERO, false, text(selector));
            f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain(); var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
            var first = graph.inputReceipt(); assertNotNull(first);

            f.world.reloadableRegistries.set(new net.minecraft.server.ReloadableServerRegistries.Holder(
                    f.world.level.registryAccess()));
            assertEquals(FullInitializedInputs.State.STALE,
                    graph.validateInputs(graph.finishing.stage(), first).state());
            f.runtime.inputs.changed(); f.drain();
            assertNotNull(graph.inputReceipt()); assertNotSame(first, graph.inputReceipt());
            assertEquals(FullInitializedInputs.State.SATISFIED, graph.inputEvaluation().state());
        } finally { f.stop(); FullFinishingServices.stopped(f.world.level.getServer()); }
        assertCleanup(f);
    }

    @Test void foreignCandidateInsertionBeforeClaimStalesInsteadOfUsingTheActiveAppendAllowance() throws Exception {
        var f = new FullRegistrationFixture(true); var lookup = new EntityLookup<Entity>();
        var existing = EntityTypes.TEXT_DISPLAY.create(f.world.level, EntitySpawnReason.LOAD);
        var foreign = EntityTypes.TEXT_DISPLAY.create(f.world.level, EntitySpawnReason.LOAD);
        assertNotNull(existing); assertNotNull(foreign);
        existing.setId(741); existing.setPos(8, 8, 8); foreign.setId(742); foreign.setPos(9, 8, 8);
        lookup.add(existing); f.world.selectorEntities.add(existing);
        var selector = new CompoundTag(); selector.putString("selector", "@e[distance=..32]");
        try {
            saved(f, ChunkPos.ZERO, false, text(selector));
            f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain(); var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
            var first = graph.inputReceipt(); assertNotNull(first);

            lookup.add(foreign); f.world.selectorEntities.add(foreign);
            f.runtime.inputs.changed(); f.drain();
            assertNotNull(graph.inputReceipt()); assertNotSame(first, graph.inputReceipt());
            assertEquals(FullInitializedInputs.State.STALE,
                    graph.validateInputs(graph.finishing.stage(), first).state());
            assertEquals(FullInitializedInputs.State.SATISFIED, graph.inputEvaluation().state());
        } finally {
            lookup.remove(existing); lookup.remove(foreign); f.stop(); FullFinishingServices.stopped(f.world.level.getServer());
        }
        assertCleanup(f);
    }

    @Test void earlierSavedRootInsertionExtendsOnlyItsOwnActiveSelectorCut() throws Exception {
        var f = new FullRegistrationFixture(true); var position = new BlockPos(8, 8, 8);
        var state = Blocks.BEACON.defaultBlockState();
        f.input(ChunkPos.ZERO).getSection(0).setBlockState(8, 8, 8, state);
        var beacon = new BeaconBlockEntity(position, state); beacon.setCustomName(Component.literal("block "));
        f.input(ChunkPos.ZERO).setBlockEntity(beacon);

        var first = text(new CompoundTag()); first.putString("CustomName", "earlier saved root");
        var selector = new CompoundTag();
        selector.putString("selector", "@e[type=minecraft:text_display,distance=..32,limit=1]");
        var laterText = new CompoundTag(); laterText.putString("translate", "%s%s");
        var arguments = new ListTag(); arguments.add(blockText(position)); arguments.add(selector); laterText.put("with", arguments);
        var second = text(laterText);
        var selectorBodies = new AtomicInteger();
        try (var ignored = FullSavedContextTestHooks.selectorPrefix(selectorBodies::incrementAndGet)) {
            var proto = f.input(ChunkPos.ZERO); proto.getEntities().clear(); proto.addEntity(first); proto.addEntity(second);
            f.world.indexLegacyRoots = true;
            f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain(); var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
            assertEquals(FullInitializedInputs.State.SATISFIED, graph.inputEvaluation().state(), graph.inputEvaluation().obligation());

            FullAuthoritativeFinishingTest.context(f); f.runtime.inputs.changed(); f.drain();
            assertInstanceOf(ChunkStageOutcome.Applied.class, graph.finishing.outcome().join());
            assertEquals(2, f.world.inserted.size());
            assertEquals(1, selectorBodies.get());
            assertEquals(2, f.world.selectorEntities.size());
            assertEquals("block earlier saved root", FullSavedContextTestHooks.text(
                    assertInstanceOf(Display.TextDisplay.class, f.world.inserted.get(1))).getString());
            assertFalse(f.barrier.tripped());
        } finally { f.stop(); FullFinishingServices.stopped(f.world.level.getServer()); }
        assertCleanup(f);
    }

    @Test void liveEntityCustomNamePublishesNestedStorageBeforeOriginalSerializationAndResolution() throws Exception {
        var f = new FullRegistrationFixture(true); var lookup = new EntityLookup<Entity>();
        var data = new SavedDataStorage(directory, null, f.world.level.registryAccess());
        var commandStorage = new CommandStorage(data); set(f.world.level.getServer(), MinecraftServer.class, "commandStorage", commandStorage);
        var value = new CompoundTag(); value.putString("message", "live entity storage"); commandStorage.set(FIRST, value);
        var candidate = EntityTypes.TEXT_DISPLAY.create(f.world.level, EntitySpawnReason.LOAD); assertNotNull(candidate);
        candidate.setId(751); candidate.setPos(8, 8, 8); candidate.setCustomName(component(f, storageText(FIRST)));
        lookup.add(candidate); f.world.selectorEntities.add(candidate);
        try {
            saved(f, ChunkPos.ZERO, false, text(entityText("@e[type=minecraft:text_display,distance=..32,limit=1]", "CustomName", true)));
            f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain(); var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
            assertEquals(FullInitializedInputs.State.SATISFIED, graph.inputEvaluation().state(), graph.inputEvaluation().obligation());
            assertTrue(graph.inputReceipt().contexts().requirements("root[0]").size() >= 3);
            FullAuthoritativeFinishingTest.context(f); f.runtime.inputs.changed(); f.drain();
            assertInstanceOf(ChunkStageOutcome.Applied.class, graph.finishing.outcome().join());
            assertEquals("live entity storage", FullSavedContextTestHooks.text(
                    assertInstanceOf(Display.TextDisplay.class, f.world.inserted.getFirst())).getString());
            assertFalse(f.barrier.tripped());
        } finally {
            lookup.remove(candidate); f.stop(); FullFinishingServices.stopped(f.world.level.getServer()); data.close();
        }
        assertCleanup(f);
    }

    @Test void liveTextDisplayTextPublishesNestedStorageBeforeOriginalSerializationAndResolution() throws Exception {
        var f = new FullRegistrationFixture(true); var lookup = new EntityLookup<Entity>();
        var data = new SavedDataStorage(directory, null, f.world.level.registryAccess());
        var commandStorage = new CommandStorage(data); set(f.world.level.getServer(), MinecraftServer.class, "commandStorage", commandStorage);
        var value = new CompoundTag(); value.putString("message", "live display text"); commandStorage.set(FIRST, value);
        var candidate = assertInstanceOf(Display.TextDisplay.class,
                EntityTypes.TEXT_DISPLAY.create(f.world.level, EntitySpawnReason.LOAD));
        candidate.setId(752); candidate.setPos(8, 8, 8); candidate.setText(component(f, storageText(FIRST)));
        lookup.add(candidate); f.world.selectorEntities.add(candidate);
        try {
            saved(f, ChunkPos.ZERO, false, text(entityText("@e[type=minecraft:text_display,distance=..32,limit=1]", "text", true)));
            f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain(); var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
            assertEquals(FullInitializedInputs.State.SATISFIED, graph.inputEvaluation().state(), graph.inputEvaluation().obligation());
            FullAuthoritativeFinishingTest.context(f); f.runtime.inputs.changed(); f.drain();
            assertInstanceOf(ChunkStageOutcome.Applied.class, graph.finishing.outcome().join());
            assertEquals("live display text", FullSavedContextTestHooks.text(
                    assertInstanceOf(Display.TextDisplay.class, f.world.inserted.getFirst())).getString());
        } finally {
            lookup.remove(candidate); f.stop(); FullFinishingServices.stopped(f.world.level.getServer()); data.close();
        }
        assertCleanup(f);
    }

    @Test void directSiblingAliasMutationIsCaughtUnderTheFinalLeaseAndRebuildsDependencies() throws Exception {
        var f = new FullRegistrationFixture(true); var lookup = new EntityLookup<Entity>();
        var data = new SavedDataStorage(directory, null, f.world.level.registryAccess());
        var commandStorage = new CommandStorage(data); set(f.world.level.getServer(), MinecraftServer.class, "commandStorage", commandStorage);
        var firstValue = new CompoundTag(); firstValue.putString("message", "first live dependency"); commandStorage.set(FIRST, firstValue);
        var secondValue = new CompoundTag(); secondValue.putString("message", "second live dependency"); commandStorage.set(SECOND, secondValue);
        var root = Component.empty().append(component(f, storageText(FIRST)));
        var candidate = EntityTypes.TEXT_DISPLAY.create(f.world.level, EntitySpawnReason.LOAD); assertNotNull(candidate);
        candidate.setId(753); candidate.setPos(8, 8, 8); candidate.setCustomName(root);
        lookup.add(candidate); f.world.selectorEntities.add(candidate);
        try {
            saved(f, ChunkPos.ZERO, false, text(entityText("@e[type=minecraft:text_display,distance=..32,limit=1]", "CustomName", true)));
            f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain(); var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
            var first = graph.inputReceipt(); assertNotNull(first);
            root.getSiblings().set(0, component(f, storageText(SECOND)));
            FullAuthoritativeFinishingTest.context(f); f.runtime.inputs.changed(); f.drain();
            assertNotSame(first, graph.inputReceipt(), "same-lease validation must retire the stale structural receipt");
            assertInstanceOf(ChunkStageOutcome.Applied.class, graph.finishing.outcome().join());
            assertEquals("second live dependency", FullSavedContextTestHooks.text(
                    assertInstanceOf(Display.TextDisplay.class, f.world.inserted.getFirst())).getString());
            assertFalse(f.barrier.tripped());
        } finally {
            lookup.remove(candidate); f.stop(); FullFinishingServices.stopped(f.world.level.getServer()); data.close();
        }
        assertCleanup(f);
    }

    @Test void directTranslationArgumentMutationAndAbsentToPresentFieldBothRebuildBeforeOriginalBody() throws Exception {
        var f = new FullRegistrationFixture(true); var lookup = new EntityLookup<Entity>();
        var data = new SavedDataStorage(directory, null, f.world.level.registryAccess());
        var commandStorage = new CommandStorage(data); set(f.world.level.getServer(), MinecraftServer.class, "commandStorage", commandStorage);
        var firstValue = new CompoundTag(); firstValue.putString("message", "first argument"); commandStorage.set(FIRST, firstValue);
        var secondValue = new CompoundTag(); secondValue.putString("message", "second argument"); commandStorage.set(SECOND, secondValue);
        var translated = Component.translatable("%s", component(f, storageText(FIRST)));
        var firstCandidate = EntityTypes.TEXT_DISPLAY.create(f.world.level, EntitySpawnReason.LOAD);
        var absentCandidate = EntityTypes.TEXT_DISPLAY.create(f.world.level, EntitySpawnReason.LOAD);
        assertNotNull(firstCandidate); assertNotNull(absentCandidate);
        firstCandidate.setId(754); firstCandidate.setPos(8, 8, 8); firstCandidate.setCustomName(translated);
        absentCandidate.setId(755); absentCandidate.setPos(9, 8, 8);
        lookup.add(firstCandidate); lookup.add(absentCandidate); f.world.selectorEntities.add(firstCandidate); f.world.selectorEntities.add(absentCandidate);
        try {
            saved(f, ChunkPos.ZERO, false, text(entityText("@e[type=minecraft:text_display,distance=..32,sort=furthest,limit=2]", "CustomName", true)));
            f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain(); var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
            var first = graph.inputReceipt(); assertNotNull(first);
            assertInstanceOf(net.minecraft.network.chat.contents.TranslatableContents.class, translated.getContents())
                    .getArgs()[0] = component(f, storageText(SECOND));
            absentCandidate.setCustomName(Component.literal("now present"));
            FullAuthoritativeFinishingTest.context(f); f.runtime.inputs.changed(); f.drain();
            assertNotSame(first, graph.inputReceipt());
            assertInstanceOf(ChunkStageOutcome.Applied.class, graph.finishing.outcome().join());
            String result = FullSavedContextTestHooks.text(assertInstanceOf(Display.TextDisplay.class, f.world.inserted.getFirst())).getString();
            assertTrue(result.contains("second argument")); assertTrue(result.contains("now present"));
            assertFalse(f.barrier.tripped());
        } finally {
            lookup.remove(firstCandidate); lookup.remove(absentCandidate);
            f.stop(); FullFinishingServices.stopped(f.world.level.getServer()); data.close();
        }
        assertCleanup(f);
    }

    @Test void busyRegionalLeaseReleaseAutomaticallyWakesLiveDependencyRefinement() throws Exception {
        var f = new FullRegistrationFixture(true); var lookup = new EntityLookup<Entity>();
        var candidate = EntityTypes.TEXT_DISPLAY.create(f.world.level, EntitySpawnReason.LOAD); assertNotNull(candidate);
        candidate.setId(756); candidate.setPos(8, 8, 8); candidate.setCustomName(Component.literal("retained after busy owner"));
        lookup.add(candidate); f.world.selectorEntities.add(candidate);
        try {
            saved(f, ChunkPos.ZERO, false, text(entityText("@e[type=minecraft:text_display,distance=..32,limit=1]", "CustomName", true)));
            f.start(ChunkPos.ZERO, ChunkStatus.FULL);
            assertTrue(f.runtime.pumpOne(), "the original FULL delivery binds its retained input before refinement");
            try (var occupied = f.owner.leases().acquire()) {
                while (f.runtime.inputs.pump()) { }
                var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
                assertNull(graph.inputReceipt()); assertEquals(FullInitializedInputs.State.PENDING, graph.inputEvaluation().state(),
                        graph.inputEvaluation().obligation());
                assertTrue(graph.inputEvaluation().obligation().contains("live component dependency refinement"));
            }
            while (f.runtime.inputs.pump()) { }
            var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
            assertNotNull(graph.inputReceipt(), "lease release notification retries without an immediate dirty spin");
            assertEquals(FullInitializedInputs.State.SATISFIED, graph.inputEvaluation().state());
            assertFalse(f.owner.leases().hasActiveLease());
        } finally {
            lookup.remove(candidate); f.stop(); FullFinishingServices.stopped(f.world.level.getServer());
        }
        assertCleanup(f);
    }

    @Test void liveBeaconCustomNamePublishesNestedStorageBeforeOriginalImmediateGetter() throws Exception {
        var f = new FullRegistrationFixture(true); var position = new BlockPos(8, 8, 8);
        var data = new SavedDataStorage(directory, null, f.world.level.registryAccess());
        var commandStorage = new CommandStorage(data); set(f.world.level.getServer(), MinecraftServer.class, "commandStorage", commandStorage);
        var value = new CompoundTag(); value.putString("message", "live beacon storage"); commandStorage.set(FIRST, value);
        var state = Blocks.BEACON.defaultBlockState(); f.input(ChunkPos.ZERO).getSection(0).setBlockState(8, 8, 8, state);
        var beacon = new BeaconBlockEntity(position, state); beacon.setCustomName(component(f, storageText(FIRST)));
        f.input(ChunkPos.ZERO).setBlockEntity(beacon);
        try {
            saved(f, ChunkPos.ZERO, false, text(blockText(position)));
            f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain(); var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
            assertEquals(FullInitializedInputs.State.SATISFIED, graph.inputEvaluation().state(), graph.inputEvaluation().obligation());
            assertTrue(graph.inputReceipt().contexts().requirements("root[0]").size() >= 3);
            FullAuthoritativeFinishingTest.context(f); f.runtime.inputs.changed(); f.drain();
            assertInstanceOf(ChunkStageOutcome.Applied.class, graph.finishing.outcome().join());
            assertEquals("live beacon storage", FullSavedContextTestHooks.text(
                    assertInstanceOf(Display.TextDisplay.class, f.world.inserted.getFirst())).getString());
            assertFalse(f.barrier.tripped());
        } finally { f.stop(); FullFinishingServices.stopped(f.world.level.getServer()); data.close(); }
        assertCleanup(f);
    }

    @Test void cachedBlockComponentContextIsExplicitUntilAllOriginalEncoderProducersAreFenced() throws Exception {
        var f = new FullRegistrationFixture(true); var position = new BlockPos(8, 8, 8);
        var state = Blocks.BEACON.defaultBlockState(); f.input(ChunkPos.ZERO).getSection(0).setBlockState(8, 8, 8, state);
        var beacon = new BeaconBlockEntity(position, state);
        beacon.setComponents(DataComponentMap.builder().set(DataComponents.CUSTOM_NAME, Component.literal("cached boundary")).build());
        f.input(ChunkPos.ZERO).setBlockEntity(beacon);
        try {
            saved(f, ChunkPos.ZERO, false, text(blockText(position, "\"components\".\"minecraft:custom_name\"", true)));
            f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain(); var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
            assertEquals(FullInitializedInputs.State.UNRESOLVED, graph.inputEvaluation().state());
            assertTrue(graph.inputEvaluation().obligation().contains(
                    "another vanilla encoder can begin an equal-key load before the original serializer"));
            assertNull(graph.inputReceipt()); assertFalse(graph.finishing.outcome().isDone(),
                    "the unresolved cached input must not invoke the original FULL continuation");
            assertTrue(f.world.inserted.isEmpty()); assertFalse(f.barrier.tripped());
        } finally { f.stop(); FullFinishingServices.stopped(f.world.level.getServer()); }
        assertCleanup(f);
    }

    @Test void dependencyPathClassifierUnderstandsVanillaQuotedNamesMatchesIndicesAndWildcardsWithoutSelectingData() {
        assertEquals(List.of("front.text", "filtered_messages", "leaf"),
                FullNbtPathInputs.leadingFields("{root:{nested:[1,2]}}.\"front.text\"[{kind:\"value\"}].filtered_messages[].leaf[3]", 8));
        assertEquals(FullLiveComponentInputs.BlockField.SIGN_BACK_MESSAGES,
                FullLiveComponentInputs.blockField("back_text.messages[2]"));
        assertTrue(FullNbtPathInputs.cachedCustomName(
                "components{\"minecraft:custom_name\":{}}.\"minecraft:custom_name\""));
    }

    @Test void liveCommandBlockNameAndLastOutputPublishNestedStorageAtTheOriginalBlockSerializer() throws Exception {
        var f = new FullRegistrationFixture(true); var position = new BlockPos(8, 8, 8);
        var data = new SavedDataStorage(directory, net.minecraft.util.datafix.DataFixers.getDataFixer(), f.world.level.registryAccess());
        var storage = new CommandStorage(data); set(f.world.level.getServer(), MinecraftServer.class, "commandStorage", storage);
        var first = new CompoundTag(); first.putString("message", "command name "); storage.set(FIRST, first);
        var second = new CompoundTag(); second.putString("message", "command output"); storage.set(SECOND, second);
        var state = Blocks.COMMAND_BLOCK.defaultBlockState(); f.input(ChunkPos.ZERO).getSection(0).setBlockState(8, 8, 8, state);
        var command = new CommandBlockEntity(position, state);
        command.getCommandBlock().setCustomName(component(f, storageText(FIRST)));
        command.getCommandBlock().setLastOutput(component(f, storageText(SECOND)));
        f.input(ChunkPos.ZERO).setBlockEntity(command);
        var root = new CompoundTag(); root.putString("text", ""); var extra = new ListTag();
        extra.add(blockText(position, "CustomName", true)); extra.add(blockText(position, "LastOutput", true)); root.put("extra", extra);
        try {
            saved(f, ChunkPos.ZERO, false, text(root)); f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain();
            var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
            assertEquals(FullInitializedInputs.State.SATISFIED, graph.inputEvaluation().state(), graph.inputEvaluation().obligation());
            FullAuthoritativeFinishingTest.context(f); f.runtime.inputs.changed(); f.drain();
            assertInstanceOf(ChunkStageOutcome.Applied.class, graph.finishing.outcome().join());
            assertEquals("command name command output", FullSavedContextTestHooks.text(
                    assertInstanceOf(Display.TextDisplay.class, f.world.inserted.getFirst())).getString());
            assertSame(command, graph.prepared().orElseThrow().actual().getBlockEntity(position)); assertFalse(f.barrier.tripped());
        } finally { f.stop(); FullFinishingServices.stopped(f.world.level.getServer()); data.close(); }
        assertCleanup(f);
    }

    @Test void commandOutputPresenceGateRebuildsToTheOriginalAbsentPathWhenTrackingIsDisabled() throws Exception {
        var f = new FullRegistrationFixture(true); var position = new BlockPos(8, 8, 8);
        var state = Blocks.COMMAND_BLOCK.defaultBlockState(); f.input(ChunkPos.ZERO).getSection(0).setBlockState(8, 8, 8, state);
        var command = new CommandBlockEntity(position, state);
        command.getCommandBlock().setLastOutput(Component.literal("initial output")); f.input(ChunkPos.ZERO).setBlockEntity(command);
        try {
            saved(f, ChunkPos.ZERO, false, text(blockText(position, "LastOutput", true)));
            f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain(); var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
            var firstReceipt = graph.inputReceipt(); assertNotNull(firstReceipt);
            command.getCommandBlock().setTrackOutput(false);
            FullAuthoritativeFinishingTest.context(f); f.runtime.inputs.changed(); f.drain();
            assertNotSame(firstReceipt, graph.inputReceipt(), "TrackOutput changes whether the original serializer emits LastOutput");
            assertInstanceOf(ChunkStageOutcome.Applied.class, graph.finishing.outcome().join());
            assertEquals("", FullSavedContextTestHooks.text(
                    assertInstanceOf(Display.TextDisplay.class, f.world.inserted.getFirst())).getString());
            assertFalse(f.barrier.tripped());
        } finally { f.stop(); FullFinishingServices.stopped(f.world.level.getServer()); }
        assertCleanup(f);
    }

    @Test void liveSignArrayAliasesAndSideReplacementRebuildBeforeOriginalFilteredAndBackTextSelection() throws Exception {
        var f = new FullRegistrationFixture(true); var position = new BlockPos(8, 8, 8);
        var data = new SavedDataStorage(directory, net.minecraft.util.datafix.DataFixers.getDataFixer(), f.world.level.registryAccess());
        var storage = new CommandStorage(data); set(f.world.level.getServer(), MinecraftServer.class, "commandStorage", storage);
        var first = new CompoundTag(); first.putString("message", "first"); storage.set(FIRST, first);
        var second = new CompoundTag(); second.putString("message", "second"); storage.set(SECOND, second);
        var state = Blocks.OAK_SIGN.defaultBlockState(); f.input(ChunkPos.ZERO).getSection(0).setBlockState(8, 8, 8, state);
        var sign = new SignBlockEntity(position, state);
        var front = new SignText().setMessage(0, Component.literal("raw"), component(f, storageText(FIRST)));
        var back = new SignText().setMessage(1, component(f, storageText(FIRST)));
        set(sign, SignBlockEntity.class, "frontText", front); set(sign, SignBlockEntity.class, "backText", back);
        f.input(ChunkPos.ZERO).setBlockEntity(sign);
        var root = new CompoundTag(); root.putString("text", ""); var extra = new ListTag();
        extra.add(blockText(position, "\"front_text\".\"filtered_messages\"[0]", true));
        extra.add(blockText(position, "back_text.messages[]", true)); root.put("extra", extra);
        try {
            saved(f, ChunkPos.ZERO, false, text(root)); f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain();
            var graph = f.runtime.graph(f.holder(ChunkPos.ZERO)); var firstReceipt = graph.inputReceipt(); assertNotNull(firstReceipt);
            front.getMessages(true)[0] = component(f, storageText(SECOND));
            set(sign, SignBlockEntity.class, "backText", sign.getBackText().setMessage(1, component(f, storageText(SECOND))));
            FullAuthoritativeFinishingTest.context(f); f.runtime.inputs.changed(); f.drain();
            assertNotSame(firstReceipt, graph.inputReceipt(), "array-slot and side-field aliases must rebuild the dependency receipt");
            assertInstanceOf(ChunkStageOutcome.Applied.class, graph.finishing.outcome().join());
            assertEquals("second, second, , ", FullSavedContextTestHooks.text(
                    assertInstanceOf(Display.TextDisplay.class, f.world.inserted.getFirst())).getString());
            assertFalse(f.barrier.tripped());
        } finally { f.stop(); FullFinishingServices.stopped(f.world.level.getServer()); data.close(); }
        assertCleanup(f);
    }

    @Test void liveMannequinDescriptionReplacementRebuildsBeforeOriginalEntitySerialization() throws Exception {
        var f = new FullRegistrationFixture(true); var lookup = new EntityLookup<Entity>();
        var data = new SavedDataStorage(directory, net.minecraft.util.datafix.DataFixers.getDataFixer(), f.world.level.registryAccess());
        var storage = new CommandStorage(data); set(f.world.level.getServer(), MinecraftServer.class, "commandStorage", storage);
        var first = new CompoundTag(); first.putString("message", "first description"); storage.set(FIRST, first);
        var second = new CompoundTag(); second.putString("message", "second description"); storage.set(SECOND, second);
        var mannequin = EntityTypes.MANNEQUIN.create(f.world.level, EntitySpawnReason.LOAD); assertNotNull(mannequin);
        mannequin.setId(759); mannequin.setPos(8, 8, 8); setMannequinDescription(mannequin, component(f, storageText(FIRST)));
        lookup.add(mannequin); f.world.selectorEntities.add(mannequin);
        try {
            saved(f, ChunkPos.ZERO, false, text(entityText("@e[type=minecraft:mannequin,distance=..32,limit=1]", "\"description\"", true)));
            f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain(); var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
            var firstReceipt = graph.inputReceipt(); assertNotNull(firstReceipt);
            setMannequinDescription(mannequin, component(f, storageText(SECOND)));
            FullAuthoritativeFinishingTest.context(f); f.runtime.inputs.changed(); f.drain();
            assertNotSame(firstReceipt, graph.inputReceipt());
            assertInstanceOf(ChunkStageOutcome.Applied.class, graph.finishing.outcome().join());
            assertEquals("second description", FullSavedContextTestHooks.text(
                    assertInstanceOf(Display.TextDisplay.class, f.world.inserted.getFirst())).getString());
            assertFalse(f.barrier.tripped());
        } finally {
            lookup.remove(mannequin); f.stop(); FullFinishingServices.stopped(f.world.level.getServer()); data.close();
        }
        assertCleanup(f);
    }

    @Test void retainedSavedMannequinDescriptionPublishesDependenciesBeforeTheLaterOriginalSelector() throws Exception {
        var f = new FullRegistrationFixture(true);
        var data = new SavedDataStorage(directory, net.minecraft.util.datafix.DataFixers.getDataFixer(), f.world.level.registryAccess());
        var storage = new CommandStorage(data); set(f.world.level.getServer(), MinecraftServer.class, "commandStorage", storage);
        var value = new CompoundTag(); value.putString("message", "saved mannequin description"); storage.set(FIRST, value);
        var mannequin = tag(8, 8, 0); mannequin.putString("id", "minecraft:mannequin"); mannequin.put("description", storageText(FIRST));
        var display = text(entityText("@e[type=minecraft:mannequin,distance=..32,limit=1]", "description", true));
        var proto = f.input(ChunkPos.ZERO); proto.getEntities().clear(); proto.addEntity(mannequin); proto.addEntity(display);
        f.world.indexLegacyRoots = true;
        try {
            f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain(); var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
            assertEquals(FullInitializedInputs.State.SATISFIED, graph.inputEvaluation().state(), graph.inputEvaluation().obligation());
            FullAuthoritativeFinishingTest.context(f); f.runtime.inputs.changed(); f.drain();
            assertInstanceOf(ChunkStageOutcome.Applied.class, graph.finishing.outcome().join());
            assertEquals(2, f.world.inserted.size());
            assertEquals("saved mannequin description", FullSavedContextTestHooks.text(
                    assertInstanceOf(Display.TextDisplay.class, f.world.inserted.get(1))).getString());
            assertFalse(f.barrier.tripped());
        } finally { f.stop(); FullFinishingServices.stopped(f.world.level.getServer()); data.close(); }
        assertCleanup(f);
    }

    @Test void hiddenSavedMannequinKeepsTheOriginalAbsentDescriptionWithoutDemandingItsStoredSyntax() throws Exception {
        var f = new FullRegistrationFixture(true); var unreachable = new BlockPos(1600, 8, 8);
        var mannequin = tag(8, 8, 0); mannequin.putString("id", "minecraft:mannequin");
        mannequin.putBoolean("hide_description", true); mannequin.put("description", blockText(unreachable));
        var display = text(entityText("@e[type=minecraft:mannequin,distance=..32,limit=1]", "description", true));
        var proto = f.input(ChunkPos.ZERO); proto.getEntities().clear(); proto.addEntity(mannequin); proto.addEntity(display);
        f.world.indexLegacyRoots = true;
        try {
            f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain(); var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
            assertEquals(FullInitializedInputs.State.SATISFIED, graph.inputEvaluation().state(), graph.inputEvaluation().obligation());
            assertFalse(graph.inputReceipt().targets().stream().anyMatch(target -> target.position().equals(new ChunkPos(100, 0))));
            FullAuthoritativeFinishingTest.context(f); f.runtime.inputs.changed(); f.drain();
            assertInstanceOf(ChunkStageOutcome.Applied.class, graph.finishing.outcome().join());
            assertEquals("", FullSavedContextTestHooks.text(
                    assertInstanceOf(Display.TextDisplay.class, f.world.inserted.get(1))).getString());
            assertFalse(f.barrier.tripped());
        } finally { f.stop(); FullFinishingServices.stopped(f.world.level.getServer()); }
        assertCleanup(f);
    }

    @Test void signDependencyCapacityIsCheckedBeforeAnyArrayOrComponentCopies() throws Exception {
        var f = new FullRegistrationFixture(true); var position = new BlockPos(8, 8, 8);
        var state = Blocks.OAK_SIGN.defaultBlockState(); var sign = new SignBlockEntity(position, state);
        var front = new SignText().setMessage(0, Component.literal("raw"), Component.literal("filtered"));
        set(sign, SignBlockEntity.class, "frontText", front);
        var fact = new FullSavedInput.EntityFact("capacity", "minecraft:text_display", 8, 8, 8, 0, false);
        try {
            var measured = FullLiveComponentInputs.measureBlock(sign,
                    FullLiveComponentInputs.BlockField.SIGN_FRONT_FILTERED_MESSAGES, 0);
            var refused = FullLiveComponentInputs.captureBlock(fact, sign,
                    FullLiveComponentInputs.BlockField.SIGN_FRONT_FILTERED_MESSAGES, 0, measured.bytes() - 1);
            assertTrue(refused.unmet().contains("grew after")); assertTrue(refused.components().isEmpty());
            assertNull(refused.firstValues()); assertNull(refused.secondValues());

            var captured = FullLiveComponentInputs.captureBlock(fact, sign,
                    FullLiveComponentInputs.BlockField.SIGN_FRONT_FILTERED_MESSAGES, 0, measured.bytes());
            assertNull(captured.unmet()); assertEquals(8, captured.components().size()); assertTrue(captured.valid(sign));
            front.getMessages(false)[0] = Component.literal("raw replacement");
            assertFalse(captured.valid(sign), "the retained raw/filtered equality decision includes both exposed arrays");
        } finally { f.stop(); FullFinishingServices.stopped(f.world.level.getServer()); }
        assertCleanup(f);
    }

    @Test void laterSavedSelectorChargesOneAdditionalCandidateForEachPossibleConversionProduct() throws Exception {
        var f = new FullRegistrationFixture(true);
        var tadpole = FullInitializedInputBindingTest.tag(8, 8, 24000);
        tadpole.remove("AgeLocked");
        tadpole.putString("CustomName", "converted frog");
        var selector = new CompoundTag(); selector.putString("selector", "@e[type=minecraft:frog,distance=..32,limit=1]");
        try {
            var proto = f.input(ChunkPos.ZERO); proto.getEntities().clear(); proto.addEntity(tadpole); proto.addEntity(text(selector));
            f.world.indexLegacyRoots = true;
            f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain(); var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
            var contexts = graph.inputReceipt().contexts();
            assertEquals(3, field(contexts, FullSavedContextInputs.Plan.class, "maximumSelectorCandidates"),
                    "one saved conversion may retain its tadpole alias and add one constructed frog");
            assertEquals(FullInitializedInputs.State.SATISFIED, graph.inputEvaluation().state());
        } finally { f.stop(); FullFinishingServices.stopped(f.world.level.getServer()); }
        assertCleanup(f);
    }

    @Test void reentrantLiveTargetRetirementCannotInstallTheDetachedReadAndReleasesEveryTemporaryPin() throws Exception {
        var f = new FullRegistrationFixture(true); var source = ChunkPos.ZERO; var target = new ChunkPos(1, 0);
        var position = new BlockPos(24, 8, 8); var state = Blocks.BEACON.defaultBlockState();
        f.input(target).getSection(0).setBlockState(8, 8, 8, state);
        var beacon = new BeaconBlockEntity(position, state); beacon.setCustomName(Component.literal("must not install"));
        f.input(target).setBlockEntity(beacon);
        var retired = new AtomicInteger(); var retiredGraph = new java.util.concurrent.atomic.AtomicReference<FullRegistrationRuntime.Graph>();
        try (var ignored = FullSavedContextTestHooks.liveBlockPrefix(() -> {
            if (retired.getAndIncrement() != 0) return;
            var targetGraph = f.runtime.graph(f.holder(target));
            retiredGraph.set(targetGraph);
            assertTrue(targetGraph.preparedConsumers() > 0, "live observation retains the exact target preparation");
            f.runtime.retire(targetGraph.holder);
            assertTrue(targetGraph.prepared().isPresent(), "the target preparation stays retained until the field read exits");
        })) {
            saved(f, source, false, text(blockText(position)));
            saved(f, target, false, text(new CompoundTag()));
            f.start(source, ChunkStatus.FULL); f.start(target, ChunkStatus.FULL); f.drain();
            var sourceGraph = f.runtime.graph(f.holder(source));
            assertTrue(retired.get() >= 1); assertNull(sourceGraph.inputReceipt());
            assertNotEquals(FullInitializedInputs.State.SATISFIED, sourceGraph.inputEvaluation().state());
            assertEquals(0L, f.runtime.inputs.measurements().get("consumers"),
                    "failed refinement and retired target acknowledge every temporary input pin");
            assertEquals(0, retiredGraph.get().preparedConsumers());
            assertFalse(f.owner.leases().hasActiveLease());
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
    private static Component component(FullRegistrationFixture fixture, net.minecraft.nbt.Tag encoded) {
        return net.minecraft.network.chat.ComponentSerialization.CODEC.parse(
                fixture.world.level.registryAccess().createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE), encoded)
                .result().orElseThrow();
    }
    private static void setMannequinDescription(Mannequin mannequin, Component description) {
        try {
            var method = Mannequin.class.getDeclaredMethod("setDescription", Component.class);
            method.setAccessible(true); method.invoke(mannequin, description);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
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
