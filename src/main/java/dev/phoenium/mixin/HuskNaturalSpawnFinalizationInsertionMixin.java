package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.phoenium.chunk.FreshPassengerAdmission;
import dev.phoenium.chunk.FullFinishingServices;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.level.ServerLevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Extends the same exact Husk/Camel graph before its later Parched helper insertion. */
@Mixin(Husk.class)
abstract class HuskNaturalSpawnFinalizationInsertionMixin {
    private static final String FINALIZE = "finalizeSpawn(Lnet/minecraft/world/level/ServerLevelAccessor;"
        + "Lnet/minecraft/world/DifficultyInstance;Lnet/minecraft/world/entity/EntitySpawnReason;"
        + "Lnet/minecraft/world/entity/SpawnGroupData;)Lnet/minecraft/world/entity/SpawnGroupData;";

    @WrapOperation(method = FINALIZE, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/ServerLevelAccessor;addFreshEntityWithPassengers(Lnet/minecraft/world/entity/Entity;)V"),
        require = 1, expect = 1)
    private void phoenium$finalizationBulkInsertion(ServerLevelAccessor accessor, Entity root,
            Operation<Void> original) {
        if (!(accessor instanceof ServerLevel level)) { original.call(accessor, root); return; }
        FreshPassengerAdmission.NaturalSpawnScope scope = FreshPassengerAdmission.currentNaturalSpawnScope(level);
        if (scope == null) { original.call(accessor, root); return; }
        try (var turn = FullFinishingServices.forServer(level.getServer()).referenceAccess()) {
            scope.bulkInsertion(root, () -> original.call(accessor, root));
        }
    }
}
