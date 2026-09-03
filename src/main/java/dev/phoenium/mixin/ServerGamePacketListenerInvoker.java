package dev.phoenium.mixin;

import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Narrow invokers replay only a previously admitted packet while Phoenium's execution marker is active. */
@Mixin(ServerGamePacketListenerImpl.class)
public interface ServerGamePacketListenerInvoker {
    @Invoker("handleMovePlayer")
    void phoenium$invokeMovePlayer(ServerboundMovePlayerPacket packet);
    @Invoker("handleInteract")
    void phoenium$invokeInteract(ServerboundInteractPacket packet);
}
