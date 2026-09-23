package dev.phoenium.mixin;

import dev.phoenium.Phoenium;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ShulkerBullet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cuts the inherited baseTick path before lookup; the direct caller uses the same lookup cut. */
@Mixin(Entity.class)
abstract class ShulkerBulletPortalEntityMixin {
    @Inject(method = "handlePortal()V", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/PortalProcessor;getPortalDestination(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/world/level/portal/TeleportTransition;"),
        cancellable = true, require = 1, expect = 1)
    private void phoenium$suspendShulkerBulletDestination(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (self instanceof ShulkerBullet bullet
                && Phoenium.liveRuntime().suspendShulkerBulletPortalDestination(bullet, self.portalProcess)) {
            Profiler.get().pop();
            ci.cancel();
        }
    }

    @Inject(method = "baseTick()V", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/Entity;handlePortal()V", shift = At.Shift.AFTER),
        cancellable = true, require = 1, expect = 1)
    private void phoenium$returnSuspendedShulkerBulletBaseTick(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (self instanceof ShulkerBullet bullet
                && Phoenium.liveRuntime().shulkerBulletPortalSuspended(bullet)) {
            Profiler.get().pop();
            ci.cancel();
        }
    }
}
