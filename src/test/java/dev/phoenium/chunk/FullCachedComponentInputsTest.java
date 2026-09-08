package dev.phoenium.chunk;

import com.google.common.cache.PhoeniumCacheWitness;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.util.EncoderCache;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

final class FullCachedComponentInputsTest {
    @BeforeAll static void bootstrap() { FullSavedVillagerInputTest.bootstrap(); }

    @Test void exactWitnessDoesNotRefreshEvictionRecency() {
        var cache = new EncoderCache(2); Codec<String> wrapped = cache.wrap(Codec.STRING);
        wrapped.encodeStart(NbtOps.INSTANCE, "one").result().orElseThrow();
        wrapped.encodeStart(NbtOps.INSTANCE, "two").result().orElseThrow();
        assertEquals(PhoeniumCacheWitness.State.PRESENT, witness(wrapped, "one", NbtOps.INSTANCE).state());

        wrapped.encodeStart(NbtOps.INSTANCE, "three").result().orElseThrow();
        assertEquals(PhoeniumCacheWitness.State.ABSENT, witness(wrapped, "one", NbtOps.INSTANCE).state(),
                "the evidence read must not make the oldest entry recent");
        assertEquals(PhoeniumCacheWitness.State.PRESENT, witness(wrapped, "two", NbtOps.INSTANCE).state());
    }

    @Test void mutableKeyLookupUsesItsCurrentHashBucketInsteadOfScanningAnEqualStoredKey() {
        var cache = new EncoderCache(8); Codec<Component> wrapped = cache.wrap(ComponentSerialization.CODEC);
        var component = Component.translatable("%s", "before");
        wrapped.encodeStart(NbtOps.INSTANCE, component).result().orElseThrow();
        int insertionHash = component.hashCode();
        assertInstanceOf(TranslatableContents.class, component.getContents()).getArgs()[0] = "after";
        assertNotEquals(insertionHash, component.hashCode());

        assertEquals(PhoeniumCacheWitness.State.ABSENT, witness(wrapped, component, NbtOps.INSTANCE).state(),
                "LocalCache computes the current query hash and cannot reach the old insertion bucket");
        var current = wrapped.encodeStart(NbtOps.INSTANCE, component).result().orElseThrow();
        assertEquals("after", ComponentSerialization.CODEC.parse(NbtOps.INSTANCE, current)
                .result().orElseThrow().getString());
    }

