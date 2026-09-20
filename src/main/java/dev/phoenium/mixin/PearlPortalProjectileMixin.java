package dev.phoenium.mixin;

import dev.phoenium.Phoenium;
import dev.phoenium.live.PearlPortalNativeAccess;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Projectile.class)
abstract class PearlPortalProjectileMixin implements PearlPortalNativeAccess.ProjectileSuffix {
    @Shadow private boolean leftOwnerChecked;

    @Inject(method = "tick()V", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/Entity;tick()V", shift = At.Shift.AFTER),
        cancellable = true, require = 1, expect = 1)
    private void phoenium$returnSuspendedPearlProjectileTick(CallbackInfo ci) {
        Projectile self = (Projectile) (Object) this;
        if (self instanceof ThrownEnderpearl pearl && Phoenium.liveRuntime().pearlPortalTickSuspended(pearl)) ci.cancel();
    }

    @Override public void phoenium$resumePearlPortalProjectileTickSuffix() { this.leftOwnerChecked = false; }
}
