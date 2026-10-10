package dev.phoenium.engine.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.phoenium.engine.EngineAutosave;
import dev.phoenium.engine.EngineMoveSplit;
import dev.phoenium.engine.EngineTimers;
import java.util.function.BooleanSupplier;
import net.minecraft.network.PacketProcessor;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.PlayerChunkSender;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** S9-E8 (engine mode only): the frame's server-thread sections for {@link EngineTimers} (packets, tick, children). */
@Mixin(MinecraftServer.class)
abstract class EngineServerTimersMixin {
    @WrapOperation(method = "processPacketsAndTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/PacketProcessor;processQueuedPackets()V"))
    private void phoenium$timePackets(PacketProcessor processor, Operation<Void> original) {
        long start = EngineTimers.start();
        try {
            EngineMoveSplit.beginPackets();   // S9-E13: the move packets' tracker updates run after the queued packets
            try { original.call(processor); } finally { EngineMoveSplit.endPackets(); }
            EngineMoveSplit.flush();
        } finally { EngineTimers.add(EngineTimers.PACKETS, start); }
    }

    @WrapOperation(method = "processPacketsAndTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;tickServer(Ljava/util/function/BooleanSupplier;)V"))
    private void phoenium$timeServerTick(MinecraftServer server, BooleanSupplier haveTime, Operation<Void> original) {
        long start = EngineTimers.start();
        EngineAutosave.tickStart();   // S9-E15: the spread autosave's per-tick budget and leading queue kind
        try { original.call(server, haveTime); } finally { EngineTimers.add(EngineTimers.SERVER_TICK, start); EngineTimers.frame(); }
    }

    @WrapOperation(method = "tickServer", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;autoSave()V"))
    private void phoenium$timeAutosave(MinecraftServer server, Operation<Void> original) {
        long start = EngineTimers.start();
        try { original.call(server); } finally { EngineTimers.add(EngineTimers.AUTOSAVE, start); }
    }

    @WrapOperation(method = "tickServer", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;tickChildren(Ljava/util/function/BooleanSupplier;)V"))
    private void phoenium$timeChildren(MinecraftServer server, BooleanSupplier haveTime, Operation<Void> original) {
        long start = EngineTimers.start();
        try { original.call(server, haveTime); } finally { EngineTimers.add(EngineTimers.CHILDREN, start); }
    }

    @WrapOperation(method = "tickChildren", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;tick(Ljava/util/function/BooleanSupplier;)V"))
    private void phoenium$timeLevel(ServerLevel level, BooleanSupplier haveTime, Operation<Void> original) {
        long start = EngineTimers.start();
        try { original.call(level, haveTime); } finally { EngineTimers.add(EngineTimers.LEVELS, start); }
    }

    @WrapOperation(method = "tickChildren", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;tickConnection()V"))
    private void phoenium$timeConnections(MinecraftServer server, Operation<Void> original) {
        long start = EngineTimers.start();
        try { original.call(server); } finally { EngineTimers.add(EngineTimers.CONNECTIONS, start); }
    }

    @WrapOperation(method = "tickChildren", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/players/PlayerList;tick()V"))
    private void phoenium$timePlayerList(PlayerList players, Operation<Void> original) {
        long start = EngineTimers.start();
        try { original.call(players); } finally { EngineTimers.add(EngineTimers.PLAYER_LIST, start); }
    }

    @WrapOperation(method = "tickChildren", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/PlayerChunkSender;sendNextChunks(Lnet/minecraft/server/level/ServerPlayer;)V"))
    private void phoenium$timeChunkSend(PlayerChunkSender sender, ServerPlayer player, Operation<Void> original) {
        long start = EngineTimers.start();
        try { original.call(sender, player); } finally { EngineTimers.add(EngineTimers.CHUNK_SEND, start); }
    }
}
