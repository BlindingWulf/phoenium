package dev.phoenium.mixin;

import dev.phoenium.Phoenium;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Admission happens immediately after vanilla has moved a packet from Netty to the server thread,
 * but before either handler performs gameplay mutation. Replayed invocations require the narrow
 * marker installed by the region scheduler and therefore cannot recursively re-admit themselves.
 */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class ServerGamePacketListenerMixin {
    private static final String ENSURE_SERVER_THREAD = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/server/level/ServerLevel;)V";

    @Inject(method = "handleMovePlayer(Lnet/minecraft/network/protocol/game/ServerboundMovePlayerPacket;)V",
        at = @At(value = "INVOKE", target = ENSURE_SERVER_THREAD, shift = At.Shift.AFTER), cancellable = true)
    private void phoenium$routeMove(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        if (Phoenium.liveRuntime().packetExecutionMarked()) return;
        Phoenium.liveRuntime().admitMovePacket((ServerGamePacketListenerImpl) (Object) this, packet);
        ci.cancel();
    }

    @Inject(method = "handleInteract(Lnet/minecraft/network/protocol/game/ServerboundInteractPacket;)V",
        at = @At(value = "INVOKE", target = ENSURE_SERVER_THREAD, shift = At.Shift.AFTER), cancellable = true)
    private void phoenium$routeInteract(ServerboundInteractPacket packet, CallbackInfo ci) {
        if (Phoenium.liveRuntime().packetExecutionMarked()) return;
        Phoenium.liveRuntime().admitInteractPacket((ServerGamePacketListenerImpl) (Object) this, packet);
        ci.cancel();
    }
}
