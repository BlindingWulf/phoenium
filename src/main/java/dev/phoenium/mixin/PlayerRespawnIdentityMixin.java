package dev.phoenium.mixin;

import dev.phoenium.chunk.PlayerRespawnHandoff;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(ServerPlayer.class)
abstract class PlayerRespawnIdentityMixin implements PlayerRespawnHandoff.Incarnation {
    @Unique private boolean phoenium$respawnRetired;
    @Override public boolean phoenium$respawnRetired() { return phoenium$respawnRetired; }
    @Override public void phoenium$respawnRetired(boolean retired) { phoenium$respawnRetired = retired; }
}
