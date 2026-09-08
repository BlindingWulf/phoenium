package dev.phoenium.chunk;

import com.google.common.cache.PhoeniumCacheWitness;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.util.EncoderCache;
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

    @Test void anExactLoadingEntryIsObservedWithoutWaitingAndCompletionWakesOneRetainedRetry() throws Exception {
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        Codec<String> slow = new Codec<>() {
            @Override public <T> DataResult<Pair<String, T>> decode(DynamicOps<T> ops, T input) {
                return Codec.STRING.decode(ops, input);
            }
            @Override public <T> DataResult<T> encode(String input, DynamicOps<T> ops, T prefix) {
                started.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("slow cache fixture timed out");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); throw new AssertionError(interrupted);
                }
                return Codec.STRING.encode(input, ops, prefix);
            }
        };
        var cache = new EncoderCache(8); Codec<String> wrapped = cache.wrap(slow);
        var encoding = CompletableFuture.supplyAsync(() -> wrapped.encodeStart(NbtOps.INSTANCE, "pending").result().orElseThrow());
        assertTrue(started.await(10, TimeUnit.SECONDS));
        assertEquals(PhoeniumCacheWitness.State.LOADING, witness(wrapped, "pending", NbtOps.INSTANCE).state());
        var cancelledNotices = new AtomicInteger();
        AutoCloseable cancelled = FullCachedComponentInputs.whenIdle(cache, cancelledNotices::incrementAndGet);
        assertNotNull(cancelled); cancelled.close();
        var notices = new AtomicInteger();
        AutoCloseable waiter = FullCachedComponentInputs.whenIdle(cache, notices::incrementAndGet);
        assertNotNull(waiter); assertEquals(0, notices.get());
        release.countDown(); encoding.get(10, TimeUnit.SECONDS);
        assertEquals(1, notices.get()); assertEquals(0, cancelledNotices.get());
        assertEquals(PhoeniumCacheWitness.State.PRESENT, witness(wrapped, "pending", NbtOps.INSTANCE).state());
        waiter.close();
    }

    @Test void encodeCompletionAttemptsEveryWaiterAndPreservesTheFirstFailure() throws Exception {
        var cache = new EncoderCache(8); var activity = FullCachedComponentInputs.encoding(cache);
        var firstFailure = new IllegalStateException("first cache waiter failed");
        var secondFailure = new IllegalArgumentException("second cache waiter failed");
        var reached = new AtomicInteger();
        AutoCloseable first = FullCachedComponentInputs.whenIdle(cache, () -> { reached.incrementAndGet(); throw firstFailure; });
        AutoCloseable second = FullCachedComponentInputs.whenIdle(cache, () -> { reached.incrementAndGet(); throw secondFailure; });
        assertNotNull(first); assertNotNull(second);
        var thrown = assertThrows(IllegalStateException.class, activity::close);
        assertSame(firstFailure, thrown); assertArrayEquals(new Throwable[]{secondFailure}, thrown.getSuppressed());
        assertEquals(2, reached.get());
        first.close(); second.close();
    }

    private static PhoeniumCacheWitness.Result<DataResult<?>> witness(
            Codec<?> wrapped, Object value, DynamicOps<?> ops) {
        var access = assertInstanceOf(FullCachedComponentInputs.WrappedCodecAccess.class, wrapped);
        var cache = assertInstanceOf(FullCachedComponentInputs.CacheAccess.class, access.phoenium$encoderCache());
        return cache.phoenium$cached(access.phoenium$sourceCodec(), value, ops);
    }
}
