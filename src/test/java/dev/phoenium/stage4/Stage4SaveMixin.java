package dev.phoenium.stage4;

import dev.phoenium.stage4probe.Stage4Trace;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProgressListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerLevel.class)
abstract class Stage4SaveMixin {
    @Inject(method = "save(Lnet/minecraft/util/ProgressListener;ZZ)V", at = @At("HEAD"), require = 1)
    private void phoenium$saveEnter(ProgressListener listener, boolean flush, boolean skip, CallbackInfo ci) { Stage4Trace.event("save.enter", "flush=" + flush + " skip=" + skip); }

    @Inject(method = "save(Lnet/minecraft/util/ProgressListener;ZZ)V", at = @At("RETURN"), require = 1)
    private void phoenium$saveExit(ProgressListener listener, boolean flush, boolean skip, CallbackInfo ci) { Stage4Trace.event("save.exit"); }

}
