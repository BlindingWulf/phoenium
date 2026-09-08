package dev.phoenium.mixin;

import com.mojang.serialization.Codec;
import dev.phoenium.chunk.FullCachedComponentInputs;
import net.minecraft.util.EncoderCache;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(targets = "net.minecraft.util.EncoderCache$2")
public abstract class FullWrappedComponentCodecMixin implements FullCachedComponentInputs.WrappedCodecAccess {
    @Shadow @Final private EncoderCache this$0;
    @Shadow @Final private Codec<?> val$codec;
    @Override public EncoderCache phoenium$encoderCache() { return this$0; }
    @Override public Codec<?> phoenium$sourceCodec() { return val$codec; }
}
