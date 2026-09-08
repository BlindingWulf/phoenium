package dev.phoenium.mixin;

import com.mojang.serialization.Codec;
import dev.phoenium.chunk.FullCachedComponentInputs;
import net.minecraft.util.EncoderCache;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(targets = "net.minecraft.util.EncoderCache$2")
public abstract class FullWrappedComponentCodecMixin implements FullCachedComponentInputs.WrappedCodecAccess {
    @Shadow @Final private EncoderCache this$0;
    @Shadow @Final private Codec<?> val$codec;
    @Override public EncoderCache phoenium$encoderCache() { return this$0; }
    @Override public Codec<?> phoenium$sourceCodec() { return val$codec; }

    @WrapMethod(method = "encode")
    private <T> DataResult<T> phoenium$encoding(Object input, DynamicOps<T> ops, T prefix,
                                                Operation<DataResult<T>> original) {
        var activity = FullCachedComponentInputs.encoding(this$0); Throwable primary = null;
        try { return original.call(input, ops, prefix); }
        catch (RuntimeException | Error failure) { primary = failure; throw failure; }
        finally {
            try { activity.close(); }
            catch (RuntimeException | Error cleanup) {
                if (primary != null) primary.addSuppressed(cleanup); else throw cleanup;
            }
        }
    }
}
