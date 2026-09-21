package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.phoenium.chunk.FullFinishingServices;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Observe vanilla's assignment after respawn returns; never assign the listener early. */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class PlayerRespawnListenerMixin {
    @WrapOperation(method = "handleClientCommand", at = @At(value = "FIELD", opcode = Opcodes.PUTFIELD,
        target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;player:Lnet/minecraft/server/level/ServerPlayer;"),
        require = 2, allow = 2)
    private void phoenium$respawnConverged(ServerGamePacketListenerImpl listener, ServerPlayer player, Operation<Void> original) {
        original.call(listener, player);
        var service = FullFinishingServices.forServer(player.level().getServer());
        try (var turn = service.scoreboardAccess()) { service.scoreboard.respawnListenerConverged(player); }
    }
}
