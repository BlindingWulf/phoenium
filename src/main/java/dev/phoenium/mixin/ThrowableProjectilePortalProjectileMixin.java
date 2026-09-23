package dev.phoenium.mixin;

import dev.phoenium.Phoenium;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ThrowableProjectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Projectile.class)
abstract class ThrowableProjectilePortalProjectileMixin {
    @Inject(method = "tick()V", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/Entity;tick()V", shift = At.Shift.AFTER),
        cancellable = true, require = 1, expect = 1)
    private void phoenium$returnSuspendedThrowableProjectileTick(CallbackInfo ci) {
        Projectile self = (Projectile) (Object) this;
        if (self instanceof ThrowableProjectile projectile
                && Phoenium.liveRuntime().throwableProjectilePortalSuspended(projectile)) ci.cancel();
    }
}