    @Test void successiveLoadingEpisodesEachWakeAReplacementWaiterWithoutManualNotification() throws Exception {
        var started = new AtomicReference<CountDownLatch>(); var release = new AtomicReference<CountDownLatch>();
        Codec<String> slow = new Codec<>() {
            @Override public <T> DataResult<Pair<String, T>> decode(DynamicOps<T> ops, T input) {
                return Codec.STRING.decode(ops, input);
            }
            @Override public <T> DataResult<T> encode(String input, DynamicOps<T> ops, T prefix) {
                CountDownLatch began = started.get(); CountDownLatch finish = release.get(); began.countDown();
                try {
                    if (!finish.await(10, TimeUnit.SECONDS)) throw new AssertionError("slow cache fixture timed out");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); throw new AssertionError(interrupted);
                }
                return Codec.STRING.encode(input, ops, prefix);
            }
        };
        var cache = new EncoderCache(8); Codec<String> wrapped = cache.wrap(slow);
        var notices = new AtomicInteger();
        FullCachedComponentInputs.IdleWait prior = null;
        for (int cycle = 0; cycle < 2; cycle++) {
            var began = new CountDownLatch(1); var finish = new CountDownLatch(1);
            started.set(began); release.set(finish); String key = "pending-" + cycle;
            var encoding = CompletableFuture.supplyAsync(() -> wrapped.encodeStart(NbtOps.INSTANCE, key).result().orElseThrow());
            assertTrue(began.await(10, TimeUnit.SECONDS));
            assertEquals(PhoeniumCacheWitness.State.LOADING, witness(wrapped, key, NbtOps.INSTANCE).state());
            if (prior != null) { assertFalse(prior.pending(), "a fired one-shot is replaceable"); prior.close(); }
            prior = FullCachedComponentInputs.whenIdle(cache, notices::incrementAndGet);
            assertNotNull(prior); assertTrue(prior.pending()); assertEquals(cycle, notices.get());
            finish.countDown(); encoding.get(10, TimeUnit.SECONDS);
            assertEquals(cycle + 1, notices.get()); assertFalse(prior.pending());
            assertEquals(PhoeniumCacheWitness.State.PRESENT, witness(wrapped, key, NbtOps.INSTANCE).state());
        }
        prior.close();
        assertEquals(new FullCachedComponentInputs.ActivityCounts(0, 0, 0),
                FullCachedComponentInputs.activityCounts());
    }

    @Test void encodeCompletionAttemptsEveryWaiterAndPreservesTheFirstFailure() throws Exception {
        var cache = new EncoderCache(8); var activity = FullCachedComponentInputs.encoding(cache);
        var firstFailure = new IllegalStateException("first cache waiter failed");
        var laterFailure = new IllegalArgumentException("later cache waiter failed");
        var reached = new AtomicInteger();
        AutoCloseable first = FullCachedComponentInputs.whenIdle(cache, () -> { reached.incrementAndGet(); throw firstFailure; });
        AutoCloseable same = FullCachedComponentInputs.whenIdle(cache, () -> { reached.incrementAndGet(); throw firstFailure; });
        AutoCloseable later = FullCachedComponentInputs.whenIdle(cache, () -> { reached.incrementAndGet(); throw laterFailure; });
        assertNotNull(first); assertNotNull(same); assertNotNull(later);
        var thrown = assertThrows(IllegalStateException.class, activity::close);
        assertSame(firstFailure, thrown); assertArrayEquals(new Throwable[]{laterFailure}, thrown.getSuppressed());
        assertEquals(3, reached.get(), "self-suppression must not abort later notifications");
        first.close(); same.close(); later.close();
        assertEquals(new FullCachedComponentInputs.ActivityCounts(0, 0, 0),
                FullCachedComponentInputs.activityCounts());
    }

    @Test void originalAndWaiterSameFailureStillRunsEveryLaterNotification() throws Exception {
        var began = new CountDownLatch(1); var release = new CountDownLatch(1);
        var shared = new IllegalStateException("same original and waiter failure");
        Codec<String> failing = new Codec<>() {
            @Override public <T> DataResult<Pair<String, T>> decode(DynamicOps<T> ops, T input) {
                return Codec.STRING.decode(ops, input);
            }
            @Override public <T> DataResult<T> encode(String input, DynamicOps<T> ops, T prefix) {
                began.countDown();
                try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); throw new AssertionError(interrupted);
                }
                throw shared;
            }
        };
        var cache = new EncoderCache(8); var wrapped = cache.wrap(failing);
        var result = CompletableFuture.supplyAsync(() -> wrapped.encodeStart(NbtOps.INSTANCE, "failure"));
        assertTrue(began.await(10, TimeUnit.SECONDS));
        var reached = new AtomicInteger();
        var first = FullCachedComponentInputs.whenIdle(cache, () -> { reached.incrementAndGet(); throw shared; });
        var later = FullCachedComponentInputs.whenIdle(cache, reached::incrementAndGet);
        assertNotNull(first); assertNotNull(later); release.countDown();
        var thrown = assertThrows(ExecutionException.class, () -> result.get(10, TimeUnit.SECONDS));
        assertSame(shared, thrown.getCause().getCause());
        assertArrayEquals(new Throwable[]{shared}, thrown.getCause().getSuppressed());
        assertEquals(2, reached.get());
        first.close(); later.close();
        assertEquals(new FullCachedComponentInputs.ActivityCounts(0, 0, 0),
                FullCachedComponentInputs.activityCounts());
    }

    @Test void evidenceReplacementDuringCaptureReturnsRetryInsteadOfProtocolFailure() throws Exception {
        var f = new FullRegistrationFixture(true);
        var component = Component.literal("moving cache evidence");
        var first = DataComponentMap.builder().set(DataComponents.CUSTOM_NAME, component).build();
        var second = DataComponentMap.EMPTY;
        class MovingBeacon extends BeaconBlockEntity implements FullCachedComponentInputs.BlockAccess {
            private int reads;
            MovingBeacon() { super(BlockPos.ZERO, Blocks.BEACON.defaultBlockState()); }
            @Override public DataComponentMap phoenium$rawComponents() { return ++reads < 4 ? first : second; }
        }
        try {
            var fact = new FullSavedInput.EntityFact("moving", "minecraft:text_display", 0, 0, 0, 0, false);
            var result = assertDoesNotThrow(() -> FullCachedComponentInputs.capture(
                    fact, new MovingBeacon(), 0, Long.MAX_VALUE, f.world.level.registryAccess()));
            assertNotNull(result.unmet()); assertTrue(result.unmet().contains("cache evidence changed"));
            assertNull(result.current()); assertNull(result.cachedTag());
        } finally { f.stop(); FullFinishingServices.stopped(f.world.level.getServer()); }
        assertEquals(new FullCachedComponentInputs.ActivityCounts(0, 0, 0),
                FullCachedComponentInputs.activityCounts());
    }

    private static PhoeniumCacheWitness.Result<DataResult<?>> witness(
            Codec<?> wrapped, Object value, DynamicOps<?> ops) {
        var access = assertInstanceOf(FullCachedComponentInputs.WrappedCodecAccess.class, wrapped);
        var cache = assertInstanceOf(FullCachedComponentInputs.CacheAccess.class, access.phoenium$encoderCache());
        return cache.phoenium$cached(access.phoenium$sourceCodec(), value, ops);
    }
}
