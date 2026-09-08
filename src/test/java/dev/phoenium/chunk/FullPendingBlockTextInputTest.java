package dev.phoenium.chunk;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Display;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.CommandBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.storage.CommandStorage;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static dev.phoenium.chunk.FullDecisionWitnessSupport.*;
import static dev.phoenium.chunk.FullInitializedInputBindingTest.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Pending block text coverage deliberately starts with only serialized block-entity NBT.
 * The command/sign instances are created by the original chunk post-load path; discovery
 * must retain the pending tag, and finishing must consume the original serializer output.
 */
class FullPendingBlockTextInputTest {
    private static final Identifier FIRST = Identifier.parse("phoenium:pending_first");
    private static final Identifier SECOND = Identifier.parse("phoenium:pending_second");

    @TempDir Path directory;

    @BeforeAll static void bootstrap() { FullSavedVillagerInputTest.bootstrap(); }

    @Test void pendingCommandBlockUsesOriginalLazyLoadAndSerializerForBothTextFields() throws Exception {
        var f = new FullRegistrationFixture(true);
        var position = new BlockPos(8, 8, 8);
        var data = new SavedDataStorage(directory, null, f.world.level.registryAccess());
        var storage = new CommandStorage(data);
        set(f.world.level.getServer(), net.minecraft.server.MinecraftServer.class, "commandStorage", storage);
        var first = new CompoundTag(); first.putString("message", "command name "); storage.set(FIRST, first);
        var second = new CompoundTag(); second.putString("message", "command output"); storage.set(SECOND, second);
        var state = Blocks.COMMAND_BLOCK.defaultBlockState();
        f.input(ChunkPos.ZERO).getSection(0).setBlockState(8, 8, 8, state);
        var command = new CommandBlockEntity(position, state);
        command.getCommandBlock().setTrackOutput(true);
        command.getCommandBlock().setCustomName(component(f, storageText(FIRST)));
        command.getCommandBlock().setLastOutput(component(f, storageText(SECOND)));
        CompoundTag pending = command.saveWithFullMetadata(f.world.level.registryAccess());
        f.input(ChunkPos.ZERO).setBlockEntityNbt(pending);
        var root = new CompoundTag(); root.putString("text", "");
        var extra = new ListTag();
        extra.add(blockText(position, "CustomName", true));
        extra.add(blockText(position, "LastOutput", true));
        root.put("extra", extra);
        try {
            saved(f, ChunkPos.ZERO, false, text(root));
            f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain();
            var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
            assertEquals(FullInitializedInputs.State.SATISFIED, graph.inputEvaluation().state(), graph.inputEvaluation().obligation());
            assertNotNull(graph.inputReceipt());
            assertEquals(1, graph.inputReceipt().contexts().blockTagCaptures(),
                    "discovery must retain the serialized pending tag before lazy block creation");
            assertEquals(0, f.world.nextId, "pending discovery must not construct a command block");
            assertTrue(f.world.inserted.isEmpty());

            FullAuthoritativeFinishingTest.context(f); f.runtime.inputs.changed(); f.drain();
            var finishing = graph.finishing.outcome().join();
            assertInstanceOf(ChunkStageOutcome.Applied.class, finishing, String.valueOf(finishing));
            var publication = graph.publication.outcome().join();
            assertInstanceOf(ChunkStageOutcome.Applied.class, publication, String.valueOf(publication));
            assertEquals("command name command output", FullSavedContextTestHooks.text(
                    assertInstanceOf(Display.TextDisplay.class, f.world.inserted.getFirst())).getString());
            assertInstanceOf(CommandBlockEntity.class, graph.prepared().orElseThrow().actual().getBlockEntity(position));
            assertFalse(f.barrier.tripped());
        } finally {
            f.stop(); FullFinishingServices.stopped(f.world.level.getServer()); data.close();
        }
        assertCleanup(f);
    }

