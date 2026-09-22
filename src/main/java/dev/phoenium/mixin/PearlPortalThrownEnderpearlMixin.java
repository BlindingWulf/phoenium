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

    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method = "teleport", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/Entity;placePortalTicket(Lnet/minecraft/core/BlockPos;)V"), require = 1, allow = 1)
    private void phoenium$portalTicket(Entity entity, BlockPos position,
            com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original) {
        dev.phoenium.live.NativePortalTransfer.destinationEffect(entity, "PEARL_TELEPORT_SUFFIX", () -> original.call(entity, position));
    }

    @Inject(method = "tick()V", at = @At("HEAD"), cancellable = true, require = 1, expect = 1)
    private void phoenium$skipPendingPearlTick(CallbackInfo ci) {
        if (Phoenium.liveRuntime().skipPendingPearlPortalTick((ThrownEnderpearl) (Object) this)
                || Phoenium.liveRuntime().pearlPlayerHitPending((ThrownEnderpearl) (Object) this)) ci.cancel();
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
        if (Phoenium.liveRuntime().completePearlPortalTick((ThrownEnderpearl) (Object) this)
                || Phoenium.liveRuntime().pearlPlayerHitPending((ThrownEnderpearl) (Object) this)) ci.cancel();
    }

    @com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod(method = "tick()V")
    private void phoenium$finishPearlPortalTick(com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original) {
        try { original.call(); }
        finally { Phoenium.liveRuntime().finishPearlPortalTick((ThrownEnderpearl) (Object) this); }
    }
    @com.llamalad7.mixinextras.injector.ModifyExpressionValue(method = "onHit", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/projectile/throwableitemprojectile/ThrownEnderpearl;getOwner()Lnet/minecraft/world/entity/Entity;"), require = 1, allow = 1)
    private Entity phoenium$currentHitOwner(Entity original) {
        return Phoenium.liveRuntime().currentPearlHitOwner(original);
    }

    @Inject(method = "onHit(Lnet/minecraft/world/phys/HitResult;)V", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/projectile/throwableitemprojectile/ThrownEnderpearl;isOnPortalCooldown()Z"),
        cancellable = true, require = 1, allow = 1)
    private void phoenium$retainForeignPlayerHit(HitResult result, CallbackInfo ci,
            @Local(ordinal = 0) ServerLevel destination, @Local(ordinal = 0) Vec3 position,
            @Local(ordinal = 0) ServerPlayer player) {
        if (Phoenium.liveRuntime().capturePearlPlayerHit((ThrownEnderpearl)(Object)this,destination,position,player)) ci.cancel();
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
        if (!self.isAlive() || Phoenium.liveRuntime().pearlPlayerHitPending(self)) return;
        // A paused tick must not update tickets through a respawned/disconnected incarnation.
        if (Phoenium.liveRuntime().currentPearlHitOwner(retainedOwner) != retainedOwner) return;
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
        phoenium$resumePearlPlayerDamage(player);
        phoenium$resumePearlHitEffects(destination,teleportPosition);
    }
    @Override public void phoenium$resumePearlPlayerDamage(ServerPlayer player) {
        ThrownEnderpearl self = (ThrownEnderpearl) (Object) this;
        player.resetFallDistance();
        player.resetCurrentImpulseContext();
        player.hurtServer(player.level(), self.damageSources().enderPearl(), 5.0F);
    }
    @Override public void phoenium$resumePearlHitEffects(ServerLevel destination, Vec3 teleportPosition) {
        ThrownEnderpearl self = (ThrownEnderpearl) (Object) this;
        ((PearlPortalThrownEnderpearlAccess) (Object) self)
            .phoenium$invokePlaySound(destination, teleportPosition);
        self.discard();
    }
}
