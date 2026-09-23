package dev.phoenium.mixin;

import dev.phoenium.Phoenium;
import net.minecraft.world.entity.projectile.hurtingprojectile.AbstractHurtingProjectile;
import net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.AbstractWindCharge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractWindCharge.class)
abstract class HurtingProjectilePortalWindBaseMixin {
    @Inject(method = "tick()V", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/projectile/hurtingprojectile/AbstractHurtingProjectile;tick()V",
        shift = At.Shift.AFTER), cancellable = true, require = 1, expect = 1)
    private void phoenium$returnSuspendedWindBaseTick(CallbackInfo ci) {
        if (Phoenium.liveRuntime().hurtingProjectilePortalSuspended((AbstractHurtingProjectile) (Object) this))
            ci.cancel();
    }
}
