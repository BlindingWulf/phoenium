package dev.phoenium.stage4;

import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Server-side connection boundary evidence for a stalled account-free login. */
@Mixin(Connection.class)
abstract class Stage4ConnectionMixin {
    @org.spongepowered.asm.mixin.Unique private int phoenium$received;
    @org.spongepowered.asm.mixin.Unique private int phoenium$sent;
    @Inject(method = "channelActive", at = @At("RETURN"), require = 1)
    private void phoenium$active(io.netty.channel.ChannelHandlerContext context, CallbackInfo callback) {
        trace("active", null);
        if (!Boolean.getBoolean("phoenium.stage4.wireTrace")) return;
        try {
            context.pipeline().addFirst("phoenium_stage4_wire", new io.netty.channel.ChannelDuplexHandler() {
                @Override public void channelRead(io.netty.channel.ChannelHandlerContext ctx, Object message)
                        throws Exception {
                    String detail;
                    if (message instanceof io.netty.buffer.ByteBuf bytes) {
                        int length = bytes.readableBytes();
                        int sample = Math.min(length, 32);
                        detail = "bytes=" + length + " hex=" + io.netty.buffer.ByteBufUtil.hexDump(bytes, bytes.readerIndex(), sample);
                    } else detail = "message=" + message.getClass().getName();
                    System.out.println("PHOENIUM_STAGE4_WIRE event=read thread="
                        + Thread.currentThread().getName() + " address="
                        + String.valueOf(((Connection) (Object) Stage4ConnectionMixin.this).getRemoteAddress())
                        + " " + detail);
                    super.channelRead(ctx, message);
                }

                @Override public void write(io.netty.channel.ChannelHandlerContext ctx, Object message,
                                            io.netty.channel.ChannelPromise promise) throws Exception {
                    String detail;
                    if (message instanceof io.netty.buffer.ByteBuf bytes) {
                        int length = bytes.readableBytes();
                        int sample = Math.min(length, 32);
                        detail = "bytes=" + length + " hex=" + io.netty.buffer.ByteBufUtil.hexDump(bytes, bytes.readerIndex(), sample);
                    } else detail = "message=" + message.getClass().getName();
                    System.out.println("PHOENIUM_STAGE4_WIRE event=write thread="
                        + Thread.currentThread().getName() + " address="
                        + String.valueOf(((Connection) (Object) Stage4ConnectionMixin.this).getRemoteAddress())
                        + " " + detail);
                    super.write(ctx, message, promise);
                }

                @Override public void exceptionCaught(io.netty.channel.ChannelHandlerContext ctx, Throwable failure)
                        throws Exception {
                    System.out.println("PHOENIUM_STAGE4_WIRE event=exception thread="
                        + Thread.currentThread().getName() + " failure="
                        + failure.getClass().getName() + ":" + failure.getMessage());
                    super.exceptionCaught(ctx, failure);
                }
            });
        } catch (Throwable failure) {
            System.out.println("PHOENIUM_STAGE4_WIRE event=install-failure failure="
                + failure.getClass().getName() + ":" + failure.getMessage());
        }
    }

    @Inject(method = "channelInactive", at = @At("HEAD"), require = 1)
    private void phoenium$inactive(io.netty.channel.ChannelHandlerContext context, CallbackInfo callback) {
        trace("inactive", null);
    }

    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V",
            at = @At("HEAD"), require = 1)
    private void phoenium$receive(io.netty.channel.ChannelHandlerContext context, Packet<?> packet,
                                  CallbackInfo callback) {
        phoenium$received++;
        trace("receive", packet);
    }

    @Inject(method = "send", at = @At("HEAD"), require = 1)
    private void phoenium$send(Packet<?> packet, CallbackInfo callback) {
        phoenium$sent++;
        trace("send", packet);
    }

    @Inject(method = "exceptionCaught(Lio/netty/channel/ChannelHandlerContext;Ljava/lang/Throwable;)V",
            at = @At("HEAD"), require = 1)
    private void phoenium$exception(io.netty.channel.ChannelHandlerContext context, Throwable failure,
                                    CallbackInfo callback) {
        trace("exception", null, failure);
    }

    private void trace(String event, Packet<?> packet) {
        trace(event, packet, null);
    }

    private void trace(String event, Packet<?> packet, Throwable failure) {
        Connection connection = (Connection) (Object) this;
        String address;
        try { address = String.valueOf(connection.getRemoteAddress()); }
        catch (Throwable ignored) { address = "unknown"; }
        String packetName = packet == null ? "none" : packet.getClass().getName();
        String listener;
        try {
            net.minecraft.network.PacketListener value = connection.getPacketListener();
            listener = value == null ? "none" : value.getClass().getName();
        } catch (Throwable ignored) { listener = "unavailable"; }
        System.out.println("PHOENIUM_STAGE4_SERVER_TRACE event=" + event + " thread="
            + Thread.currentThread().getName() + " packet=" + packetName + " address=" + address
            + " listener=" + listener + " receiving=" + connection.getReceiving()
            + " received=" + phoenium$received + " sent=" + phoenium$sent
            + (failure == null ? "" : " failure=" + failure.getClass().getName() + ":" + failure.getMessage()));
    }
}
