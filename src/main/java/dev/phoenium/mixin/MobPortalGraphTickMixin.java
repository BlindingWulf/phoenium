package dev.phoenium.mixin;

import dev.phoenium.Phoenium;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Pause the complete captured graph while its owner-free destination work is pending. */
@Mixin(ServerLevel.class)
abstract class MobPortalGraphTickMixin {
    @Inject(method = "tickNonPassenger", at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private void phoenium$pauseGraphRoot(Entity entity, CallbackInfo ci) {
        if (Phoenium.liveRuntime().mobPortalGraphTickSuspended(entity, false)) ci.cancel();
    }

    @Inject(method = "tickPassenger", at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private void phoenium$pauseGraphPassenger(Entity vehicle, Entity passenger, CallbackInfo ci) {
        if (Phoenium.liveRuntime().mobPortalGraphTickSuspended(passenger, true)) ci.cancel();
    }
}
