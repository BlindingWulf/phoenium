package dev.phoenium.chunk;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.util.EncoderCache;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Exact dependency evidence for the cached DataComponents CUSTOM_NAME codec. A cache
 * lookup uses getIfPresent semantics only: discovery never invokes the wrapped encoder. */
public final class FullCachedComponentInputs {
    private static final Class<?> EMPTY_MAP = DataComponentMap.EMPTY.getClass();
    private static final Class<?> SIMPLE_MAP = DataComponentMap.builder()
            .set(DataComponents.CUSTOM_NAME, Component.empty()).build().getClass();
    public interface BlockAccess { DataComponentMap phoenium$rawComponents(); }
    public interface WrappedCodecAccess {
        EncoderCache phoenium$encoderCache();
        Codec<?> phoenium$sourceCodec();
    }
    public interface CacheAccess {
        DataResult<?> phoenium$cached(Codec<?> codec, Object value, DynamicOps<?> ops);
    }
    record Measure(long bytes, String unmet) { }
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
            DataResult<?> now = cached(value, registries);
            // Eviction/soft-value collection turns the original call into a miss, whose
            // current graph is also included in this receipt. A different live entry may
            // encode an ABA state, so it must trigger rediscovery.
            return now == null || now == cachedResult;
        }
    }
    private FullCachedComponentInputs() { }

    static boolean supported(BlockEntity block) {
        if (!(block instanceof BlockAccess access)) return false;
        DataComponentMap map = access.phoenium$rawComponents();
        return map.getClass() == SIMPLE_MAP || map.getClass() == EMPTY_MAP;
    }

    static Measure measure(BlockEntity block, int depth, HolderLookup.Provider registries) {
        if (block == null) return new Measure(32L, null);
        if (!supported(block)) return new Measure(32L,
                "cached block component map implementation is not audited: "
                        + ((BlockAccess) block).phoenium$rawComponents().getClass().getName());
        DataComponentMap map = ((BlockAccess) block).phoenium$rawComponents();
        Component value = rawCustomName(map);
        var current = FullLiveComponentInputs.measure(value, depth);
        if (current.unmet() != null) return new Measure(current.bytes(), current.unmet());
        DataResult<?> cached = cached(value, registries); Tag tag = successfulTag(cached);
        long cachedBytes = tag == null ? 32L : Math.max(32L, tag.sizeInBytes());
        return new Measure(Math.addExact(96L, Math.addExact(current.bytes(), cachedBytes)), null);
    }

    static Snapshot capture(FullSavedInput.EntityFact consumer, BlockEntity block, int depth,
                            long allowance, HolderLookup.Provider registries) {
        Measure measured = measure(block, depth, registries);
        if (measured.unmet() != null || measured.bytes() > allowance)
            return new Snapshot(block, null, null, null, null, null, measured.bytes(), List.of(),
                    measured.unmet() != null ? measured.unmet() : "live component grew after its admitted scalar measurement");
        if (block == null) {
            var empty = FullLiveComponentInputs.capture(consumer, null, depth, 32L);
            return new Snapshot(null, null, null, empty, null, null, measured.bytes(), List.of(), null);
        }
        DataComponentMap map = ((BlockAccess) block).phoenium$rawComponents(); Component value = rawCustomName(map);
        DataResult<?> cached = cached(value, registries); Tag tag = successfulTag(cached);
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
            throw new ChunkProtocolException("cached component evidence changed inside its exact regional dependency read");
        return result;
    }

    private static Component rawCustomName(DataComponentMap map) {
        return map.get(DataComponents.CUSTOM_NAME);
    }
    private static DataResult<?> cached(Component value, HolderLookup.Provider registries) {
        if (value == null) return null;
        Codec<Component> wrapped = DataComponents.CUSTOM_NAME.codecOrThrow();
        if (!(wrapped instanceof WrappedCodecAccess access))
            throw new ChunkProtocolException("cached custom-name codec lacks exact cache evidence access");
        DynamicOps<Tag> ops = registries.createSerializationContext(NbtOps.INSTANCE);
        return ((CacheAccess) access.phoenium$encoderCache()).phoenium$cached(access.phoenium$sourceCodec(), value, ops);
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
