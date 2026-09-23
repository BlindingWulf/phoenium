package dev.phoenium.stage7repair.mixin;

import dev.phoenium.live.PhoeniumLiveRuntime;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.PortalProcessor;
import net.minecraft.world.entity.projectile.hurtingprojectile.AbstractHurtingProjectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Isolated installed negative control. The switch is absent in all coverage runs;
 * an exact tagged P0 fireball can deliberately take the native unretained path.
 */
@Mixin(value = PhoeniumLiveRuntime.class, remap = false)
abstract class UnretainedFireballNegativeControl {
    @Inject(method = "suspendHurtingProjectilePortalDestination", at = @At("HEAD"),
        cancellable = true, require = 1)
    private void stage7$allowNativeFireballFallthrough(AbstractHurtingProjectile projectile,
            PortalProcessor processor, CallbackInfoReturnable<Boolean> cir) {
        if (Boolean.getBoolean("phoenium.stage7.negativeUnretainedFireball")
                && projectile.getType() == EntityTypes.FIREBALL
                && projectile.entityTags().contains("stage7_projectile_fireball_return"))
            cir.setReturnValue(false);
    }
}
