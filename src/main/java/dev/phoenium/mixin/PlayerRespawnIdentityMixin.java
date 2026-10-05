package dev.phoenium.mixin;

import dev.phoenium.chunk.PlayerRespawnHandoff;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(ServerPlayer.class)
abstract class PlayerRespawnIdentityMixin implements PlayerRespawnHandoff.Incarnation {
    @Unique private boolean phoenium$respawnRetired;
    @Unique private volatile boolean phoenium$deathRemovalCompleted;
    @Unique private boolean phoenium$respawnPrecheckLogged;
    @Unique private volatile boolean phoenium$creditsRemovalCompleted;
    @Override public boolean phoenium$creditsRemovalCompleted() { return phoenium$creditsRemovalCompleted; }
    @Override public void phoenium$creditsRemovalCompleted(boolean completed) { phoenium$creditsRemovalCompleted = completed; }
    /** Native has just removed the player with CHANGED_DIMENSION; the respawn that follows opens on that retirement. */
    @org.spongepowered.asm.mixin.injection.Inject(method = "showEndCredits()V", at = @org.spongepowered.asm.mixin.injection.At(
        value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;removePlayerImmediately(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/entity/Entity$RemovalReason;)V",
        shift = org.spongepowered.asm.mixin.injection.At.Shift.AFTER), require = 1, allow = 1)
    private void phoenium$creditsRemovalCompleted(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        phoenium$creditsRemovalCompleted = true;
    }
    @Unique private dev.phoenium.chunk.FullEntityFacts.PublishedPosition phoenium$deathPosition;
    @Override public dev.phoenium.chunk.FullEntityFacts.PublishedPosition phoenium$deathPosition() { return phoenium$deathPosition; }
    @Override public void phoenium$deathPosition(dev.phoenium.chunk.FullEntityFacts.PublishedPosition position) { phoenium$deathPosition=position; }
    @Override public boolean phoenium$respawnRetired() { return phoenium$respawnRetired; }
    @Override public void phoenium$respawnRetired(boolean retired) { phoenium$respawnRetired = retired; }
    @Override public boolean phoenium$deathRemovalCompleted() { return phoenium$deathRemovalCompleted; }
    @Override public void phoenium$deathRemovalCompleted(boolean completed) { phoenium$deathRemovalCompleted = completed; }
    @Override public boolean phoenium$respawnPrecheckLogged() { return phoenium$respawnPrecheckLogged; }
    @Override public void phoenium$respawnPrecheckLogged(boolean logged) { phoenium$respawnPrecheckLogged = logged; }
}
