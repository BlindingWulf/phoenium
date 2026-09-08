package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.phoenium.Phoenium;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.NaturalSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Replaces NaturalSpawner's only global player-position observation with the exact
 * owner-published level-player projection captured for this original chunk callback. */
@Mixin(NaturalSpawner.class)
abstract class RegionalNaturalSpawnerMixin {
    @WrapOperation(method = "spawnCategoryForPosition", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/level/ServerLevel;getNearestPlayer(DDDDZ)Lnet/minecraft/world/entity/player/Player;"))
    private static Player phoenium$nearestPlayer(ServerLevel level, double x, double y, double z,
            double range, boolean creative, Operation<Player> original) {
        if (!Phoenium.liveRuntime().regionalNaturalSpawnActive(level)) return original.call(level, x, y, z, range, creative);
        if (range != -1.0 || creative) throw new IllegalStateException("pinned natural-spawn nearest-player call changed");
        return Phoenium.liveRuntime().regionalNaturalSpawnPlayer(level, x, y, z);
    }

    @WrapOperation(method = "spawnCategoryForPosition", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/player/Player;distanceToSqr(DDD)D"))
    private static double phoenium$nearestDistance(Player player, double x, double y, double z, Operation<Double> original,
            net.minecraft.world.entity.MobCategory category, ServerLevel level) {
        if (!Phoenium.liveRuntime().regionalNaturalSpawnActive(level)) return original.call(player, x, y, z);
        return Phoenium.liveRuntime().regionalNaturalSpawnDistance(level, player, x, y, z);
    }
}
