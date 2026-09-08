package dev.phoenium.stage4;

import dev.phoenium.stage4probe.Stage4Trace;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.network.ServerPlayerConnection;

/** Test-only projection of packets that actually enter the tracking broadcaster. */
@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
abstract class Stage4TrackingPacketMixin {
    @Shadow @Final private Entity entity;

    @WrapOperation(method = {"sendToTrackingPlayers", "sendToTrackingPlayersFiltered"},
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/ServerPlayerConnection;send(Lnet/minecraft/network/protocol/Packet;)V"),
        require = 2, expect = 2)
    private void phoenium$actualRecipient(ServerPlayerConnection connection, Packet<?> packet, Operation<Void> original) {
        original.call(connection, packet);
        Stage4Trace.trackingDelivered(entity, connection.getPlayer(), packet);
    }

    @Inject(method = "sendToTrackingPlayers(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"), require = 1)
    private void phoenium$broadcast(Packet<?> packet, CallbackInfo ci) {
        Stage4Trace.trackingPacket("broadcast", entity, packet);
    }

    @Inject(method = "sendToTrackingPlayersAndSelf(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"), require = 1)
    private void phoenium$broadcastSelf(Packet<?> packet, CallbackInfo ci) {
        Stage4Trace.trackingPacket("broadcast-self", entity, packet);
    }

    @Inject(method = "sendToTrackingPlayersFiltered(Lnet/minecraft/network/protocol/Packet;Ljava/util/function/Predicate;)V", at = @At("HEAD"), require = 1)
    private void phoenium$broadcastFiltered(Packet<?> packet, java.util.function.Predicate<?> filter, CallbackInfo ci) {
        Stage4Trace.trackingPacket("broadcast-filtered", entity, packet);
    }
}