    @Test void pendingSignUsesOriginalLazyLoadAndSerializerForInterpretedMessageSlot() throws Exception {
        var f = new FullRegistrationFixture(true);
        var position = new BlockPos(8, 8, 8);
        var data = new SavedDataStorage(directory, null, f.world.level.registryAccess());
        var storage = new CommandStorage(data);
        set(f.world.level.getServer(), net.minecraft.server.MinecraftServer.class, "commandStorage", storage);
        var value = new CompoundTag(); value.putString("message", "sign storage"); storage.set(FIRST, value);
        var state = Blocks.OAK_SIGN.defaultBlockState();
        f.input(ChunkPos.ZERO).getSection(0).setBlockState(8, 8, 8, state);
        var sign = new SignBlockEntity(position, state);
        var front = new SignText().setMessage(0, component(f, storageText(FIRST)));
        set(sign, SignBlockEntity.class, "frontText", front);
        CompoundTag pending = sign.saveWithFullMetadata(f.world.level.registryAccess());
        f.input(ChunkPos.ZERO).setBlockEntityNbt(pending);
        var root = new CompoundTag(); root.putString("text", "");
        var extra = new ListTag(); extra.add(blockText(position, "front_text.messages[0]", true)); root.put("extra", extra);
        try {
            saved(f, ChunkPos.ZERO, false, text(root));
            f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain();
            var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
            assertEquals(FullInitializedInputs.State.SATISFIED, graph.inputEvaluation().state(), graph.inputEvaluation().obligation());
            assertNotNull(graph.inputReceipt());
            assertEquals(1, graph.inputReceipt().contexts().blockTagCaptures());
            assertEquals(0, f.world.nextId, "pending discovery must not construct a sign");
            assertTrue(f.world.inserted.isEmpty());

            FullAuthoritativeFinishingTest.context(f); f.runtime.inputs.changed(); f.drain();
            var finishing = graph.finishing.outcome().join();
            assertInstanceOf(ChunkStageOutcome.Applied.class, finishing, String.valueOf(finishing));
            var publication = graph.publication.outcome().join();
            assertInstanceOf(ChunkStageOutcome.Applied.class, publication, String.valueOf(publication));
            assertEquals("sign storage", FullSavedContextTestHooks.text(
                    assertInstanceOf(Display.TextDisplay.class, f.world.inserted.getFirst())).getString());
            assertInstanceOf(SignBlockEntity.class, graph.prepared().orElseThrow().actual().getBlockEntity(position));
            assertFalse(f.barrier.tripped());
        } finally {
            f.stop(); FullFinishingServices.stopped(f.world.level.getServer()); data.close();
        }
        assertCleanup(f);
    }

    @Test void pendingTagOccurrenceAndAbsentReceiptsInvalidateOnExactVersionedReplacement() throws Exception {
        var f = new FullRegistrationFixture(true);
        var position = new BlockPos(8, 8, 8);
        var absentPosition = new BlockPos(9, 8, 8);
        var original = pendingBeacon(position, net.minecraft.nbt.StringTag.valueOf("first"));
        f.input(ChunkPos.ZERO).setBlockEntityNbt(original);
        var changed = new AtomicInteger();
        AutoCloseable retained = null, absentRetained = null;
        try {
            var current = FullBlockEntityTagInputs.observeVersioned(f.input(ChunkPos.ZERO), position,
                    f.world.level.registryAccess(), changed::incrementAndGet, ignored -> true);
            assertNotNull(current.capture); assertNull(current.absence);
            retained = current.capture.retain(changed::incrementAndGet);
            var absent = FullBlockEntityTagInputs.observeVersioned(f.input(ChunkPos.ZERO), absentPosition,
                    f.world.level.registryAccess(), changed::incrementAndGet, ignored -> true);
            assertNull(absent.capture); assertNotNull(absent.absence);
            absentRetained = absent.absence.retain(changed::incrementAndGet);
            original.put("CustomName", net.minecraft.nbt.StringTag.valueOf("aliased mutation"));
            assertFalse(current.capture.valid()); assertEquals(1, changed.get(),
                    "an alias mutation invalidates the retained occurrence version");
            f.input(ChunkPos.ZERO).setBlockEntityNbt(pendingBeacon(position,
                    net.minecraft.nbt.StringTag.valueOf("replacement")));
            assertFalse(current.capture.valid()); assertEquals(2, changed.get());
            f.input(ChunkPos.ZERO).setBlockEntityNbt(pendingBeacon(absentPosition,
                    net.minecraft.nbt.StringTag.valueOf("new occurrence")));
            assertFalse(absent.absence.valid()); assertEquals(3, changed.get());
        } finally {
            if (retained != null) retained.close(); if (absentRetained != null) absentRetained.close();
            f.stop(); FullFinishingServices.stopped(f.world.level.getServer());
        }
        assertCleanup(f);
    }

