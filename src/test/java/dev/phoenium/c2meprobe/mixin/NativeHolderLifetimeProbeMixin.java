package dev.phoenium.c2meprobe.mixin;

import dev.phoenium.c2meprobe.C2mePathProbe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Receipt only at the provider's existing release transition; supplies no lifecycle action. */
@Pseudo
@Mixin(targets = "com.ishland.flowsched.scheduler.ItemHolder", priority = 1)
abstract class NativeHolderLifetimeProbeMixin {
    @Inject(method = "release()V", at = @At("HEAD"), remap = false)
    private void phoenium$released(CallbackInfo callback) { C2mePathProbe.nativeHolderRelease(this); }
}
