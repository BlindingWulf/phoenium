package dev.phoenium.mixin;

import dev.phoenium.Phoenium;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ThrowableProjectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
abstract class ThrowableProjectilePortalEntityMixin {
    @Inject(method = "handlePortal()V", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/PortalProcessor;getPortalDestination(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/world/level/portal/TeleportTransition;"),
        cancellable = true, require = 1, expect = 1)
    private void phoenium$suspendThrowableProjectileDestination(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (self instanceof ThrowableProjectile projectile
                && Phoenium.liveRuntime().suspendThrowableProjectilePortalDestination(projectile, self.portalProcess)) {
            // handlePortal pushed its native "portal" profile section before this call.
            Profiler.get().pop();
            ci.cancel();
        }
    }

    @Inject(method = "baseTick()V", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/Entity;handlePortal()V", shift = At.Shift.AFTER),
        cancellable = true, require = 1, expect = 1)
    private void phoenium$returnSuspendedThrowableProjectileBaseTick(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (self instanceof ThrowableProjectile projectile
                && Phoenium.liveRuntime().throwableProjectilePortalSuspended(projectile)) {
            Profiler.get().pop();
            ci.cancel();
        }
    }
}
