package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.phoenium.Phoenium;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.entity.vehicle.minecart.MinecartChest;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Stops AbstractMinecart.tick immediately after its pinned direct handlePortal call. */
@Mixin(AbstractMinecart.class)
abstract class AbstractMinecartPortalTickMixin {
    @WrapMethod(method = "tick()V")
    private void phoenium$retainChestMinecartTick(Operation<Void> original) {
        AbstractMinecart self = (AbstractMinecart) (Object) this;
        if (self.getClass() != MinecartChest.class) { original.call(); return; }
        if (Phoenium.liveRuntime().minecartPortalSuspended(self)) return;
        Phoenium.liveRuntime().beginMinecartPortalTick(self);
        try {
            original.call();
            Phoenium.liveRuntime().completeMinecartPortalTick(self);
        } finally {
            Phoenium.liveRuntime().finishMinecartPortalTick(self);
        }
    }

    @Inject(method = "tick()V", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/vehicle/minecart/AbstractMinecart;handlePortal()V",
        shift = At.Shift.AFTER), cancellable = true, require = 1, expect = 1)
    private void phoenium$returnSuspendedMinecartDirectTick(CallbackInfo ci) {
        AbstractMinecart self = (AbstractMinecart) (Object) this;
        if (Phoenium.liveRuntime().minecartPortalSuspended(self)) ci.cancel();
    }
}
