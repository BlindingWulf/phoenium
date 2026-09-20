package dev.phoenium.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.phoenium.Phoenium;
import dev.phoenium.live.PearlPortalNativeAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrowableItemProjectile;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ThrownEnderpearl.class)
abstract class PearlPortalThrownEnderpearlMixin implements PearlPortalNativeAccess.PearlSuffix {
    @Shadow private long ticketTimer;

    @Inject(method = "tick()V", at = @At("HEAD"), cancellable = true, require = 1, expect = 1)
    private void phoenium$skipPendingPearlTick(CallbackInfo ci) {
        if (Phoenium.liveRuntime().skipPendingPearlPortalTick((ThrownEnderpearl) (Object) this)) ci.cancel();
    }

    @Inject(method = "tick()V", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/projectile/throwableitemprojectile/ThrowableItemProjectile;tick()V", ordinal = 1),
        require = 1, expect = 1)
    private void phoenium$beginPearlPortalTick(CallbackInfo ci,
            @Local(ordinal = 0) int previousSectionX,
            @Local(ordinal = 1) int previousSectionZ,
            @Local(ordinal = 0) Entity retainedOwner) {
        Phoenium.liveRuntime().beginPearlPortalTick((ThrownEnderpearl) (Object) this,
            previousSectionX, previousSectionZ, retainedOwner);
    }

    @Inject(method = "tick()V", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/projectile/throwableitemprojectile/ThrowableItemProjectile;tick()V",
        ordinal = 1, shift = At.Shift.AFTER), cancellable = true, require = 1, expect = 1)
    private void phoenium$completeSuspendedPearlTick(CallbackInfo ci) {
        if (Phoenium.liveRuntime().completePearlPortalTick((ThrownEnderpearl) (Object) this)) ci.cancel();
    }

    @Inject(method = "tick()V", at = @At("RETURN"), require = 1)
    private void phoenium$finishPearlPortalTick(CallbackInfo ci) {
        Phoenium.liveRuntime().finishPearlPortalTick((ThrownEnderpearl) (Object) this);
    }

    @Override public void phoenium$resumePearlPortalPearlTickSuffix(
            int previousSectionX, int previousSectionZ, Entity retainedOwner) {
        ThrownEnderpearl self = (ThrownEnderpearl) (Object) this;
        if (!self.isAlive()) return;
        BlockPos currentPos = BlockPos.containing(self.position());
        if ((--this.ticketTimer <= 0L
                || previousSectionX != SectionPos.blockToSectionCoord(currentPos.getX())
                || previousSectionZ != SectionPos.blockToSectionCoord(currentPos.getZ()))
                && retainedOwner instanceof ServerPlayer player)
            this.ticketTimer = player.registerAndUpdateEnderPearlTicket(self);
    }
}
