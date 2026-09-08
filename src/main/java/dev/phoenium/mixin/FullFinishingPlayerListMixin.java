package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import dev.phoenium.chunk.*;
import dev.phoenium.service.RegionalBlockEventBroadcast;
import java.util.*;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.*;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(PlayerList.class)
abstract class FullFinishingPlayerListMixin {
    @Shadow public abstract net.minecraft.server.MinecraftServer getServer();
    @Shadow @Final private List<ServerPlayer> players;
    @Shadow @Final private Map<UUID, ServerPlayer> playersByUUID;
    @WrapMethod(method = {"getPlayerByName", "getPlayer(Ljava/lang/String;)Lnet/minecraft/server/level/ServerPlayer;"})
    private ServerPlayer phoenium$nameLookup(String name, Operation<ServerPlayer> original) {
        return FullFinishingServices.finishing() ? FullFinishingServices.forServer(getServer()).scoreboard.lookupName(name) : original.call(name);
    }
    @WrapMethod(method = "getPlayer(Ljava/util/UUID;)Lnet/minecraft/server/level/ServerPlayer;")
    private ServerPlayer phoenium$uuidLookup(UUID id, Operation<ServerPlayer> original) {
        return FullFinishingServices.finishing() ? FullFinishingServices.forServer(getServer()).scoreboard.lookupPlayer(id) : original.call(id);
    }
    @WrapMethod(method = "placeNewPlayer")
    private void phoenium$placement(Connection connection, ServerPlayer player, CommonListenerCookie cookie, Operation<Void> original) {
        var service = FullFinishingServices.forServer(getServer());
        try (var turn = service.serial(FullFinishingServices.Phase.PLAYER_LIFECYCLE)) {
            try { original.call(connection, player, cookie); }
            catch (Throwable failure) { service.scoreboard.failedPlacement(player); throw failure; }
        }
    }
    @WrapMethod(method = "updateEntireScoreboard")
    private void phoenium$initial(ServerScoreboard scoreboard, ServerPlayer player, Operation<Void> original) {
        var service = FullFinishingServices.forServer(getServer());
        try (var turn = service.serial(FullFinishingServices.Phase.SCOREBOARD_SYNC)) {
            service.scoreboard.initialSync(player); original.call(scoreboard, player);
        }
    }
    @WrapOperation(method = {"placeNewPlayer", "respawn"}, at = @At(value = "INVOKE", target = "Ljava/util/List;add(Ljava/lang/Object;)Z"), require = 2)
    private boolean phoenium$registered(List<Object> list, Object player, Operation<Boolean> original) {
        boolean added = original.call(list, player);
        if (list == (Object) players && added) FullFinishingServices.forServer(getServer()).scoreboard.registered((ServerPlayer) player);
        return added;
    }
    @WrapOperation(method = {"remove", "respawn"}, at = @At(value = "INVOKE", target = "Ljava/util/List;remove(Ljava/lang/Object;)Z"), require = 2)
    private boolean phoenium$removed(List<Object> list, Object player, Operation<Boolean> original) {
        boolean removed = original.call(list, player);
        if (list == (Object) players && removed) FullFinishingServices.forServer(getServer()).scoreboard.remove((ServerPlayer) player, false);
        return removed;
    }
    @WrapOperation(method = {"placeNewPlayer", "respawn"}, at = @At(value = "INVOKE", target = "Ljava/util/Map;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"), require = 2)
    private Object phoenium$uuidPublished(Map<Object, Object> map, Object key, Object value, Operation<Object> original) {
        Object previous = original.call(map, key, value);
        if (map == (Object) playersByUUID) FullFinishingServices.forServer(getServer()).scoreboard.playerUuidPublished((UUID) key, (ServerPlayer) value);
        return previous;
    }
    @WrapOperation(method = "remove(Lnet/minecraft/server/level/ServerPlayer;)V", at = @At(value = "INVOKE", target = "Ljava/util/Map;remove(Ljava/lang/Object;)Ljava/lang/Object;"), require = 3)
    private Object phoenium$uuidRemoved(Map<Object, Object> map, Object key, Operation<Object> original) {
        Object previous = original.call(map, key);
        if (map == (Object) playersByUUID) FullFinishingServices.forServer(getServer()).scoreboard.playerUuidRemoved((UUID) key);
        return previous;
    }
    @WrapMethod(method = "remove(Lnet/minecraft/server/level/ServerPlayer;)V")
    private void phoenium$disconnect(ServerPlayer player, Operation<Void> original) {
        var service = FullFinishingServices.forServer(getServer());
        try (var turn = service.serial(FullFinishingServices.Phase.PLAYER_LIFECYCLE)) {
            try { original.call(player); } finally { service.scoreboard.remove(player, true); }
        }
    }
    @WrapMethod(method = "broadcastAll(Lnet/minecraft/network/protocol/Packet;)V")
    private void phoenium$broadcast(Packet<?> packet, Operation<Void> original) {
        var service = FullFinishingServices.forServer(getServer());
        try (var turn = service.scoreboardAccess(); var record = service.scoreboard.beginBroadcast(packet, players)) { original.call(packet); }
    }
    @WrapOperation(method = "broadcastAll(Lnet/minecraft/network/protocol/Packet;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;send(Lnet/minecraft/network/protocol/Packet;)V"), require = 1)
    private void phoenium$send(ServerGamePacketListenerImpl listener, Packet<?> packet, Operation<Void> original) {
        FullFinishingServices.forServer(getServer()).scoreboard.send(listener, () -> original.call(listener, packet));
    }

    @WrapOperation(method = "broadcast", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;level()Lnet/minecraft/server/level/ServerLevel;"), require = 1, expect = 1)
    private ServerLevel phoenium$eventRecipientWorld(ServerPlayer player, Operation<ServerLevel> original) {
        var scope = RegionalBlockEventBroadcast.current();
        return scope == null ? original.call(player) : scope.level(player);
    }
    @WrapOperation(method = "broadcast", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;getX()D"), require = 1, expect = 1)
    private double phoenium$eventRecipientX(ServerPlayer player, Operation<Double> original) {
        var scope = RegionalBlockEventBroadcast.current();
        return scope == null ? original.call(player) : scope.x(player);
    }
    @WrapOperation(method = "broadcast", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;getY()D"), require = 1, expect = 1)
    private double phoenium$eventRecipientY(ServerPlayer player, Operation<Double> original) {
        var scope = RegionalBlockEventBroadcast.current();
        return scope == null ? original.call(player) : scope.y(player);
    }
    @WrapOperation(method = "broadcast", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerPlayer;getZ()D"), require = 1, expect = 1)
    private double phoenium$eventRecipientZ(ServerPlayer player, Operation<Double> original) {
        var scope = RegionalBlockEventBroadcast.current();
        return scope == null ? original.call(player) : scope.z(player);
    }
    @WrapOperation(method = "broadcast", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;send(Lnet/minecraft/network/protocol/Packet;)V"), require = 1, expect = 1)
    private void phoenium$eventRecipientSend(ServerGamePacketListenerImpl listener, Packet<?> packet, Operation<Void> original) {
        var scope = RegionalBlockEventBroadcast.current();
        if (scope == null) original.call(listener, packet);
        else scope.send(listener, () -> original.call(listener, packet));
    }
}
