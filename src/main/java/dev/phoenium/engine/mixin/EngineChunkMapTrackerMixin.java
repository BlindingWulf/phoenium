package dev.phoenium.engine.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.phoenium.engine.EngineConfig;
import dev.phoenium.engine.EngineTracker;
import java.util.List;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

/**
 * S9-E4 (engine mode only): ChunkMap's tracker loops check each tracked entity against each player. A check whose
 * entity is out of the player's reach and not seen by the player can only unpair nothing, so it is skipped
 * ({@link EngineTracker}); the others call TrackedEntity.updatePlayer as before. ChunkMap.move (every player move
 * packet) checked every tracked entity of the level; tick() and addEntity check the moved entities and players.
 */
@Mixin(value = ChunkMap.class, priority = 2000)
abstract class EngineChunkMapTrackerMixin {
    @Shadow private int getPlayerViewDistance(ServerPlayer player) { throw new AssertionError(); }

    @WrapWithCondition(method = {"move", "addEntity"}, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/level/ChunkMap$TrackedEntity;updatePlayer(Lnet/minecraft/server/level/ServerPlayer;)V"))
    private boolean phoenium$updatePlayerMayChange(@Coerce Object tracker, ServerPlayer player) {
        return !EngineConfig.TRACKER || EngineTracker.mayChange((EngineTrackedEntityAccess) tracker, player, this.getPlayerViewDistance(player));
    }

    @WrapOperation(method = {"tick()V", "move", "addEntity"}, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/level/ChunkMap$TrackedEntity;updatePlayers(Ljava/util/List;)V"))
    private void phoenium$updatePlayersMayChange(@Coerce Object tracker, List<ServerPlayer> players, Operation<Void> original) {
        if (!EngineConfig.TRACKER) {
            original.call(tracker, players);
            return;
        }
        EngineTrackedEntityAccess access = (EngineTrackedEntityAccess) tracker;
        for (ServerPlayer player : players) {
            if (EngineTracker.mayChange(access, player, this.getPlayerViewDistance(player))) access.phoenium$updatePlayer(player);
        }
    }
}
