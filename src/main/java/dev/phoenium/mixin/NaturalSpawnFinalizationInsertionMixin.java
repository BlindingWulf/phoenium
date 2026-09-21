package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.phoenium.chunk.FreshPassengerAdmission;
import dev.phoenium.chunk.FullFinishingServices;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.zombie.Drowned;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.ServerLevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Pinned 26.2 jockey/vehicle insertions that can synchronously track a new root while the natural
 * spawn candidate is already attached but has not yet reached NaturalSpawner's later bulk call.
 * Outside that proved outer natural-spawn scope these native calls are completely unchanged.
 */
@Mixin({Zombie.class, Drowned.class, Husk.class})
public abstract class NaturalSpawnFinalizationInsertionMixin {
    private static final String FINALIZE = "finalizeSpawn(Lnet/minecraft/world/level/ServerLevelAccessor;"
        + "Lnet/minecraft/world/DifficultyInstance;Lnet/minecraft/world/entity/EntitySpawnReason;"
        + "Lnet/minecraft/world/entity/SpawnGroupData;)Lnet/minecraft/world/entity/SpawnGroupData;";

    @WrapOperation(method = FINALIZE, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/ServerLevelAccessor;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"),
        require = 1, expect = 1)
    private boolean phoenium$directFinalizationInsertion(ServerLevelAccessor accessor, Entity root,
            Operation<Boolean> original) {
        if (!(accessor instanceof ServerLevel level)) return original.call(accessor, root);
        FreshPassengerAdmission.NaturalSpawnScope scope = FreshPassengerAdmission.currentNaturalSpawnScope(level);
        if (scope == null) return original.call(accessor, root);
        try (var turn = FullFinishingServices.forServer(level.getServer()).referenceAccess()) {
            return scope.directInsertion(root, () -> original.call(accessor, root));
        }
    }
}
