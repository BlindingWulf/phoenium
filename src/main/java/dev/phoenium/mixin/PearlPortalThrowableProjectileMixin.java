package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.phoenium.Phoenium;
import dev.phoenium.live.PearlPortalNativeAccess;
import net.minecraft.world.entity.projectile.ThrowableProjectile;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ThrowableProjectile.class)
abstract class PearlPortalThrowableProjectileMixin implements PearlPortalNativeAccess.ThrowableSuffix {
    @ModifyExpressionValue(method = "tick()V", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/projectile/ProjectileUtil;getHitResultOnMoveVector(Lnet/minecraft/world/entity/Entity;Ljava/util/function/Predicate;)Lnet/minecraft/world/phys/HitResult;"),
        require = 1, expect = 1)
    private HitResult phoenium$retainPearlHitResult(HitResult result) {
        ThrowableProjectile self = (ThrowableProjectile) (Object) this;
        if (self instanceof ThrownEnderpearl pearl) Phoenium.liveRuntime().retainPearlPortalHitResult(pearl, result);
        return result;
    }

    @Inject(method = "tick()V", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/projectile/Projectile;tick()V", shift = At.Shift.AFTER),
        cancellable = true, require = 1, expect = 1)
    private void phoenium$returnSuspendedPearlThrowableTick(CallbackInfo ci) {
        ThrowableProjectile self = (ThrowableProjectile) (Object) this;
        if (self instanceof ThrownEnderpearl pearl && Phoenium.liveRuntime().pearlPortalTickSuspended(pearl)) ci.cancel();
    }

    @Override public void phoenium$resumePearlPortalThrowableTickSuffix(HitResult result) {
        ThrowableProjectile self = (ThrowableProjectile) (Object) this;
        if (result.getType() != HitResult.Type.MISS && self.isAlive())
            ((PearlPortalProjectileAccess) (Object) self).phoenium$invokeHitTargetOrDeflectSelf(result);
    }
}
