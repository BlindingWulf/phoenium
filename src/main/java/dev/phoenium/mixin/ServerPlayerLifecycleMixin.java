package dev.phoenium.mixin;

import dev.phoenium.Phoenium;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Mixin;

import java.util.Set;

/** Closes the movement-admission window around a native ServerPlayer dimension transition. */
@Mixin(ServerPlayer.class)
abstract class ServerPlayerLifecycleMixin {
    /** RCON /tp reaches this overload directly instead of ServerPlayer.teleport(TeleportTransition). */
    @WrapMethod(method = "teleportTo(Lnet/minecraft/server/level/ServerLevel;DDDLjava/util/Set;FFZ)Z")
    private boolean phoenium$teleportToLevelChanging(ServerLevel destination, double x, double y, double z,
            Set<Relative> relatives, float yRot, float xRot, boolean dismountVehicle,
            Operation<Boolean> original) {
        ServerPlayer player = (ServerPlayer) (Object) this;
        if (destination != null && player.level() != destination)
            Phoenium.liveRuntime().onPlayerLevelChanging(player, destination);
        try {
            return original.call(destination, x, y, z, relatives, yRot, xRot, dismountVehicle);
        } finally {
            Phoenium.liveRuntime().onPlayerLevelChanged(player);
        }
    }

    @WrapMethod(method = "teleport(Lnet/minecraft/world/level/portal/TeleportTransition;)Lnet/minecraft/server/level/ServerPlayer;")
    private ServerPlayer phoenium$dimensionChange(TeleportTransition transition, Operation<ServerPlayer> original) {
        ServerPlayer player = (ServerPlayer) (Object) this;
        Phoenium.liveRuntime().onPlayerLevelChanging(player, transition.newLevel());
        try {
            return original.call(transition);
        } finally {
            Phoenium.liveRuntime().onPlayerLevelChanged(player);
        }
    }
}
