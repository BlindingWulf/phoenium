package dev.phoenium.mixin;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Remapped constructor bridge for EncoderCache's private exact key type. */
@Mixin(targets = "net.minecraft.util.EncoderCache$Key")
public interface FullEncoderCacheKeyInvoker {
    @Invoker("<init>")
    static FullEncoderCacheKeyInvoker phoenium$create(Codec<?> codec, Object value, DynamicOps<?> ops) {
        throw new AssertionError("mixin constructor bridge was not applied");
    }
}
