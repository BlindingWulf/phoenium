package dev.phoenium.mixin;

import dev.phoenium.Phoenium;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The two cuts of EntityPortalTransitionManager for entity classes without a dedicated manager. */
@Mixin(Entity.class)
abstract class EntityPortalEntityMixin {
    @Inject(method = "handlePortal()V", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/PortalProcessor;getPortalDestination(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/world/level/portal/TeleportTransition;"),
        cancellable = true, require = 1, allow = 1)
    private void phoenium$retainEntityDestination(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (Phoenium.liveRuntime().suspendEntityPortalDestination(self, self.portalProcess)) {
            Profiler.get().pop(); ci.cancel();
        }
    }

    @Inject(method = "baseTick()V", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;handlePortal()V",
        shift = At.Shift.AFTER), cancellable = true, require = 1, allow = 1)
    private void phoenium$returnEntityBaseTick(CallbackInfo ci) {
        if (Phoenium.liveRuntime().entityPortalSuspended((Entity) (Object) this)) {
            Profiler.get().pop(); ci.cancel();
        }
    }
}
