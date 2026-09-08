package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.phoenium.chunk.FullFinishingScoreboard;
import dev.phoenium.chunk.FullFinishingServices;
import dev.phoenium.chunk.FullServerShutdown;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketProcessor;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.ServerConnectionListener;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

/**
 * The failed-stop scope has already terminally acknowledged every retained regional operation.
 * It closes native resources without entering vanilla's new save/unload mutations and without
 * letting one close failure suppress later independent closes. Healthy shutdown is unchanged.
 */
@Mixin(MinecraftServer.class)
abstract class FailedStopMinecraftServerMixin {
    private static final String STOP = "stopServer()V";

    @WrapOperation(method = STOP, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/PacketProcessor;close()V"), require = 1, expect = 1)
    private void phoenium$closePacketProcessor(PacketProcessor processor, Operation<Void> original) {
        FullServerShutdown.cleanup(() -> original.call(processor));
    }

    @WrapOperation(method = STOP, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/MinecraftServer;cancelRecordingMetrics()V"), require = 1, expect = 1)
    private void phoenium$cancelMetrics(MinecraftServer server, Operation<Void> original) {
        FullServerShutdown.cleanup(() -> original.call(server));
    }

    @WrapOperation(method = STOP, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/network/ServerConnectionListener;stop()V"), require = 1, expect = 1)
    private void phoenium$stopConnections(ServerConnectionListener listener, Operation<Void> original) {
        FullServerShutdown.cleanup(() -> original.call(listener));
    }

    @WrapOperation(method = STOP, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/players/PlayerList;saveAll()V"), require = 1, expect = 1)
    private void phoenium$savePlayers(PlayerList players, Operation<Void> original) {
        if (!FullServerShutdown.failedStop()) original.call(players);
    }

    @WrapOperation(method = STOP, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/players/PlayerList;removeAll()V"), require = 1, expect = 1)
    private void phoenium$removePlayers(PlayerList players, Operation<Void> original) {
        if (!FullServerShutdown.failedStop()) {
            original.call(players);
            return;
        }

        MinecraftServer server = (MinecraftServer) (Object) this;
        var scoreboard = FullFinishingServices.forServer(server).scoreboard;
        FullFinishingScoreboard.TrackingCut[] retained = {null};
        FullServerShutdown.cleanup(() -> retained[0] = scoreboard.trackingCut(List.copyOf(players.getPlayers())));
        if (retained[0] == null) return;
        FullFinishingScoreboard.TrackingCut cut = retained[0];
        Component reason = Component.translatable("multiplayer.disconnect.server_shutdown");
        DisconnectionDetails details = new DisconnectionDetails(reason);
        ClientboundDisconnectPacket packet = new ClientboundDisconnectPacket(reason);
        for (FullFinishingScoreboard.TrackingSession session : cut.sessions()) {
            boolean[] current = {false};
            FullServerShutdown.cleanup(() -> {
                scoreboard.requireTrackingSession(cut, session);
                current[0] = true;
            });
            if (!current[0]) continue;
            if (!(session.listener() instanceof FullFinishingScoreboard.ConnectionAccess access)) {
                FullServerShutdown.cleanup(() -> {
                    throw FullFinishingServices.failure(new dev.phoenium.chunk.ChunkProtocolException(
                            "shutdown connection identity producer is not installed"));
                });
                continue;
            }
            Connection connection = access.phoenium$serviceConnection();
            boolean[] submitted = {false};
            FullServerShutdown.cleanup(() -> {
                connection.send(packet, PacketSendListener.thenRun(() -> connection.disconnect(details)));
                submitted[0] = true;
            });
            if (!submitted[0]) FullServerShutdown.cleanup(() -> connection.disconnect(details));
            FullServerShutdown.cleanup(connection::setReadOnly);
        }
    }

    @WrapOperation(method = STOP, at = @At(value = "INVOKE",
            target = "Ljava/util/stream/Stream;anyMatch(Ljava/util/function/Predicate;)Z"), require = 1, expect = 1)
    private boolean phoenium$hasUnloadWork(Stream<?> levels, Predicate<Object> predicate,
                                            Operation<Boolean> original) {
        return FullServerShutdown.failedStop() ? false : original.call(levels, predicate);
    }

    @WrapOperation(method = STOP, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/MinecraftServer;saveAllChunks(ZZZ)Z"), require = 1, expect = 1)
    private boolean phoenium$saveWorlds(MinecraftServer server, boolean silent, boolean flush, boolean force,
                                         Operation<Boolean> original) {
        return FullServerShutdown.failedStop() ? false : original.call(server, silent, flush, force);
    }

    @WrapOperation(method = STOP, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;close()V"), require = 1, expect = 1)
    private void phoenium$closeLevel(ServerLevel level, Operation<Void> original) {
        FullServerShutdown.cleanup(() -> original.call(level));
    }

    @WrapOperation(method = STOP, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/storage/SavedDataStorage;close()V"), require = 1, expect = 1)
    private void phoenium$closeGlobalData(SavedDataStorage storage, Operation<Void> original) {
        FullServerShutdown.cleanup(() -> original.call(storage));
    }

    @WrapOperation(method = STOP, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/MinecraftServer$ReloadableResources;close()V"), require = 1, expect = 1)
    private void phoenium$closeResources(@Coerce Object resources, Operation<Void> original) {
        FullServerShutdown.cleanup(() -> original.call(resources));
    }

    @WrapOperation(method = STOP, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/storage/LevelStorageSource$LevelStorageAccess;close()V"), require = 1, expect = 1)
    private void phoenium$closeStorage(LevelStorageSource.LevelStorageAccess storage, Operation<Void> original) {
        FullServerShutdown.cleanup(() -> original.call(storage));
    }
}
