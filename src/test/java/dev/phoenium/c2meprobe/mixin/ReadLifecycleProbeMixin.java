package dev.phoenium.c2meprobe.mixin;

import dev.phoenium.c2meprobe.C2mePathProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Captures C2ME's real ReadFromDiskAsync action token for a disposable cancellation race. */
@Pseudo
@Mixin(targets = "com.ishland.c2me.rewrites.chunksystem.common.statuses.ReadFromDiskAsync", priority = 1)
abstract class ReadLifecycleProbeMixin {
    @Inject(method = "upgradeToThis(Lcom/ishland/c2me/rewrites/chunksystem/common/ChunkLoadingContext;Lcom/ishland/flowsched/scheduler/Cancellable;)Lio/reactivex/rxjava3/core/Completable;",
            at = @At("HEAD"), remap = false)
    private void phoenium$captureReadCancellable(@Coerce Object context, @Coerce Object cancellable,
                                                   CallbackInfoReturnable<Object> callback) {
        C2mePathProbe.captureReadCancellable(context, cancellable);
    }

    @Inject(method = "upgradeToThis(Lcom/ishland/c2me/rewrites/chunksystem/common/ChunkLoadingContext;Lcom/ishland/flowsched/scheduler/Cancellable;)Lio/reactivex/rxjava3/core/Completable;",
            at = @At("RETURN"), cancellable = true, remap = false)
    private void phoenium$observeTerminal(@Coerce Object context, @Coerce Object cancellable,
                                         CallbackInfoReturnable<Object> callback) {
        callback.setReturnValue(C2mePathProbe.observeNativeTerminal(callback.getReturnValue(), context, cancellable));
    }
}
