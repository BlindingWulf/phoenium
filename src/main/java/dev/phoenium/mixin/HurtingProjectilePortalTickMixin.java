package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.phoenium.Phoenium;
import dev.phoenium.live.HurtingProjectilePortalNativeAccess;
import net.minecraft.world.entity.projectile.hurtingprojectile.AbstractHurtingProjectile;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Native prefix and the one live HitResult local are retained before the owner exits. */
@Mixin(AbstractHurtingProjectile.class)
abstract class HurtingProjectilePortalTickMixin implements HurtingProjectilePortalNativeAccess {
    @Shadow protected abstract boolean shouldBurn();

    @WrapMethod(method = "tick()V")
    private void phoenium$retainHurtingProjectileTick(Operation<Void> original) {
        AbstractHurtingProjectile self = (AbstractHurtingProjectile) (Object) this;
        if (Phoenium.liveRuntime().hurtingProjectilePortalSuspended(self)) return;
        Phoenium.liveRuntime().beginHurtingProjectilePortalTick(self);
        try {
            original.call();
            Phoenium.liveRuntime().completeHurtingProjectilePortalTick(self);
        } finally {
            Phoenium.liveRuntime().finishHurtingProjectilePortalTick(self);
        }
    }

    @ModifyExpressionValue(method = "tick()V", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/projectile/ProjectileUtil;getHitResultOnMoveVector(Lnet/minecraft/world/entity/Entity;Ljava/util/function/Predicate;Lnet/minecraft/world/level/ClipContext$Block;)Lnet/minecraft/world/phys/HitResult;"),
        require = 1, expect = 1)
    private HitResult phoenium$retainHurtingProjectileHit(HitResult result) {
        Phoenium.liveRuntime().retainHurtingProjectileHitResult((AbstractHurtingProjectile) (Object) this, result);
        return result;
    }

    @Inject(method = "tick()V", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/projectile/Projectile;tick()V", shift = At.Shift.AFTER),
        cancellable = true, require = 1, expect = 1)
    private void phoenium$returnSuspendedHurtingProjectileTick(CallbackInfo ci) {
        if (Phoenium.liveRuntime().hurtingProjectilePortalSuspended((AbstractHurtingProjectile) (Object) this))
            ci.cancel();
    }

    @Override public void phoenium$resumeHurtingProjectileTickSuffix(HitResult hitResult) {
        AbstractHurtingProjectile self = (AbstractHurtingProjectile) (Object) this;
        if (shouldBurn()) self.igniteForSeconds(1.0F);
        if (hitResult.getType() != HitResult.Type.MISS && self.isAlive())
            ((PearlPortalProjectileAccess) (Object) self).phoenium$invokeHitTargetOrDeflectSelf(hitResult);
        ((HurtingProjectilePortalAccess) (Object) self).phoenium$invokeCreateParticleTrail();
    }
}
