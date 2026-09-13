package dev.phoenium.mixin;

import dev.phoenium.Phoenium;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Mixin;

/** Closes the movement-admission window around a native ServerPlayer dimension transition. */
@Mixin(ServerPlayer.class)
abstract class ServerPlayerLifecycleMixin {
    @WrapMethod(method = "teleport(Lnet/minecraft/world/level/portal/TeleportTransition;)Lnet/minecraft/server/level/ServerPlayer;")
    private ServerPlayer phoenium$dimensionChange(TeleportTransition transition, Operation<ServerPlayer> original) {
        ServerPlayer player = (ServerPlayer) (Object) this;
        synchronized (player) {
            Phoenium.liveRuntime().onPlayerLevelChanging(player, transition.newLevel());
            try {
                return original.call(transition);
            } finally {
                Phoenium.liveRuntime().onPlayerLevelChanged(player);
            }
        }
    }
}
