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
    @Override public boolean phoenium$respawnRetired() { return phoenium$respawnRetired; }
    @Override public void phoenium$respawnRetired(boolean retired) { phoenium$respawnRetired = retired; }
    @Override public boolean phoenium$deathRemovalCompleted() { return phoenium$deathRemovalCompleted; }
    @Override public void phoenium$deathRemovalCompleted(boolean completed) { phoenium$deathRemovalCompleted = completed; }
    @Override public boolean phoenium$respawnPrecheckLogged() { return phoenium$respawnPrecheckLogged; }
    @Override public void phoenium$respawnPrecheckLogged(boolean logged) { phoenium$respawnPrecheckLogged = logged; }
}
