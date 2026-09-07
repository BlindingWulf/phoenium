package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import dev.phoenium.chunk.*;
import net.minecraft.server.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.scores.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;

/** Original synchronous mutation/send/waypoint/dirty order, with explicit journal outcomes. */
@Mixin(ServerScoreboard.class)
abstract class FullFinishingScoreboardMixin implements FullScoreboardAccess {
    @Shadow @Final private MinecraftServer server;
    @Override public MinecraftServer phoenium$scoreboardServer() { return server; }
    @WrapMethod(method = "addPlayerToTeam(Ljava/lang/String;Lnet/minecraft/world/scores/PlayerTeam;)Z")
    private boolean phoenium$add(String member, PlayerTeam team, Operation<Boolean> original) {
        var service = FullFinishingServices.forServer(server);
        try (var guard = service.scoreboardAccess(); var entry = service.scoreboard.begin(member, team.getName())) {
            try { boolean result = original.call(member, team); entry.result(result); return result; }
            catch (Throwable failure) { entry.failed(failure); throw failure; }
        }
    }
    @WrapMethod(method = "removePlayerFromTeam(Ljava/lang/String;Lnet/minecraft/world/scores/PlayerTeam;)V")
    private void phoenium$remove(String member, PlayerTeam team, Operation<Void> original) {
        var service = FullFinishingServices.forServer(server);
        try (var guard = service.scoreboardAccess(); var entry = service.scoreboard.begin(member, team.getName())) {
            try { original.call(member, team); entry.result(true); }
            catch (Throwable failure) { entry.failed(failure); throw failure; }
        }
    }
    @WrapMethod(method = "storeToSaveDataIfDirty")
    private void phoenium$snapshot(ScoreboardSaveData data, Operation<Void> original) {
        try (var guard = FullFinishingServices.forServer(server).scoreboardAccess()) { original.call(data); }
    }
    @WrapMethod(method = "load")
    private void phoenium$load(ScoreboardSaveData.Packed data, Operation<Void> original) {
        try (var guard = FullFinishingServices.forServer(server).scoreboardAccess()) { original.call(data); }
    }
    @WrapMethod(method = "setDirty()V")
    private void phoenium$dirty(Operation<Void> original) {
        var service = FullFinishingServices.forServer(server);
        try (var guard = service.scoreboardAccess()) { original.call(); service.changed(FullFinishingServices.Kind.SCOREBOARD); }
    }
    @WrapOperation(method = "updatePlayerWaypoint", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/players/PlayerList;getPlayerByName(Ljava/lang/String;)Lnet/minecraft/server/level/ServerPlayer;"), require = 1)
    private ServerPlayer phoenium$waypointName(PlayerList list, String name, Operation<ServerPlayer> original) {
        if (!FullFinishingServices.finishing()) return original.call(list, name);
        return FullFinishingServices.forServer(server).scoreboard.lookupName(name);
    }
}
