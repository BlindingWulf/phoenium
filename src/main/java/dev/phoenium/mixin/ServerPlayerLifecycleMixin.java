package dev.phoenium.mixin;

import dev.phoenium.Phoenium;
import dev.phoenium.live.PlayerDimensionTransitionNativeAccess;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

/** Closes the movement-admission window around a native ServerPlayer dimension transition. */
@Mixin(ServerPlayer.class)
abstract class ServerPlayerLifecycleMixin implements PlayerDimensionTransitionNativeAccess.PlayerSuffix {
    @Shadow @Final private MinecraftServer server;
    @Shadow private net.minecraft.world.phys.Vec3 enteredNetherPosition;
    @Shadow private int lastSentExp;
    @Shadow private float lastSentHealth;
    @Shadow private int lastSentFood;
    @Shadow private void triggerDimensionChangeTriggers(ServerLevel oldLevel) { throw new AssertionError(); }

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
            if (!Phoenium.liveRuntime().playerDimensionTransitionPending(player))
                Phoenium.liveRuntime().onPlayerLevelChanged(player);
        }
    }

    @WrapMethod(method = "teleport(Lnet/minecraft/world/level/portal/TeleportTransition;)Lnet/minecraft/server/level/ServerPlayer;")
    private ServerPlayer phoenium$dimensionChange(TeleportTransition transition, Operation<ServerPlayer> original) {
        ServerPlayer player = (ServerPlayer) (Object) this;
        return Phoenium.liveRuntime().withPlayerDimensionSourceOwner(player, transition, () -> {
            Phoenium.liveRuntime().onPlayerLevelChanging(player, transition.newLevel());
            boolean began = Phoenium.liveRuntime().beginPlayerDimensionTransition(player, transition);
            try {
                return original.call(transition);
            } finally {
                boolean retained = Phoenium.liveRuntime().finishPlayerDimensionTransitionCall(player, began);
                if (!retained) Phoenium.liveRuntime().onPlayerLevelChanged(player);
            }
        });
    }

    /**
     * Vanilla has completed oldLevel.removePlayerImmediately, including the complete source
     * tracking-end callback, and has not yet called unsetRemoved or installed the destination
     * level.  Returning here releases the source lease without replaying any prefix effect.
     */
    @Inject(method = "teleport(Lnet/minecraft/world/level/portal/TeleportTransition;)Lnet/minecraft/server/level/ServerPlayer;",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;removePlayerImmediately(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/entity/Entity$RemovalReason;)V",
            shift = At.Shift.AFTER), cancellable = true, require = 1, expect = 1)
    private void phoenium$suspendAfterSourceRetirement(TeleportTransition transition,
                                                        CallbackInfoReturnable<ServerPlayer> cir) {
        ServerPlayer player = (ServerPlayer) (Object) this;
        if (Phoenium.liveRuntime().suspendPlayerDimensionTransition(player)) cir.setReturnValue(player);
    }

    /** Exact pinned 26.2 suffix following oldLevel.removePlayerImmediately. */
    @Override public void phoenium$resumePlayerDimensionChange(TeleportTransition transition,
                                                               ServerLevel oldLevel) {
        ServerPlayer player = (ServerPlayer) (Object) this;
        ServerLevel newLevel = transition.newLevel();
        ((PlayerDimensionEntityAccess) (Object) player).phoenium$invokeUnsetRemoved();
        ProfilerFiller profiler = Profiler.get();
        profiler.push("moving");
        if (oldLevel.dimension() == Level.OVERWORLD && newLevel.dimension() == Level.NETHER)
            this.enteredNetherPosition = player.position();
        profiler.pop();
        profiler.push("placing");
        player.setServerLevel(newLevel);
        player.connection.teleport(net.minecraft.world.entity.PositionMoveRotation.of(transition), transition.relatives());
        player.connection.resetPosition();
        newLevel.addDuringTeleport(player);
        profiler.pop();
        this.triggerDimensionChangeTriggers(oldLevel);
        player.stopUsingItem();
        player.connection.send(new net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket(player.getAbilities()));
        PlayerList playerList = this.server.getPlayerList();
        playerList.sendLevelInfo(player, newLevel);
        playerList.sendAllPlayerInfo(player);
        playerList.sendActivePlayerEffects(player);
        transition.postTeleportTransition().onTransition(player);
        this.lastSentExp = -1;
        this.lastSentHealth = -1.0F;
        this.lastSentFood = -1;
        ((PlayerDimensionEntityAccess) (Object) player)
            .phoenium$invokeTeleportSpectators(transition, oldLevel);
    }
}
