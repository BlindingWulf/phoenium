package dev.phoenium.mixin;

import com.google.common.cache.LoadingCache;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import dev.phoenium.chunk.FullCachedComponentInputs;
import net.minecraft.util.EncoderCache;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(EncoderCache.class)
public abstract class FullEncoderCacheAccessMixin implements FullCachedComponentInputs.CacheAccess {
    @Shadow @Final private LoadingCache<Object, DataResult<?>> cache;
    @Override public DataResult<?> phoenium$cached(Codec<?> codec, Object value, DynamicOps<?> ops) {
        return cache.getIfPresent(FullEncoderCacheKeyInvoker.phoenium$create(codec, value, ops));
    }
}
