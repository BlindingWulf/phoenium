package dev.phoenium.chunk;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.google.common.cache.PhoeniumCacheWitness;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.util.EncoderCache;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Exact audit evidence for the cached DataComponents CUSTOM_NAME codec. A witness
 * lookup never invokes the wrapped encoder or changes cache recency. The evidence is
 * intentionally not an admitted context receipt: the process-global cache has original
 * producers outside the regional FULL claim. */
public final class FullCachedComponentInputs {
    private static final Class<?> EMPTY_MAP = DataComponentMap.EMPTY.getClass();
    private static final Class<?> SIMPLE_MAP = DataComponentMap.builder()
            .set(DataComponents.CUSTOM_NAME, Component.empty()).build().getClass();
    private static final ReentrantLock ACTIVITY_GATE = new ReentrantLock();
    private static final IdentityHashMap<EncoderCache, Activity> ACTIVITIES = new IdentityHashMap<>();
    private static final class Activity {
        int active;
        final LinkedHashMap<Object, Waiter> waiters = new LinkedHashMap<>();
    }
    interface IdleWait extends AutoCloseable { boolean pending(); }
    private static final class Waiter implements Runnable, IdleWait {
        private final EncoderCache cache;
        private final Object token;
        private final AtomicReference<Runnable> changed;
        private Waiter(EncoderCache cache, Object token, Runnable changed) {
            this.cache = cache; this.token = token; this.changed = new AtomicReference<>(changed);
        }
        @Override public void run() {
            Runnable current = changed.getAndSet(null); if (current != null) current.run();
        }
        @Override public boolean pending() { return changed.get() != null; }
        @Override public void close() {
            changed.set(null);
            ACTIVITY_GATE.lock();
            try {
                Activity activity = ACTIVITIES.get(cache);
                if (activity != null) activity.waiters.remove(token);
            } finally { ACTIVITY_GATE.unlock(); }
        }
    }
    public static final class Encoding implements AutoCloseable {
        private EncoderCache cache;
        private Encoding(EncoderCache cache) { this.cache = cache; }
        @Override public void close() {
            EncoderCache current = cache; if (current == null) return; cache = null;
            List<Waiter> notices = List.of();
            ACTIVITY_GATE.lock();
            try {
                Activity activity = ACTIVITIES.get(current);
                if (activity == null || activity.active <= 0)
                    throw new ChunkProtocolException("cached component encode activity underflow");
                if (--activity.active == 0) {
                    notices = List.copyOf(activity.waiters.values()); activity.waiters.clear();
                    ACTIVITIES.remove(current);
                }
            } finally { ACTIVITY_GATE.unlock(); }
            Throwable failure = null;
            for (Waiter notice : notices) try { notice.run(); }
            catch (RuntimeException | Error next) {
                if (failure == null) failure = next;
                else if (failure != next) failure.addSuppressed(next);
            }
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure instanceof Error error) throw error;
        }
    }
    public interface BlockAccess { DataComponentMap phoenium$rawComponents(); }
    public interface WrappedCodecAccess {
        EncoderCache phoenium$encoderCache();
        Codec<?> phoenium$sourceCodec();
    }
    public interface CacheAccess {
        PhoeniumCacheWitness.Result<DataResult<?>> phoenium$cached(Codec<?> codec, Object value, DynamicOps<?> ops);
    }
    record Measure(long bytes, String unmet, EncoderCache loading) { }
    record Snapshot(BlockEntity block, DataComponentMap map, Component value,
                    FullLiveComponentInputs.Snapshot current,
                    DataResult<?> cachedResult, Tag cachedTag,
                    long bytes, List<FullSavedTextInputs.Requirements> requirements,
                    String unmet) {
        boolean valid(BlockEntity exact, HolderLookup.Provider registries) {
            if (exact == null) return block == null && map == null && value == null;
            if (exact != block || !(exact instanceof BlockAccess access)
                    || access.phoenium$rawComponents() != map || rawCustomName(map) != value
                    || !current.valid(value)) return false;
            var witness = cached(value, registries);
            if (witness.state() == PhoeniumCacheWitness.State.LOADING) return false;
            DataResult<?> now = witness.value();
            // Eviction/soft-value collection turns the original call into a miss, whose
            // current graph is also included in this receipt. A different live entry may
            // encode an ABA state, so it must trigger rediscovery. This is an instantaneous
            // validity test only; it cannot close the later load-before-original-call race.
            return now == null || now == cachedResult;
        }
    }
    private FullCachedComponentInputs() { }

    /** Marks an original wrapped encoder call without holding the metadata gate across it. */
    public static Encoding encoding(EncoderCache cache) {
        ACTIVITY_GATE.lock();
        try { ACTIVITIES.computeIfAbsent(cache, ignored -> new Activity()).active++; }
        finally { ACTIVITY_GATE.unlock(); }
        return new Encoding(cache);
    }

    /** Returns null if the encoder is already idle; otherwise registers one cancellable wake. */
    static IdleWait whenIdle(EncoderCache cache, Runnable changed) {
        Object token = new Object();
        Waiter waiter = new Waiter(cache, token, changed);
        ACTIVITY_GATE.lock();
        try {
            Activity activity = ACTIVITIES.get(cache);
            if (activity == null || activity.active == 0) return null;
            activity.waiters.put(token, waiter);
        } finally { ACTIVITY_GATE.unlock(); }
        return waiter;
    }

    /** Read-only terminal evidence for the static encoder metadata. */
    public record ActivityCounts(int caches, long encoders, int waiters) { }
    public static ActivityCounts activityCounts() {
        ACTIVITY_GATE.lock();
        try {
            long encoders = 0L; int waiters = 0;
            for (Activity activity : ACTIVITIES.values()) {
                encoders = Math.addExact(encoders, activity.active);
                waiters = Math.addExact(waiters, activity.waiters.size());
            }
            return new ActivityCounts(ACTIVITIES.size(), encoders, waiters);
        } finally { ACTIVITY_GATE.unlock(); }
    }

    static boolean supported(BlockEntity block) {
        if (!(block instanceof BlockAccess access)) return false;
        DataComponentMap map = access.phoenium$rawComponents();
        return map.getClass() == SIMPLE_MAP || map.getClass() == EMPTY_MAP;
    }

    static Measure measure(BlockEntity block, int depth, HolderLookup.Provider registries) {
        if (block == null) return new Measure(32L, null, null);
        if (!supported(block)) return new Measure(32L,
                "cached block component map implementation is not audited: "
                        + ((BlockAccess) block).phoenium$rawComponents().getClass().getName(), null);
        DataComponentMap map = ((BlockAccess) block).phoenium$rawComponents();
        Component value = rawCustomName(map);
        var current = FullLiveComponentInputs.measure(value, depth);
        if (current.unmet() != null) return new Measure(current.bytes(), current.unmet(), null);
        var witness = cached(value, registries);
        if (witness.state() == PhoeniumCacheWitness.State.LOADING)
            return new Measure(current.bytes(), null, cache(value));
        Tag tag = successfulTag(witness.value());
        long cachedBytes = tag == null ? 32L : Math.max(32L, tag.sizeInBytes());
        return new Measure(Math.addExact(96L, Math.addExact(current.bytes(), cachedBytes)), null, null);
    }

    static Snapshot capture(FullSavedInput.EntityFact consumer, BlockEntity block, int depth,
                            long allowance, HolderLookup.Provider registries) {
        Measure measured = measure(block, depth, registries);
        if (measured.loading() != null)
            return new Snapshot(block, null, null, null, null, null, measured.bytes(), List.of(),
                    "live component grew after its admitted scalar measurement (cached encode is in flight)");
        if (measured.unmet() != null || measured.bytes() > allowance)
            return new Snapshot(block, null, null, null, null, null, measured.bytes(), List.of(),
                    measured.unmet() != null ? measured.unmet() : "live component grew after its admitted scalar measurement");
        if (block == null) {
            var empty = FullLiveComponentInputs.capture(consumer, null, depth, 32L);
            return new Snapshot(null, null, null, empty, null, null, measured.bytes(), List.of(), null);
        }
        DataComponentMap map = ((BlockAccess) block).phoenium$rawComponents(); Component value = rawCustomName(map);
        var witness = cached(value, registries);
        if (witness.state() == PhoeniumCacheWitness.State.LOADING)
            return new Snapshot(block, map, value, null, null, null, measured.bytes(), List.of(),
                    "live component grew after its admitted scalar measurement (cached encode is in flight)");
        DataResult<?> cached = witness.value(); Tag tag = successfulTag(cached);
        long cachedBytes = tag == null ? 32L : Math.max(32L, tag.sizeInBytes());
        var current = FullLiveComponentInputs.capture(consumer, value, depth,
                Math.max(0L, Math.subtractExact(allowance, Math.addExact(96L, cachedBytes))));
        if (current.unmet() != null) return new Snapshot(block, map, value, current, cached, null,
                measured.bytes(), List.of(), current.unmet());
        Tag detached = tag == null ? null : tag.copy();
        var requirements = new ArrayList<FullSavedTextInputs.Requirements>(); requirements.add(current.requirements());
        if (detached != null) ComponentSerialization.CODEC.parse(registries.createSerializationContext(NbtOps.INSTANCE), detached)
                .result().ifPresent(component -> requirements.add(
                        FullSavedTextInputs.discoverInterpreted(consumer, component, depth)));
        var result = new Snapshot(block, map, value, current, cached, detached, measured.bytes(),
                List.copyOf(requirements), null);
        if (!result.valid(block, registries))
            return new Snapshot(block, map, value, null, cached, null, measured.bytes(), List.of(),
                    "live component grew after its admitted scalar measurement (cache evidence changed)");
        return result;
    }

    private static Component rawCustomName(DataComponentMap map) {
        return map.get(DataComponents.CUSTOM_NAME);
    }
    private static PhoeniumCacheWitness.Result<DataResult<?>> cached(Component value, HolderLookup.Provider registries) {
        if (value == null) return new PhoeniumCacheWitness.Result<>(PhoeniumCacheWitness.State.ABSENT, null);
        Codec<Component> wrapped = DataComponents.CUSTOM_NAME.codecOrThrow();
        if (!(wrapped instanceof WrappedCodecAccess access))
            throw new ChunkProtocolException("cached custom-name codec lacks exact cache evidence access");
        DynamicOps<Tag> ops = registries.createSerializationContext(NbtOps.INSTANCE);
        return ((CacheAccess) access.phoenium$encoderCache()).phoenium$cached(access.phoenium$sourceCodec(), value, ops);
    }
    private static EncoderCache cache(Component value) {
        if (value == null) return null;
        Codec<Component> wrapped = DataComponents.CUSTOM_NAME.codecOrThrow();
        return wrapped instanceof WrappedCodecAccess access ? access.phoenium$encoderCache() : null;
    }
    private static Tag successfulTag(DataResult<?> result) {
        if (result == null) return null;
        Object value = result.result().orElse(null);
        if (value == null) return null;
        if (!(value instanceof Tag tag)) throw new ChunkProtocolException("cached NBT component encoding returned a non-tag value");
        return tag;
    }

    static boolean selected(String path) {
        List<String> fields = FullNbtPathInputs.leadingFields(path, 2);
        return fields.size() >= 2 && fields.get(0).equals("components")
                && fields.get(1).equals("minecraft:custom_name");
    }
}
