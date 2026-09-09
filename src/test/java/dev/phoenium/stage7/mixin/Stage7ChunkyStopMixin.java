package dev.phoenium.stage7.mixin;

import dev.phoenium.stage7.Stage7ConcurrentProbe;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "org.popcraft.chunky.GenerationTask", remap = false)
abstract class Stage7ChunkyStopMixin {
    @Inject(method = "stop", at = @At("HEAD"))
    private void observe(boolean cancelled, CallbackInfo ci) {
        Stage7ConcurrentProbe.lifetime(cancelled ? "chunky-cancel" : "chunky-stop");
    }
}
