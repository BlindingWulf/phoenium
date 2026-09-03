package dev.phoenium.mixin;

import dev.phoenium.Phoenium;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Player placement/removal provides the initial object lifecycle before any movement packet admission. */
@Mixin(PlayerList.class)
abstract class PlayerListLifecycleMixin {
    @Inject(method = "placeNewPlayer(Lnet/minecraft/network/Connection;Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/server/network/CommonListenerCookie;)V",
        at = @At("TAIL"), require = 1)
    private void phoenium$playerPlaced(Connection connection, ServerPlayer player, CommonListenerCookie cookie, CallbackInfo ci) {
        Phoenium.liveRuntime().onPlayerPlaced(player);
    }

    @Inject(method = "remove(Lnet/minecraft/server/level/ServerPlayer;)V", at = @At("HEAD"), require = 1)
    private void phoenium$playerRemoved(ServerPlayer player, CallbackInfo ci) {
        Phoenium.liveRuntime().onEntityRemoved(player);
    }
}
