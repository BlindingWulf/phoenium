package dev.phoenium.engine.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.phoenium.engine.EngineMoveSplit;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundClientInformationPacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.game.ServerboundChatAckPacket;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.network.protocol.game.ServerboundChatSessionUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundChunkBatchReceivedPacket;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * S9-E15 F1 (engine mode only; acts only while {@link EngineMoveSplit} holds a deferred move): every queued packet passes
 * through PacketProcessor.ListenerAndPacket.handle on the server thread. Before a player's packet that may read or
 * broadcast through the tracking state, that player's deferred move runs (vanilla's whole move), so, for example, a swing
 * or an attack right after a move reaches the observers the move made. Move packets defer themselves; the packets below
 * touch neither the player's tracker nor any seenBy set and keep the move deferred: client tick end, keep-alive, pong,
 * chunk batch acknowledgement, chat (broadcast to every player), chat acknowledgement and chat session. Everything else,
 * including mod payloads and client information (view distance), runs the move first.
 */
@Mixin(targets = "net.minecraft.network.PacketProcessor$ListenerAndPacket")
abstract class EngineQueuedPacketMixin {
    @WrapOperation(method = "handle()V", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/Packet;handle(Lnet/minecraft/network/PacketListener;)V"))
    private void phoenium$moveBeforePacket(Packet<?> packet, PacketListener listener, Operation<Void> original) {
        if (EngineMoveSplit.anyPending() && listener instanceof ServerGamePacketListenerImpl game && phoenium$readsTracking(packet)) {
            EngineMoveSplit.beforePacket(game.player);
        }
        original.call(packet, listener);
    }

    @Unique
    private static boolean phoenium$readsTracking(Packet<?> packet) {
        return !(packet instanceof ServerboundMovePlayerPacket || packet instanceof ServerboundClientTickEndPacket
            || packet instanceof ServerboundKeepAlivePacket || packet instanceof ServerboundPongPacket
            || packet instanceof ServerboundChunkBatchReceivedPacket || packet instanceof ServerboundChatPacket
            || packet instanceof ServerboundChatAckPacket || packet instanceof ServerboundChatSessionUpdatePacket)
            || packet instanceof ServerboundClientInformationPacket;
    }
}
