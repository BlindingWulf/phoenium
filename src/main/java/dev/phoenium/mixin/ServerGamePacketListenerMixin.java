package dev.phoenium.mixin;

import dev.phoenium.Phoenium;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.phys.Vec3;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Movement admission is deliberately anchored at the first {@code tickCount} GETFIELD after the
 * handler's invalid-value rejection.  It is the documented 26.2 valid-input, pre-side-effect
 * point; the earlier after-ensureRunningOnSameThread hook is not retained as an authority path.
 */
@Mixin(ServerGamePacketListenerImpl.class)
abstract class ServerGamePacketListenerMixin {
    private static final String TICK_COUNT = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;tickCount:I";
    private static final String ENTITY_LOOKUP = "Lnet/minecraft/server/level/ServerLevel;getEntityOrPart(I)Lnet/minecraft/world/entity/Entity;";

    @Shadow private double lastGoodX;
    @Shadow private double lastGoodY;
    @Shadow private double lastGoodZ;
    @Shadow private Vec3 awaitingPositionFromClient;

    @Inject(method = "handleMovePlayer(Lnet/minecraft/network/protocol/game/ServerboundMovePlayerPacket;)V",
        at = @At(value = "FIELD", target = TICK_COUNT, opcode = Opcodes.GETFIELD, ordinal = 0),
        require = 1, expect = 1, cancellable = true)
    private void phoenium$routeMove(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        ServerGamePacketListenerImpl connection = (ServerGamePacketListenerImpl) (Object) this;
        if (Phoenium.liveRuntime().matchesMoveReplay(connection, packet)) return;
        Phoenium.liveRuntime().admitMovePacket(connection, packet, lastGoodX, lastGoodY, lastGoodZ,
            awaitingPositionFromClient != null);
        ci.cancel();
    }

    @Inject(method = "handleInteract(Lnet/minecraft/network/protocol/game/ServerboundInteractPacket;)V",
        at = @At(value = "INVOKE", target = ENTITY_LOOKUP), require = 1, expect = 1, cancellable = true)
    private void phoenium$routeInteract(ServerboundInteractPacket packet, CallbackInfo ci) {
        ServerGamePacketListenerImpl connection = (ServerGamePacketListenerImpl) (Object) this;
        if (Phoenium.liveRuntime().matchesInteractReplay(connection, packet)) return;
        Phoenium.liveRuntime().admitInteractPacket(connection, packet);
        ci.cancel();
    }
}
