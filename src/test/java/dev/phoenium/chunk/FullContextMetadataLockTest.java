package dev.phoenium.chunk;

import static dev.phoenium.chunk.FullDecisionWitnessSupport.field;
import static dev.phoenium.chunk.FullDecisionWitnessSupport.set;
import static org.junit.jupiter.api.Assertions.*;

import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.CommandStorage;
import net.minecraft.world.level.storage.SavedDataStorage;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Behavioral proof for the small metadata monitors exempted from the broad gameplay-lock rule. */
final class FullContextMetadataLockTest {
    private static final BlockPos POSITION = new BlockPos(8, 8, 8);
    private static final net.minecraft.resources.Identifier STORAGE =
            net.minecraft.resources.Identifier.parse("phoenium:metadata-lock");

    @BeforeAll static void bootstrap() { FullTadpoleDecisionTest.bootstrap(); }

    @Test void pendingBlockStateAndSourceObserversCallBackAfterTheirMetadataMonitorsRelease() throws Exception {
        var fixture = new FullRegistrationFixture(true);
        AutoCloseable first = null, second = null;
        try {
            var initial = pendingBlock("before");
            fixture.input(ChunkPos.ZERO).setBlockEntityNbt(initial);
            var state = ((FullBlockEntityTagInputs.Access) fixture.input(ChunkPos.ZERO)).phoenium$blockEntityTags();
            var source = sourceFor(initial);
            var capture = FullBlockEntityTagInputs.observe(fixture.input(ChunkPos.ZERO), POSITION,
                    fixture.world.level.registryAccess(), () -> { }, ignored -> true);
            assertNotNull(capture);
            var firstFailure = new IllegalStateException("pending metadata callback failure");
            AtomicInteger survivorCalls = new AtomicInteger();
            first = capture.retain(() -> {
                assertFalse(Thread.holdsLock(state), "state metadata monitor must be released before callbacks");
                assertFalse(Thread.holdsLock(source), "source metadata monitor must be released before callbacks");
                throw firstFailure;
            });
            second = capture.retain(() -> {
                assertFalse(Thread.holdsLock(state), "state metadata monitor must be released before callbacks");
                assertFalse(Thread.holdsLock(source), "source metadata monitor must be released before callbacks");
                survivorCalls.incrementAndGet();
            });

            var replacement = pendingBlock("after");
            var thrown = assertThrows(IllegalStateException.class,
                    () -> fixture.input(ChunkPos.ZERO).setBlockEntityNbt(replacement));
            assertSame(firstFailure, thrown);
            assertEquals(1, survivorCalls.get(), "a later listener still runs after an earlier callback throws");
            assertFalse(capture.valid());
        } finally {
            if (first != null) first.close();
            if (second != null) second.close();
            fixture.stop(); FullFinishingServices.stopped(fixture.world.level.getServer());
        }
        FullInitializedInputBindingTest.assertCleanup(fixture);
    }

