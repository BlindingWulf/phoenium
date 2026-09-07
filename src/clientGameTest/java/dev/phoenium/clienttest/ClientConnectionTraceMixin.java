package dev.phoenium.clienttrace;

import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Client-only packet boundary trace for the disposable account-free acceptance harness. */
@Mixin(Connection.class)
abstract class ClientConnectionTraceMixin {
    @Inject(method = "channelActive", at = @At("RETURN"))
    private void phoenium$channelActive(ChannelHandlerContext context, CallbackInfo callback) {
        trace("channel-active", null);
    }

    @Inject(method = "send", at = @At("HEAD"))
    private void phoenium$send(Packet<?> packet, CallbackInfo callback) {
        trace("send", packet);
    }

    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V",
            at = @At("HEAD"))
    private void phoenium$receive(ChannelHandlerContext context, Packet<?> packet, CallbackInfo callback) {
        trace("receive", packet);
    }

    private void trace(String event, Packet<?> packet) {
        Connection connection = (Connection) (Object) this;
        String address;
        try {
            address = String.valueOf(connection.getRemoteAddress());
        } catch (Throwable ignored) {
            address = "unknown";
        }
        if (!address.contains(":25565") && !address.contains("/25565")) return;
        PacketListener listener = connection.getPacketListener();
        System.out.println("PHOENIUM_FULL_CLIENT_TRACE event=" + event + " thread="
                + Thread.currentThread().getName() + " packet="
                + (packet == null ? "none" : packet.getClass().getName()) + " listener="
                + (listener == null ? "none" : listener.getClass().getName()) + " address=" + address);
    }
}
