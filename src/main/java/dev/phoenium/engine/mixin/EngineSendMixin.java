package dev.phoenium.engine.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.phoenium.engine.EngineConfig;
import dev.phoenium.engine.TileEngine;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * S9-E9 (engine mode only): a player connection's sends.
 * <ul>
 * <li>While its thread runs a capturing parallel-for (the tracker's sendChanges calls), a send is held in the thread's
 * outbox and delivered after the job ({@link dev.phoenium.engine.EngineTrackerSends}); a terminal packet is sent at once.
 * <li>{@link EngineConfig#SEND_FLUSH}: MinecraftServer.tickChildren suspends flushing for every player and flushes once
 * at its end, but only for the server thread's sends: vanilla flushes a send from any other thread at once (a socket
 * write per packet). A tile thread's send waits for the end-of-tick flush too.
 * </ul>
 */
@Mixin(ServerCommonPacketListenerImpl.class)
abstract class EngineSendMixin {
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V", at = @At("HEAD"), cancellable = true)
    private void phoenium$holdInOutbox(Packet<?> packet, ChannelFutureListener listener, CallbackInfo ci) {
        if (TileEngine.holdSend(this, packet, listener)) ci.cancel();
    }

    @WrapOperation(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;isSameThread()Z"))
    private boolean phoenium$tileThreadWaitsForFlush(MinecraftServer server, Operation<Boolean> original) {
        return original.call(server) || EngineConfig.SEND_FLUSH && TileEngine.onTileThread();
    }
}