    @Test void commandStorageGetterSetterAndListenersRunOutsideRegistryMonitorAndRetainIndependentCleanup() throws Exception {
        var fixture = new FullTadpoleDecisionTest.Fixture(1);
        var data = new SavedDataStorage(Files.createTempDirectory("phoenium-metadata-lock"), null,
                fixture.level.registryAccess());
        var storage = new CommandStorage(data);
        set(fixture.level.getServer(), MinecraftServer.class, "commandStorage", storage);
        AutoCloseable first = null, second = null;
        try {
            var initial = new CompoundTag(); initial.putString("message", "before"); storage.set(STORAGE, initial);
            var capture = FullCommandStorageInputs.observe(fixture.level.getServer(), STORAGE);
            assertTrue(capture.valid());
            Object states = field(null, FullCommandStorageInputs.class, "STATES");
            var firstFailure = new IllegalArgumentException("storage metadata callback failure");
            AtomicInteger survivorCalls = new AtomicInteger();
            first = capture.retain(() -> {
                assertFalse(Thread.holdsLock(states), "storage registry monitor must be released before callbacks");
                throw firstFailure;
            });
            second = capture.retain(() -> {
                assertFalse(Thread.holdsLock(states), "storage registry monitor must be released before callbacks");
                survivorCalls.incrementAndGet();
            });
            try (var serial = FullFinishingServices.forServer(fixture.level.getServer())
                    .serial(FullFinishingServices.Phase.SERVER_TASK)) {
                var read = FullCommandStorageInputs.get(storage, STORAGE, () -> {
                    assertFalse(Thread.holdsLock(states), "original storage getter must be outside registry monitor");
                    return originalGet(storage, STORAGE);
                });
                assertEquals(initial, read);
                var replacement = new CompoundTag(); replacement.putString("message", "after");
                var thrown = assertThrows(IllegalArgumentException.class, () ->
                        FullCommandStorageInputs.set(storage, STORAGE, replacement, () -> {
                            assertFalse(Thread.holdsLock(states), "original storage setter must be outside registry monitor");
                            originalSet(storage, STORAGE, replacement);
                        }));
                assertSame(firstFailure, thrown);
            }
            assertEquals(1, survivorCalls.get(), "independent listener receives the same committed mutation");
            first.close(); first = null;
            var secondReplacement = new CompoundTag(); secondReplacement.putString("message", "final");
            try (var serial = FullFinishingServices.forServer(fixture.level.getServer())
                    .serial(FullFinishingServices.Phase.SERVER_TASK)) {
                FullCommandStorageInputs.set(storage, STORAGE, secondReplacement,
                        () -> originalSet(storage, STORAGE, secondReplacement));
            }
            assertEquals(2, survivorCalls.get(), "closing one subscription leaves the independent one active");
            second.close(); second = null;
            FullCommandStorageInputs.stopped(fixture.level.getServer());
            assertEquals(2, survivorCalls.get(), "closed listeners are not resurrected at shutdown");
        } finally {
            if (first != null) first.close(); if (second != null) second.close();
            FullFinishingServices.stopped(fixture.level.getServer()); data.close();
        }
    }

    private static CompoundTag pendingBlock(String message) {
        var tag = new CompoundTag(); tag.putString("id", "minecraft:beacon");
        tag.putInt("x", POSITION.getX()); tag.putInt("y", POSITION.getY()); tag.putInt("z", POSITION.getZ());
        tag.put("CustomName", StringTag.valueOf(message)); return tag;
    }

    private static Object sourceFor(CompoundTag tag) throws Exception {
        var custody = FullSavedCollections.source(tag);
        assertNotNull(custody);
        @SuppressWarnings("unchecked") var sources = (Map<Object, WeakReference<?>>)
                field(null, FullBlockEntityTagInputs.class, "SOURCES");
        var source = sources.get(custody) == null ? null : sources.get(custody).get();
        assertNotNull(source, "pending block tag must have one source metadata owner");
        return source;
    }

    private static CompoundTag originalGet(CommandStorage storage, net.minecraft.resources.Identifier id) {
        try {
            return (CompoundTag) CommandStorage.class.getMethod(
                    "phoenium$originalContext$get", net.minecraft.resources.Identifier.class).invoke(storage, id);
        } catch (java.lang.reflect.InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            if (failure.getCause() instanceof Error error) throw error;
            throw new AssertionError(failure.getCause());
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static void originalSet(CommandStorage storage, net.minecraft.resources.Identifier id, CompoundTag value) {
        try {
            CommandStorage.class.getMethod("phoenium$originalContext$set",
                    net.minecraft.resources.Identifier.class, CompoundTag.class).invoke(storage, id, value);
        } catch (java.lang.reflect.InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            if (failure.getCause() instanceof Error error) throw error;
            throw new AssertionError(failure.getCause());
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
}
