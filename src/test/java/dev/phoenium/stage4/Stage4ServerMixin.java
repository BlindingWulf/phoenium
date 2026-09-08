package dev.phoenium.stage4;

import dev.phoenium.stage4probe.Stage4Trace;

import net.minecraft.server.MinecraftServer;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
abstract class Stage4ServerMixin {
    @Inject(method = "tickServer", at = @At("RETURN"), require = 1)
    private void phoenium$teleportReadiness(CallbackInfo ci) {
        dev.phoenium.stage4probe.Stage4AcceptanceProbe.observeTeleportReadiness();
        dev.phoenium.stage4probe.Stage4AcceptanceProbe.observeFullReadiness();
    }

    @Inject(method = "saveAllChunks(ZZZ)Z", at = @At("HEAD"), require = 1)
    private void phoenium$serverSave(boolean silent, boolean flush, boolean force, CallbackInfoReturnable<Boolean> cir) { Stage4Trace.event("save.server.begin", "flush=" + flush + " force=" + force); }

    @Inject(method = "saveAllChunks(ZZZ)Z", at = @At("RETURN"), require = 1)
    private void phoenium$serverSaveReturn(boolean silent, boolean flush, boolean force, CallbackInfoReturnable<Boolean> cir) { Stage4Trace.event("save.server.end", "result=" + cir.getReturnValueZ()); }

    @WrapMethod(method = "stopServer()V")
    private void phoenium$stop(Operation<Void> original) {
        Stage4Trace.serverStopBeginning();
        boolean completed = false;
        try {
            original.call();
            completed = true;
        } finally {
            if (completed) Stage4Trace.serverStopCompleted();
        }
    }
}