    @Test void pendingTagCapacityRefusalPrecedesDetachedComponentCopy() throws Exception {
        var f = new FullRegistrationFixture(true);
        var position = new BlockPos(8, 8, 8);
        var tag = pendingBeacon(position, net.minecraft.nbt.StringTag.valueOf("capacity"));
        f.input(ChunkPos.ZERO).setBlockEntityNbt(tag);
        try {
            var source = FullSavedCollections.source(tag); assertNotNull(source);
            assertThrows(FullBlockEntityTagInputs.Capacity.class, () ->
                    FullBlockEntityTagInputs.observe(f.input(ChunkPos.ZERO), position,
                            f.world.level.registryAccess(), () -> { }, ignored -> false));
            assertNull(source.snapshot(), "capacity refusal must precede detached pending-tag copying");
        } finally { f.stop(); FullFinishingServices.stopped(f.world.level.getServer()); }
        assertCleanup(f);
    }

    @Test void pendingTagNotificationPreservesFirstFailureAndReleasesAllConsumers() throws Exception {
        var f = new FullRegistrationFixture(true);
        var position = new BlockPos(8, 8, 8);
        f.input(ChunkPos.ZERO).setBlockEntityNbt(pendingBeacon(position,
                net.minecraft.nbt.StringTag.valueOf("failure")));
        var firstFailure = new IllegalStateException("first pending-tag consumer failure");
        var secondFailure = new IllegalArgumentException("second pending-tag consumer failure");
        AutoCloseable first = null, second = null; var secondCalls = new AtomicInteger();
        try {
            var capture = FullBlockEntityTagInputs.observe(f.input(ChunkPos.ZERO), position,
                    f.world.level.registryAccess(), () -> { }, ignored -> true);
            first = capture.retain(() -> { throw firstFailure; });
            second = capture.retain(() -> { secondCalls.incrementAndGet(); throw secondFailure; });
            var thrown = assertThrows(IllegalStateException.class, () ->
                    f.input(ChunkPos.ZERO).setBlockEntityNbt(pendingBeacon(position,
                            net.minecraft.nbt.StringTag.valueOf("replacement"))));
            assertSame(firstFailure, thrown); assertArrayEquals(new Throwable[]{secondFailure}, thrown.getSuppressed());
            assertEquals(1, secondCalls.get()); assertFalse(capture.valid());
        } finally {
            if (first != null) first.close(); if (second != null) second.close();
            f.stop(); FullFinishingServices.stopped(f.world.level.getServer());
        }
        assertCleanup(f);
    }

