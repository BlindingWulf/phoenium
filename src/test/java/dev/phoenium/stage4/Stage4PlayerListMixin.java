package dev.phoenium.stage4;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.phoenium.stage4probe.Stage4Trace;
import net.minecraft.network.protocol.Packet;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Captures only actual recipients of vanilla PlayerList.broadcast block-event packets. */
@Mixin(PlayerList.class)
abstract class Stage4PlayerListMixin {
    @WrapOperation(
        method = "broadcast(Lnet/minecraft/world/entity/player/Player;DDDDLnet/minecraft/resources/ResourceKey;Lnet/minecraft/network/protocol/Packet;)V",
        at = @At(value = "INVOKE", target =
            "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;send(Lnet/minecraft/network/protocol/Packet;)V"),
        require = 1, expect = 1)
    private void phoenium$actualRecipient(ServerGamePacketListenerImpl listener, Packet<?> packet,
                                          Operation<Void> original,
                                          @Local(argsOnly = true) Player source,
                                          @Local(argsOnly = true) double x,
                                          @Local(argsOnly = true) double y,
                                          @Local(argsOnly = true) double z,
                                          @Local(argsOnly = true) double radius,
                                          @Local(argsOnly = true) ResourceKey<Level> dimension) {
        ServerPlayer recipient = listener.getPlayer();
        Stage4Trace.blockEventRecipient(source, recipient, packet, x, y, z, radius, dimension);
        original.call(listener, packet);
    }
}
