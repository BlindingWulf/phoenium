package dev.phoenium.mixin;

import dev.phoenium.live.PortalRiderRefusal;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Runs before every family's destination capture (low priority number): a vehicle carrying a player declines the
 * portal exactly as native does for a null destination. See {@link PortalRiderRefusal}.
 */
@Mixin(value = Entity.class, priority = 100)
abstract class RiderPortalRefusalMixin {
    @Inject(method = "handlePortal()V", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/PortalProcessor;getPortalDestination(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/world/level/portal/TeleportTransition;"),
        cancellable = true, require = 1, allow = 1)
    private void phoenium$declinePlayerRiderPortal(CallbackInfo ci) {
        if (PortalRiderRefusal.refuses((Entity) (Object) this)) {
            Profiler.get().pop();
            ci.cancel();
        }
    }
}
