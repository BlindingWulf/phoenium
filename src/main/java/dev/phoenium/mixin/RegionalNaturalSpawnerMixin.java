package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import dev.phoenium.Phoenium;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Replaces NaturalSpawner's only global player-position observation with the exact
 * owner-published level-player projection captured for this original chunk callback. */
@Mixin(NaturalSpawner.class)
abstract class RegionalNaturalSpawnerMixin {
    @WrapMethod(method = "spawnForChunk")
    private static void phoenium$retainQueries(ServerLevel level, net.minecraft.world.level.chunk.LevelChunk chunk,
            NaturalSpawner.SpawnState state, java.util.List<net.minecraft.world.entity.MobCategory> categories,
            Operation<Void> original) {
        if (!dev.phoenium.service.NaturalSpawnContinuation.capture(level, chunk, state, categories))
            original.call(level, chunk, state, categories);
    }
    private static final String SPAWN_POSITION = "spawnCategoryForPosition(Lnet/minecraft/world/entity/MobCategory;"
        + "Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ChunkAccess;"
        + "Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/NaturalSpawner$SpawnPredicate;"
        + "Lnet/minecraft/world/level/NaturalSpawner$AfterSpawnCallback;)V";

    /** Owns provisional passenger identity across finalizeSpawn and the later native insertion. */
    @WrapMethod(method = SPAWN_POSITION)
    private static void phoenium$passengerIdentityLifetime(net.minecraft.world.entity.MobCategory category,
            ServerLevel level, ChunkAccess chunk, net.minecraft.core.BlockPos position,
            NaturalSpawner.SpawnPredicate predicate, NaturalSpawner.AfterSpawnCallback callback,
            Operation<Void> original) {
        if (!Phoenium.liveRuntime().regionalNaturalSpawnActive(level)) {
            original.call(category, level, chunk, position, predicate, callback); return;
        }
        try (var scope = Phoenium.liveRuntime().naturalSpawnPassengerScope(level)) {
            original.call(category, level, chunk, position, predicate, callback);
        }
    }

    @WrapOperation(method = SPAWN_POSITION, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/Mob;finalizeSpawn(Lnet/minecraft/world/level/ServerLevelAccessor;"
            + "Lnet/minecraft/world/DifficultyInstance;Lnet/minecraft/world/entity/EntitySpawnReason;"
            + "Lnet/minecraft/world/entity/SpawnGroupData;)Lnet/minecraft/world/entity/SpawnGroupData;"),
        require = 1, expect = 1)
    private static SpawnGroupData phoenium$passengerFinalization(Mob mob, ServerLevelAccessor accessor,
            DifficultyInstance difficulty, EntitySpawnReason reason, SpawnGroupData data,
            Operation<SpawnGroupData> original) {
        if (!(accessor instanceof ServerLevel level)) return original.call(mob, accessor, difficulty, reason, data);
        var scope = dev.phoenium.chunk.FreshPassengerAdmission.currentNaturalSpawnScope(level);
        if (scope == null) return original.call(mob, accessor, difficulty, reason, data);
        try (var finalization = scope.finalizing(mob)) {
            return original.call(mob, accessor, difficulty, reason, data);
        }
    }

    @WrapOperation(method = SPAWN_POSITION, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/level/ServerLevel;addFreshEntityWithPassengers(Lnet/minecraft/world/entity/Entity;)V"),
        require = 1, expect = 1)
    private static void phoenium$passengerIdentity(ServerLevel level, net.minecraft.world.entity.Entity root,
            Operation<Void> original) {
        var scope = dev.phoenium.chunk.FreshPassengerAdmission.currentNaturalSpawnScope(level);
        if (scope != null) {
            try (var turn = dev.phoenium.chunk.FullFinishingServices.forServer(level.getServer()).referenceAccess()) {
                scope.bulkInsertion(root, () -> original.call(level, root));
            }
            return;
        }
        if (root.getPassengers().isEmpty()) { original.call(level, root); return; }
        try (var turn = dev.phoenium.chunk.FullFinishingServices.forServer(level.getServer()).referenceAccess();
             var graph = Phoenium.liveRuntime().naturalSpawnPassengerIdentity(level, root)) {
            original.call(level, root); graph.validate();
        }
    }

    @WrapOperation(method = SPAWN_POSITION, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/level/ServerLevel;getNearestPlayer(DDDDZ)Lnet/minecraft/world/entity/player/Player;"),
        require = 1, expect = 1)
    private static Player phoenium$nearestPlayer(ServerLevel level, double x, double y, double z,
            double range, boolean creative, Operation<Player> original) {
        if (!Phoenium.liveRuntime().regionalNaturalSpawnActive(level)) return original.call(level, x, y, z, range, creative);
        if (range != -1.0 || creative) throw new IllegalStateException("pinned natural-spawn nearest-player call changed");
        return Phoenium.liveRuntime().regionalNaturalSpawnPlayer(level, x, y, z);
    }

    @WrapOperation(method = SPAWN_POSITION, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/player/Player;distanceToSqr(DDD)D"),
        require = 1, expect = 1)
    private static double phoenium$nearestDistance(Player player, double x, double y, double z, Operation<Double> original,
            net.minecraft.world.entity.MobCategory category, ServerLevel level) {
        if (!Phoenium.liveRuntime().regionalNaturalSpawnActive(level)) return original.call(player, x, y, z);
        return Phoenium.liveRuntime().regionalNaturalSpawnDistance(level, player, x, y, z);
    }
}
