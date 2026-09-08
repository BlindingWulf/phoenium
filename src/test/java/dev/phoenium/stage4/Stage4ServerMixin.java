package dev.phoenium.stage4;

import dev.phoenium.stage4probe.Stage4Trace;

import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MinecraftServer.class)
abstract class Stage4ServerMixin {
    @Inject(method = "saveAllChunks(ZZZ)Z", at = @At("HEAD"), require = 1)
    private void phoenium$serverSave(boolean silent, boolean flush, boolean force, CallbackInfoReturnable<Boolean> cir) { Stage4Trace.event("save.server.begin", "flush=" + flush + " force=" + force); }

    @Inject(method = "saveAllChunks(ZZZ)Z", at = @At("RETURN"), require = 1)
    private void phoenium$serverSaveReturn(boolean silent, boolean flush, boolean force, CallbackInfoReturnable<Boolean> cir) { Stage4Trace.event("save.server.end", "result=" + cir.getReturnValueZ()); }

    @Inject(method = "stopServer()V", at = @At("HEAD"), require = 1)
    private void phoenium$stop(CallbackInfo ci) { Stage4Trace.serverStopped(); }
}
