package dev.phoenium.mixin;

import net.minecraft.server.level.PlayerMap;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** S9-D16: counts PlayerMap's key-set changes. getAllPlayers is the players map's key set, whose members and
 * iteration order change only when a key is added or removed (addPlayer, removePlayer); ignore/unIgnore replace a
 * value. A retained capture of that set is still in order while the count is unchanged. */
@Mixin(PlayerMap.class)
abstract class PlayerMapRevisionMixin implements dev.phoenium.service.PlayerMapRevision {
    @Unique private int phoenium$revision;
    @Override public int phoenium$revision() { return phoenium$revision; }
    @Inject(method = "addPlayer", at = @At("HEAD"), require = 1)
    private void phoenium$added(ServerPlayer player, boolean ignored, CallbackInfo ci) { phoenium$revision++; }
    @Inject(method = "removePlayer", at = @At("HEAD"), require = 1)
    private void phoenium$removed(ServerPlayer player, CallbackInfo ci) { phoenium$revision++; }
}
