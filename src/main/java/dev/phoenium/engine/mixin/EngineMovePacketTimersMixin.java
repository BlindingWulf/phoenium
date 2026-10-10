package dev.phoenium.engine.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.phoenium.engine.EngineMoveSplit;
import dev.phoenium.engine.EngineTimers;
import net.minecraft.network.protocol.game.ServerGamePacketListener;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * S9-E10 (engine mode only): the move packets' share of the queued packets for {@link EngineTimers}. The handler runs
 * once on the network thread (where it only schedules itself; that run is not counted) and once on the server thread.
 * S9-E13: on the server thread it also marks the move packet for {@link EngineMoveSplit}.
 */
@Mixin(ServerboundMovePlayerPacket.class)
abstract class EngineMovePacketTimersMixin {
    @WrapOperation(method = "handle(Lnet/minecraft/network/protocol/game/ServerGamePacketListener;)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/game/ServerGamePacketListener;handleMovePlayer(Lnet/minecraft/network/protocol/game/ServerboundMovePlayerPacket;)V"))
    private void phoenium$timeMovePacket(ServerGamePacketListener listener, ServerboundMovePlayerPacket packet, Operation<Void> original) {
        long start = EngineTimers.start();
        EngineMoveSplit.movePacketStart();
        try { original.call(listener, packet); } finally { EngineMoveSplit.movePacketEnd(); EngineTimers.add(EngineTimers.MOVE_PACKETS, start); }
    }
}