    @Test void pendingSignWildcardBindsEverySerializedSlotAndBothNestedStorageIds() throws Exception {
        var f = new FullRegistrationFixture(true);
        var position = new BlockPos(8, 8, 8);
        var data = new SavedDataStorage(directory, null, f.world.level.registryAccess());
        var storage = new CommandStorage(data);
        set(f.world.level.getServer(), net.minecraft.server.MinecraftServer.class, "commandStorage", storage);
        var first = new CompoundTag(); first.putString("message", "first"); storage.set(FIRST, first);
        var second = new CompoundTag(); second.putString("message", "second"); storage.set(SECOND, second);
        var state = Blocks.OAK_SIGN.defaultBlockState(); f.input(ChunkPos.ZERO).getSection(0).setBlockState(8, 8, 8, state);
        var sign = new SignBlockEntity(position, state);
        var front = new SignText().setMessage(0, component(f, storageText(FIRST)))
                .setMessage(1, component(f, storageText(SECOND)));
        set(sign, SignBlockEntity.class, "frontText", front);
        f.input(ChunkPos.ZERO).setBlockEntityNbt(sign.saveWithFullMetadata(f.world.level.registryAccess()));
        var root = new CompoundTag(); root.putString("text", "");
        var extra = new ListTag(); extra.add(blockText(position, "front_text.messages[]", true)); root.put("extra", extra);
        try {
            saved(f, ChunkPos.ZERO, false, text(root)); f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain();
            var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
            assertEquals(FullInitializedInputs.State.SATISFIED, graph.inputEvaluation().state(), graph.inputEvaluation().obligation());
            var requirements = graph.inputReceipt().contexts().requirements("root[0]").toString();
            assertTrue(requirements.contains(FIRST.toString()) && requirements.contains(SECOND.toString()), requirements);
            FullAuthoritativeFinishingTest.context(f); f.runtime.inputs.changed(); f.drain();
            var finishing = graph.finishing.outcome().join();
            assertInstanceOf(ChunkStageOutcome.Applied.class, finishing, String.valueOf(finishing));
            assertEquals("first, second, , ", FullSavedContextTestHooks.text(
                    assertInstanceOf(Display.TextDisplay.class, f.world.inserted.getFirst())).getString());
            assertFalse(f.barrier.tripped());
        } finally { f.stop(); FullFinishingServices.stopped(f.world.level.getServer()); data.close(); }
        assertCleanup(f);
    }

    @Test void pendingCommandTrackOutputFalseLeavesLastOutputDependencyAbsent() throws Exception {
        var f = new FullRegistrationFixture(true);
        var position = new BlockPos(8, 8, 8);
        var data = new SavedDataStorage(directory, null, f.world.level.registryAccess());
        var storage = new CommandStorage(data);
        set(f.world.level.getServer(), net.minecraft.server.MinecraftServer.class, "commandStorage", storage);
        var value = new CompoundTag(); value.putString("message", "must not be read"); storage.set(SECOND, value);
        var state = Blocks.COMMAND_BLOCK.defaultBlockState(); f.input(ChunkPos.ZERO).getSection(0).setBlockState(8, 8, 8, state);
        var command = new CommandBlockEntity(position, state);
        command.getCommandBlock().setTrackOutput(false);
        command.getCommandBlock().setLastOutput(component(f, storageText(SECOND)));
        f.input(ChunkPos.ZERO).setBlockEntityNbt(command.saveWithFullMetadata(f.world.level.registryAccess()));
        var root = new CompoundTag(); root.putString("text", "");
        var extra = new ListTag(); extra.add(blockText(position, "LastOutput", true)); root.put("extra", extra);
        try {
            saved(f, ChunkPos.ZERO, false, text(root)); f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain();
            var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
            assertEquals(FullInitializedInputs.State.SATISFIED, graph.inputEvaluation().state(), graph.inputEvaluation().obligation());
            FullAuthoritativeFinishingTest.context(f); f.runtime.inputs.changed(); f.drain();
            var finishing = graph.finishing.outcome().join();
            assertInstanceOf(ChunkStageOutcome.Applied.class, finishing, String.valueOf(finishing));
            assertEquals("", FullSavedContextTestHooks.text(
                    assertInstanceOf(Display.TextDisplay.class, f.world.inserted.getFirst())).getString());
            assertFalse(f.barrier.tripped());
        } finally { f.stop(); FullFinishingServices.stopped(f.world.level.getServer()); data.close(); }
        assertCleanup(f);
    }

