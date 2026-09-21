package dev.phoenium.mixin;

import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
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
    @Invoker("handlePlayerAction")
    void phoenium$invokePlayerAction(ServerboundPlayerActionPacket packet);
    @Invoker("handleUseItemOn")
    void phoenium$invokeUseItemOn(ServerboundUseItemOnPacket packet);
    @Invoker("handleUseItem")
    void phoenium$invokeUseItem(ServerboundUseItemPacket packet);
}
