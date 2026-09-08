package dev.phoenium.stage4;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
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
    @WrapMethod(method = "broadcast(Lnet/minecraft/world/entity/player/Player;DDDDLnet/minecraft/resources/ResourceKey;Lnet/minecraft/network/protocol/Packet;)V")
    private void phoenium$broadcast(Player source, double x, double y, double z, double radius,
                                     ResourceKey<Level> dimension, Packet<?> packet,
                                     Operation<Void> original) {
        Stage4Trace.beginBlockEventBroadcast(source, x, y, z, radius, dimension);
        try {
            original.call(source, x, y, z, radius, dimension, packet);
        } finally {
            Stage4Trace.endBlockEventBroadcast();
        }
    }

    @WrapOperation(
        method = "broadcast(Lnet/minecraft/world/entity/player/Player;DDDDLnet/minecraft/resources/ResourceKey;Lnet/minecraft/network/protocol/Packet;)V",
        at = @At(value = "INVOKE", target =
            "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;send(Lnet/minecraft/network/protocol/Packet;)V"),
        require = 1, expect = 1)
    private void phoenium$actualRecipient(ServerGamePacketListenerImpl listener, Packet<?> packet,
                                          Operation<Void> original) {
        ServerPlayer recipient = listener.getPlayer();
        Stage4Trace.blockEventRecipient(recipient, packet);
        original.call(listener, packet);
    }
}
