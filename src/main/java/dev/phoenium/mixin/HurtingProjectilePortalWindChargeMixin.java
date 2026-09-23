package dev.phoenium.mixin;

import dev.phoenium.Phoenium;
import dev.phoenium.live.HurtingProjectilePortalNativeAccess;
import net.minecraft.world.entity.projectile.hurtingprojectile.windcharge.WindCharge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WindCharge.class)
abstract class HurtingProjectilePortalWindChargeMixin implements HurtingProjectilePortalNativeAccess.WindChargeSuffix {
    @Shadow private int noDeflectTicks;

    @Inject(method = "tick()V", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/projectile/hurtingprojectile/windcharge/AbstractWindCharge;tick()V",
        shift = At.Shift.AFTER), cancellable = true, require = 1, expect = 1)
    private void phoenium$returnSuspendedWindChargeTick(CallbackInfo ci) {
        if (Phoenium.liveRuntime().hurtingProjectilePortalSuspended((WindCharge) (Object) this)) ci.cancel();
    }

    @Override public void phoenium$resumeWindChargeTickSuffix() {
        if (noDeflectTicks > 0) noDeflectTicks--;
    }
}
