package dev.phoenium.clienttrace;

import dev.phoenium.clientobs.ClientTraceObservations;

import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.stream.Collectors;

/** Client-only packet boundary trace for the disposable account-free acceptance harness. */
@Mixin(Connection.class)
public abstract class ClientConnectionTraceMixin {
    @Inject(method = "channelActive", at = @At("RETURN"))
    private void phoenium$channelActive(ChannelHandlerContext context, CallbackInfo callback) {
        ClientTraceObservations.clear();
        trace("channel-active", null);
    }

    @Inject(method = "channelInactive", at = @At("HEAD"))
    private void phoenium$channelInactive(ChannelHandlerContext context, CallbackInfo callback) {
        boundary("channel-inactive", null);
    }

    @Inject(method = "exceptionCaught(Lio/netty/channel/ChannelHandlerContext;Ljava/lang/Throwable;)V",
            at = @At("HEAD"))
    private void phoenium$exception(ChannelHandlerContext context, Throwable failure, CallbackInfo callback) {
        boundary("exception", failure);
    }

    @Inject(method = "disconnect(Lnet/minecraft/network/chat/Component;)V", at = @At("HEAD"))
    private void phoenium$disconnect(net.minecraft.network.chat.Component reason, CallbackInfo callback) {
        boundary("disconnect-component", null);
    }

    @Inject(method = "disconnect(Lnet/minecraft/network/DisconnectionDetails;)V", at = @At("HEAD"))
    private void phoenium$disconnectDetails(net.minecraft.network.DisconnectionDetails reason, CallbackInfo callback) {
        boundary("disconnect-details", null);
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
        String packetName = packet == null ? "none" : packet.getClass().getName();
        String packetPayload = approved(packet) ? payload(packet) : "<omitted>";
        if ("receive".equals(event)) {
            ClientTraceObservations.record(packetName + " " + packetPayload);
            if (packet instanceof ClientboundLevelChunkWithLightPacket chunk)
                ClientTraceObservations.recordChunk(chunk.getX(), chunk.getZ());
            persistentTrace(event, packetName, packetPayload);
        }
        System.out.println("PHOENIUM_FULL_CLIENT_TRACE event=" + event + " packet="
                + packetName + " payload=" + packetPayload);
    }

    /** Optional on-disk packet evidence for bounded diagnosis; acceptance leaves it disabled. */
    private static void persistentTrace(String event, String packetName, String packetPayload) {
        String configured = System.getProperty("phoenium.stage4.packetTracePath", "");
        if (configured.isBlank()) return;
        try {
            Files.writeString(Path.of(configured), event + " packet=" + packetName
                    + " payload=" + packetPayload + System.lineSeparator(),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        } catch (Throwable ignored) {
            // Packet tracing is diagnostic only and must never affect the client protocol.
        }
    }

    private void boundary(String event, Throwable failure) {
        Connection connection = (Connection) (Object) this;
        String address;
        try { address = String.valueOf(connection.getRemoteAddress()); }
        catch (Throwable ignored) { address = "unknown"; }
        if (!address.contains(":25565") && !address.contains("/25565")) return;
        net.minecraft.network.PacketListener listener = connection.getPacketListener();
        System.out.println("PHOENIUM_FULL_CLIENT_BOUNDARY event=" + event
                + " thread=" + Thread.currentThread().getName()
                + " address=" + address
                + " listener=" + (listener == null ? "none" : listener.getClass().getName())
                + " receiving=" + connection.getReceiving()
                + (failure == null ? "" : " failure=" + failure.getClass().getName() + ":" + failure.getMessage()));
        if (event.startsWith("disconnect")) {
            StackTraceElement[] stack = Thread.currentThread().getStackTrace();
            for (int index = 2; index < Math.min(stack.length, 10); index++)
                System.out.println("PHOENIUM_FULL_CLIENT_BOUNDARY stack=" + stack[index]);
        }
    }

    private static boolean approved(Packet<?> packet) {
        if (packet == null) return false;
        String name = packet.getClass().getSimpleName();
        return name.equals("ClientboundBlockEventPacket")
            || name.equals("ClientboundLevelChunkWithLightPacket")
            || name.equals("ClientboundAddEntityPacket")
            || name.equals("ClientboundRemoveEntitiesPacket")
            || name.startsWith("ClientboundMoveEntityPacket")
            || name.equals("ClientboundRotateHeadPacket")
            || name.equals("ClientboundSetEntityDataPacket")
            || name.equals("ClientboundSetEquipmentPacket");
    }

    /**
     * Record payload-bearing packet values without depending on packet identity or hash codes.
     * Minecraft's packet records have deterministic toString implementations.  A small field
     * fallback keeps this useful for mapped packet classes that inherit Object.toString().
     */
    private static String payload(Packet<?> packet) {
        if (packet == null) return "none";
        if (packet instanceof ClientboundLevelChunkWithLightPacket chunk)
            return "chunk=" + chunk.getX() + "," + chunk.getZ();
        String value;
        try { value = String.valueOf(packet); }
        catch (Throwable failure) { value = "toString-error:" + failure.getClass().getSimpleName(); }
        String prefix = packet.getClass().getName() + "@";
        if (!value.startsWith(prefix)) return sanitize(value);
        try {
            return Arrays.stream(packet.getClass().getDeclaredFields())
                .filter(field -> !Modifier.isStatic(field.getModifiers()))
                .sorted(java.util.Comparator.comparing(Field::getName))
                .map(field -> fieldValue(field, packet))
                .collect(Collectors.joining(",", "fields{", "}"));
        } catch (Throwable failure) {
            return "fields-error:" + failure.getClass().getSimpleName();
        }
    }

    private static String fieldValue(Field field, Object target) {
        try {
            field.setAccessible(true);
            return field.getName() + "=" + sanitize(String.valueOf(field.get(target)));
        } catch (Throwable failure) {
            return field.getName() + "=<inaccessible>";
        }
    }

    private static String sanitize(String value) {
        String compact = value.replace('\n', ' ').replace('\r', ' ')
            .replaceAll("@[0-9a-fA-F]{4,}", "@<identity>");
        return compact.length() <= 768 ? compact : compact.substring(0, 768) + "…";
    }
}
