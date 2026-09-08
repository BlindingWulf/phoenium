package dev.phoenium.adversestop.mixin;

import dev.phoenium.adversestop.AdverseStopProbe;
import dev.phoenium.live.PhoeniumLiveRuntime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = PhoeniumLiveRuntime.class, remap = false)
abstract class AdverseLiveRuntimeMixin {
    @Inject(method = "stop()V", at = @At("HEAD"), require = 1)
    private void phoenium$failedStopWaiting(CallbackInfo ci) {
        AdverseStopProbe.runtimeStopEntered((PhoeniumLiveRuntime) (Object) this);
    }
}
