package dev.phoenium.mixin;

import dev.phoenium.Phoenium;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.entity.vehicle.boat.Boat;
import net.minecraft.world.entity.vehicle.boat.ChestBoat;
import net.minecraft.world.entity.vehicle.boat.ChestRaft;
import net.minecraft.world.entity.vehicle.boat.Raft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Retain the inherited portal call before the destination lookup crosses owners. */
@Mixin(Entity.class)
abstract class BoatPortalEntityMixin {
    @Inject(method = "handlePortal()V", at = @At(value = "INVOKE", target =
        "Lnet/minecraft/world/entity/PortalProcessor;getPortalDestination(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/world/level/portal/TeleportTransition;"),
        cancellable = true, require = 1, expect = 1)
    private void phoenium$suspendBoatDestination(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if ((self.getClass() == Boat.class || self.getClass() == Raft.class
                || self.getClass() == ChestBoat.class || self.getClass() == ChestRaft.class)
                && Phoenium.liveRuntime().suspendBoatPortalDestination(
                    (AbstractBoat) self, self.portalProcess)) {
            Profiler.get().pop();
            ci.cancel();
        }
    }

    @Inject(method = "baseTick()V", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/Entity;handlePortal()V", shift = At.Shift.AFTER),
        cancellable = true, require = 1, expect = 1)
    private void phoenium$returnSuspendedBoatBaseTick(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (self instanceof AbstractBoat boat
                && Phoenium.liveRuntime().boatPortalSuspended(boat)) {
            Profiler.get().pop();
            ci.cancel();
        }
    }
}
