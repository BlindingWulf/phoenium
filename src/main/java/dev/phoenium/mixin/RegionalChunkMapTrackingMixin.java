package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.phoenium.Phoenium;
import dev.phoenium.chunk.FullFinishingServices;
import dev.phoenium.chunk.PlayerRespawnHandoff;
import java.util.List;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChunkMap.class)
abstract class RegionalChunkMapTrackingMixin {
    @WrapMethod(method = "addEntity")
    private void phoenium$regionalTrackingAdd(Entity entity, Operation<Void> original) {
        ChunkMap map = (ChunkMap) (Object) this;
        var handoff = entity instanceof ServerPlayer player
            ? FullFinishingServices.forServer(player.level().getServer()).scoreboard.respawn(player) : null;
        try (var tracking = handoff == null ? null : handoff.beginTracking((ServerPlayer) entity, PlayerRespawnHandoff.Action.ADD)) {
            if (!Phoenium.liveRuntime().regionalTrackingAdd(map, entity, () -> original.call(entity))) original.call(entity);
            if (tracking != null) tracking.succeeded();
        }
    }

    @WrapOperation(method = "addEntity", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/level/ChunkMap$TrackedEntity;updatePlayers(Ljava/util/List;)V"), require = 1)
    private void phoenium$regionalTrackingInitialPlayers(@Coerce Object tracker, List<ServerPlayer> players,
                                                         Operation<Void> original) {
        if (!Phoenium.liveRuntime().regionalTrackingAddPlayers((ChunkMap) (Object) this, tracker, players))
            original.call(tracker, players);
    }

    @WrapOperation(method = "addEntity", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/level/ChunkMap$TrackedEntity;updatePlayer(Lnet/minecraft/server/level/ServerPlayer;)V"), require = 1)
    private void phoenium$regionalTrackingReversePlayer(@Coerce Object tracker, ServerPlayer player,
                                                        Operation<Void> original) {
        if (!Phoenium.liveRuntime().regionalTrackingAddPlayer((ChunkMap) (Object) this, tracker, player,
                () -> original.call(tracker, player))) original.call(tracker, player);
    }

    @WrapMethod(method = "removeEntity")
    private void phoenium$regionalTrackingRemove(Entity entity, Operation<Void> original) {
        ChunkMap map = (ChunkMap) (Object) this;
        var handoff = entity instanceof ServerPlayer player
            ? FullFinishingServices.forServer(player.level().getServer()).scoreboard.respawn(player) : null;
        try (var tracking = handoff == null ? null : handoff.beginTracking((ServerPlayer) entity, PlayerRespawnHandoff.Action.REMOVE)) {
            if (tracking != null && tracking.alreadyRemoved) return;
            if (!Phoenium.liveRuntime().regionalTrackingRemove(map, entity, () -> original.call(entity))) original.call(entity);
            if (tracking != null) tracking.succeeded();
        }
    }

    @WrapOperation(method = "removeEntity", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/level/ChunkMap$TrackedEntity;removePlayer(Lnet/minecraft/server/level/ServerPlayer;)V"), require = 1)
    private void phoenium$regionalTrackingRemovePlayer(@Coerce Object tracker, ServerPlayer player,
                                                       Operation<Void> original) {
        if (!Phoenium.liveRuntime().regionalTrackingRemovePlayer((ChunkMap) (Object) this, tracker, player,
                () -> original.call(tracker, player))) original.call(tracker, player);
    }

    @WrapMethod(method = "move")
    private void phoenium$regionalTrackingMove(ServerPlayer player, Operation<Void> original) {
        ChunkMap map = (ChunkMap) (Object) this;
        if (!Phoenium.liveRuntime().regionalTrackingMove(map, player, () -> original.call(player)))
            original.call(player);
    }

    @WrapOperation(method = "move", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/level/ChunkMap$TrackedEntity;updatePlayers(Ljava/util/List;)V"), require = 1)
    private void phoenium$regionalTrackingMovedPlayers(@Coerce Object tracker, List<ServerPlayer> players,
                                                       Operation<Void> original) {
        if (!Phoenium.liveRuntime().regionalTrackingMovedPlayers((ChunkMap) (Object) this, tracker, players))
            original.call(tracker, players);
    }

    @WrapOperation(method = "move", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/level/ChunkMap$TrackedEntity;updatePlayer(Lnet/minecraft/server/level/ServerPlayer;)V"), require = 1)
    private void phoenium$regionalTrackingMovedPlayer(@Coerce Object tracker, ServerPlayer player,
                                                      Operation<Void> original) {
        if (!Phoenium.liveRuntime().regionalTrackingMovedPlayer((ChunkMap) (Object) this, tracker, player,
                () -> original.call(tracker, player))) original.call(tracker, player);
    }

    @Inject(method = "tick()V", at = @At("HEAD"), cancellable = true)
    private void phoenium$regionalTrackingTick(CallbackInfo ci) {
        if (Phoenium.liveRuntime().runRegionalTracking((ChunkMap) (Object) this)) ci.cancel();
    }

    @Inject(method = "addEntity", at = @At("TAIL"))
    private void phoenium$regionalTrackingAdded(Entity entity, CallbackInfo ci) {
        Phoenium.liveRuntime().regionalTrackingSourceAdded((ChunkMap) (Object) this, entity);
    }

    @Inject(method = "removeEntity", at = @At("TAIL"))
    private void phoenium$regionalTrackingRemoved(Entity entity, CallbackInfo ci) {
        Phoenium.liveRuntime().regionalTrackingSourceRemoved((ChunkMap) (Object) this, entity);
    }
}
