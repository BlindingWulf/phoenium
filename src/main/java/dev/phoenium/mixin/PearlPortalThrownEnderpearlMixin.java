package dev.phoenium.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.phoenium.Phoenium;
import dev.phoenium.live.PearlPortalNativeAccess;
import dev.phoenium.live.PlayerDimensionTransitionNativeAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrowableItemProjectile;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ThrownEnderpearl.class)
abstract class PearlPortalThrownEnderpearlMixin implements PearlPortalNativeAccess.PearlSuffix,
        PlayerDimensionTransitionNativeAccess.PearlHitSuffix {
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

    /**
     * The player teleport returned only because its exact native destination suffix was retained.
     * Retain the rest of this already-entered hit callback at the invocation edge; collision,
     * particles, portal preparation, and the player teleport prefix are never replayed.
     */
    @Inject(method = "onHit(Lnet/minecraft/world/phys/HitResult;)V", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/level/ServerPlayer;teleport(Lnet/minecraft/world/level/portal/TeleportTransition;)Lnet/minecraft/server/level/ServerPlayer;",
        shift = At.Shift.AFTER), cancellable = true, require = 1, expect = 1)
    private void phoenium$retainPlayerDimensionHitSuffix(HitResult hitResult, CallbackInfo ci,
            @Local(ordinal = 0) ServerLevel destination,
            @Local(ordinal = 0) Vec3 teleportPosition,
            @Local(ordinal = 0) ServerPlayer player) {
        if (Phoenium.liveRuntime().retainPearlPlayerTeleportSuffix(
                (ThrownEnderpearl) (Object) this, destination, teleportPosition, player)) ci.cancel();
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

    /** Exact pinned 26.2 suffix after ServerPlayer.teleport returned a non-null player. */
    @Override public void phoenium$resumePearlPlayerHit(ServerPlayer player, ServerLevel destination,
                                                        Vec3 teleportPosition) {
        ThrownEnderpearl self = (ThrownEnderpearl) (Object) this;
        player.resetFallDistance();
        player.resetCurrentImpulseContext();
        player.hurtServer(player.level(), self.damageSources().enderPearl(), 5.0F);
        ((PearlPortalThrownEnderpearlAccess) (Object) self)
            .phoenium$invokePlaySound(destination, teleportPosition);
        self.discard();
    }
}
