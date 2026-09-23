package dev.phoenium.mixin;

import dev.phoenium.Phoenium;
import dev.phoenium.live.PearlPortalNativeAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.PortalProcessor;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Exact cut and retained Entity.baseTick suffix for the pinned Minecraft 26.2 call shape. */
@Mixin(Entity.class)
abstract class PearlPortalEntityMixin implements PearlPortalNativeAccess.EntitySuffix {
    @Shadow public PortalProcessor portalProcess;
    @Shadow public boolean wasInPowderSnow;
    @Shadow public boolean isInPowderSnow;
    @Shadow protected boolean wasEyeInWater;
    @Shadow protected boolean firstTick;
    @Shadow private int remainingFireTicks;
    @Shadow protected abstract void spawnSprintParticle();
    @Shadow protected abstract boolean updateFluidInteraction();
    @Shadow protected abstract void removeAfterChangingDimensions();
    @Shadow protected abstract void teleportSpectators(TeleportTransition transition, ServerLevel oldLevel);

    @Inject(method = "handlePortal()V", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/PortalProcessor;getPortalDestination(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/world/level/portal/TeleportTransition;"),
        cancellable = true, require = 1, expect = 1)
    private void phoenium$suspendPearlPortalDestination(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (self instanceof ThrownEnderpearl pearl
                && Phoenium.liveRuntime().suspendPearlPortalDestination(pearl, portalProcess)) {
            // handlePortal pushed this native section immediately before this call site.
            Profiler.get().pop();
            ci.cancel();
        }
    }

    @Inject(method = "baseTick()V", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/Entity;handlePortal()V", shift = At.Shift.AFTER),
        cancellable = true, require = 1, expect = 1)
    private void phoenium$returnSuspendedPearlBaseTick(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (self instanceof ThrownEnderpearl pearl && Phoenium.liveRuntime().pearlPortalTickSuspended(pearl)
                || self instanceof ArmorStand stand && Phoenium.liveRuntime().armorStandPortalSuspended(stand)
                || self instanceof Mannequin mannequin && Phoenium.liveRuntime().armorStandPortalSuspended(mannequin)) {
            // baseTick's native entityBaseTick section is resumed by the typed suffix below.
            Profiler.get().pop();
            ci.cancel();
        }
    }

    @Override public void phoenium$resumePearlPortalBaseTickSuffix() {
        Entity self = (Entity) (Object) this;
        ProfilerFiller profiler = Profiler.get();
        profiler.push("entityBaseTick");
        try {
            if (self.canSpawnSprintParticle()) this.spawnSprintParticle();
            this.wasInPowderSnow = this.isInPowderSnow;
            this.isInPowderSnow = false;
            this.wasEyeInWater = self.isEyeInFluid(FluidTags.WATER);
            this.updateFluidInteraction();
            self.updateSwimming();
            if (self.level() instanceof ServerLevel serverLevel) {
                if (this.remainingFireTicks > 0) {
                    if (self.fireImmune()) self.clearFire();
                    else {
                        if (this.remainingFireTicks % 20 == 0 && !self.isInLava())
                            self.hurtServer(serverLevel, self.damageSources().onFire(), 1.0F);
                        self.setRemainingFireTicks(this.remainingFireTicks - 1);
                    }
                }
            } else self.clearFire();
            if (self.isInLava()) self.fallDistance *= 0.5;
            self.checkBelowWorld();
            if (!self.level().isClientSide()) self.setSharedFlagOnFire(this.remainingFireTicks > 0);
            this.firstTick = false;
            if (self.level() instanceof ServerLevel serverLevel && self instanceof Leashable leashable)
                Leashable.tickLeash(serverLevel, (Entity & Leashable) self);
        } finally {
            profiler.pop();
        }
    }

    @Override public void phoenium$removeAfterChangingDimensions() { this.removeAfterChangingDimensions(); }
    @Override public void phoenium$teleportSpectators(TeleportTransition transition, ServerLevel oldLevel) {
        this.teleportSpectators(transition, oldLevel);
    }
}
