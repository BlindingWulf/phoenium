package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.phoenium.Phoenium;
import net.minecraft.world.entity.projectile.ThrowableProjectile;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Retains the exact pinned 26.2 ThrowableProjectile prefix and its live HitResult. */
@Mixin(ThrowableProjectile.class)
abstract class ThrowableProjectilePortalTickMixin {
    @WrapMethod(method = "tick()V")
    private void phoenium$retainThrowableProjectileTick(Operation<Void> original) {
        ThrowableProjectile self = (ThrowableProjectile) (Object) this;
        if (Phoenium.liveRuntime().throwableProjectilePortalSuspended(self)) return;
        Phoenium.liveRuntime().beginThrowableProjectilePortalTick(self);
        try {
            original.call();
            Phoenium.liveRuntime().completeThrowableProjectilePortalTick(self);
        } finally {
            Phoenium.liveRuntime().finishThrowableProjectilePortalTick(self);
        }
    }

    @ModifyExpressionValue(method = "tick()V", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/projectile/ProjectileUtil;getHitResultOnMoveVector(Lnet/minecraft/world/entity/Entity;Ljava/util/function/Predicate;)Lnet/minecraft/world/phys/HitResult;"),
        require = 1, expect = 1)
    private HitResult phoenium$retainThrowableProjectileHit(HitResult result) {
        Phoenium.liveRuntime().retainThrowableProjectileHitResult((ThrowableProjectile) (Object) this, result);
        return result;
    }

    @Inject(method = "tick()V", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/projectile/Projectile;tick()V", shift = At.Shift.AFTER),
        cancellable = true, require = 1, expect = 1)
    private void phoenium$returnSuspendedThrowableProjectileTick(CallbackInfo ci) {
        if (Phoenium.liveRuntime().throwableProjectilePortalSuspended((ThrowableProjectile) (Object) this)) ci.cancel();
    }
}