    @Test void pendingFieldScalarCapacityRefusalPrecedesEverySlotCopy() throws Exception {
        var f = new FullRegistrationFixture(true);
        var position = new BlockPos(8, 8, 8);
        var state = Blocks.OAK_SIGN.defaultBlockState();
        var sign = new SignBlockEntity(position, state);
        set(sign, SignBlockEntity.class, "frontText", new SignText().setMessage(0, Component.literal("slot0"))
                .setMessage(1, Component.literal("slot1")));
        var tag = sign.saveWithFullMetadata(f.world.level.registryAccess());
        f.input(ChunkPos.ZERO).setBlockEntityNbt(tag);
        try {
            var capture = FullBlockEntityTagInputs.observe(f.input(ChunkPos.ZERO), position,
                    f.world.level.registryAccess(), () -> { }, ignored -> true);
            assertThrows(FullBlockEntityTagInputs.Capacity.class,
                    () -> capture.components("front_text.messages[]", ignored -> false, () -> { }));
            assertTrue(capture.valid(), "scalar refusal must leave the versioned capture available for retry");
        } finally { f.stop(); FullFinishingServices.stopped(f.world.level.getServer()); }
        assertCleanup(f);
    }

    @Test void pendingFieldReservationMutationRetriesBeforeAnyStaleSlotCopy() throws Exception {
        var f = new FullRegistrationFixture(true);
        var position = new BlockPos(8, 8, 8);
        var state = Blocks.OAK_SIGN.defaultBlockState();
        var sign = new SignBlockEntity(position, state);
        set(sign, SignBlockEntity.class, "frontText", new SignText().setMessage(0, Component.literal("before")));
        var tag = sign.saveWithFullMetadata(f.world.level.registryAccess());
        f.input(ChunkPos.ZERO).setBlockEntityNbt(tag);
        AutoCloseable waiter = null;
        try {
            var capture = FullBlockEntityTagInputs.observe(f.input(ChunkPos.ZERO), position,
                    f.world.level.registryAccess(), () -> { }, ignored -> true);
            var mutated = new AtomicBoolean();
            var retry = assertThrows(FullBlockEntityTagInputs.Retry.class, () -> capture.components(
                    "front_text.messages[]", ignored -> {
                        if (mutated.compareAndSet(false, true)) {
                            var front = tag.getCompound("front_text").orElseThrow();
                            front.getListOrEmpty("messages").set(0,
                                    net.minecraft.nbt.StringTag.valueOf("grown during reservation"));
                        }
                        return true;
                    }, () -> { }));
            waiter = retry.waiter;
            assertFalse(capture.valid(), "reservation mutation must stale the original occurrence");
            var fresh = FullBlockEntityTagInputs.observe(f.input(ChunkPos.ZERO), position,
                    f.world.level.registryAccess(), () -> { }, ignored -> true);
            assertNotNull(fresh);
            assertDoesNotThrow(() -> fresh.components("front_text.messages[]", ignored -> true, () -> { }));
        } finally {
            if (waiter != null) waiter.close();
            f.stop(); FullFinishingServices.stopped(f.world.level.getServer());
        }
        assertCleanup(f);
    }

    private static CompoundTag text(CompoundTag contents) {
        var tag = new CompoundTag(); tag.putInt("x", 8); tag.putInt("y", 8); tag.putInt("z", 0);
        tag.putString("id", "minecraft:text_display"); tag.put("text", contents); return tag;
    }
    private static CompoundTag blockText(BlockPos position, String path, boolean interpret) {
        var text = new CompoundTag(); text.putString("nbt", path);
        text.putString("block", position.getX() + " " + position.getY() + " " + position.getZ());
        text.putBoolean("interpret", interpret); return text;
    }
    private static CompoundTag storageText(Identifier id) {
        var text = new CompoundTag(); text.putString("nbt", "message");
        text.putString("storage", id.toString()); text.putBoolean("interpret", true); return text;
    }
    private static CompoundTag pendingBeacon(BlockPos position, net.minecraft.nbt.Tag customName) {
        var tag = new CompoundTag(); tag.putString("id", "minecraft:beacon");
        tag.putInt("x", position.getX()); tag.putInt("y", position.getY()); tag.putInt("z", position.getZ());
        tag.put("CustomName", customName); return tag;
    }
    private static Component component(FullRegistrationFixture fixture, net.minecraft.nbt.Tag encoded) {
        return ComponentSerialization.CODEC.parse(
                fixture.world.level.registryAccess().createSerializationContext(NbtOps.INSTANCE), encoded)
                .result().orElseThrow();
    }
}
