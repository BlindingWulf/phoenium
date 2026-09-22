package dev.phoenium.mixin;

import dev.phoenium.Phoenium;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A native lifecycle/replacement close wins before its first effect. It still runs in full. */
@Mixin(ServerPlayer.class)
abstract class ContainerCloseLifecycleMixin {
    @Inject(method = "doCloseContainer()V", at = @At("HEAD"), require = 1, expect = 1)
    private void phoenium$nativeCloseStarting(CallbackInfo ci) {
        Phoenium.liveRuntime().containerCloseStarting((ServerPlayer) (Object) this);
    }
}
