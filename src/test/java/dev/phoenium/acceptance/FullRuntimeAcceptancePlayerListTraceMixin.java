package dev.phoenium.acceptance;

import java.util.Collection;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.network.Connection;
import net.minecraft.server.network.CommonListenerCookie;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Narrow, server-side player-list trace used only by the disposable FULL acceptance harness.
 * The invocation probes bracket vanilla initial sync, list insertion, and UUID-map insertion.
 */
@Mixin(PlayerList.class)
abstract class FullRuntimeAcceptancePlayerListTraceMixin {
    @Inject(method = "placeNewPlayer", at = @At("HEAD"))
    private void phoenium$placeEntry(Connection connection, ServerPlayer player, CommonListenerCookie cookie,
                                     CallbackInfo callback) {
        trace("place-entry", player);
    }

    @Inject(method = "placeNewPlayer", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/network/protocol/game/ClientboundPlayerInfoUpdatePacket;createPlayerInitializing(Ljava/util/Collection;)Lnet/minecraft/network/protocol/game/ClientboundPlayerInfoUpdatePacket;", ordinal = 0))
    private void phoenium$initialSync(Connection connection, ServerPlayer player, CommonListenerCookie cookie,
                                       CallbackInfo callback) {
        trace("initial-sync-before", player);
    }

    @Inject(method = "placeNewPlayer", at = @At(value = "INVOKE", target =
            "Ljava/util/List;add(Ljava/lang/Object;)Z", ordinal = 0))
    private void phoenium$listAdd(Connection connection, ServerPlayer player, CommonListenerCookie cookie,
                                   CallbackInfo callback) {
        trace("list-add-before", player);
    }

    @Inject(method = "placeNewPlayer", at = @At(value = "INVOKE", target =
            "Ljava/util/Map;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", ordinal = 0))
    private void phoenium$uuidMapAdd(Connection connection, ServerPlayer player, CommonListenerCookie cookie,
                                      CallbackInfo callback) {
        trace("uuid-map-add-before", player);
    }

    @Inject(method = "placeNewPlayer", at = @At("RETURN"))
    private void phoenium$placeExit(Connection connection, ServerPlayer player, CommonListenerCookie cookie,
                                     CallbackInfo callback) {
        trace("place-exit", player);
    }

    private void trace(String event, ServerPlayer player) {
        PlayerList list = (PlayerList) (Object) this;
        String uuid = player == null ? "null" : String.valueOf(player.getUUID());
        int players = list.getPlayers().size();
        int byUuid = list.getPlayersByUUID().size();
        System.out.println("PHOENIUM_FULL_LOGIN_TRACE event=" + event + " thread="
                + Thread.currentThread().getName() + " uuid=" + uuid + " players=" + players
                + " uuidMap=" + byUuid);
    }
}
