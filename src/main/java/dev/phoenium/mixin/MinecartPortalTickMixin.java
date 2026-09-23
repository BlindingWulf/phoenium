package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.phoenium.Phoenium;
import dev.phoenium.live.MinecartPortalNativeAccess;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.entity.vehicle.minecart.Minecart;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps the exact plain-Minecart outer tick tail after the retained native transfer. */
@Mixin(Minecart.class)
abstract class MinecartPortalTickMixin extends AbstractMinecart implements MinecartPortalNativeAccess {
    protected MinecartPortalTickMixin(EntityType<?> type, Level level) { super(type, level); }

    @Shadow private float rotationOffset;

    @WrapMethod(method = "tick()V")
    private void phoenium$retainMinecartTick(Operation<Void> original) {
        Minecart self = (Minecart) (Object) this;
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
        target = "Lnet/minecraft/world/entity/vehicle/minecart/AbstractMinecart;tick()V",
        shift = At.Shift.AFTER), cancellable = true, require = 1, expect = 1)
    private void phoenium$returnSuspendedMinecartTick(CallbackInfo ci) {
        if (Phoenium.liveRuntime().minecartPortalSuspended((Minecart) (Object) this)) ci.cancel();
    }

    @Override public void phoenium$resumeAbstractMinecartPortalSuffix() {
        this.getBehavior().tick();
        this.updateFluidInteraction();
        if (this.isInLava()) {
            this.lavaIgnite();
            this.lavaHurt();
            this.fallDistance *= 0.5;
        }
        this.firstTick = false;
    }

    @Override public void phoenium$resumeMinecartPortalSuffix(double lastKnownYRot, Vec3 lastKnownPos) {
        double tickDiff = (this.getYRot() - lastKnownYRot) % 360.0;
        if (this.level().isClientSide() && lastKnownPos.distanceTo(this.position()) > 0.01) {
            this.rotationOffset += (float) tickDiff;
            this.rotationOffset %= 360.0F;
        }
    }
}
