package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.phoenium.Phoenium;
import dev.phoenium.chunk.FreshPassengerAdmission;
import dev.phoenium.chunk.FullFinishingServices;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.zombie.Drowned;
import net.minecraft.world.entity.monster.zombie.Husk;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.ServerLevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import java.util.function.BooleanSupplier;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Pinned 26.2 jockey/vehicle insertions that can synchronously track a new root while the finalizing
 * mob is already attached but has not yet been inserted by its caller. Inside the exact natural-spawn
 * scope the outer candidate keeps its scoped custody; every other caller of finalizeSpawn (spawners,
 * eggs, summon, siege, reinforcements, nested natural riders) gets a detached custody bound to the
 * finalizing mob. A vehicle that carries nothing is the untouched native call.
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
        return phoenium$finalizationInsertion(level, (Entity) (Object) this, root,
            () -> original.call(accessor, root));
    }

    /** Only a fresh vehicle that carries the finalizing mob, or the exact natural candidate, enters custody. */
    private static boolean phoenium$finalizationInsertion(ServerLevel level, Entity finalizing, Entity root,
            BooleanSupplier original) {
        if (FreshPassengerAdmission.currentNaturalSpawnScope(level) == null
                && (root.getPassengers().isEmpty() || root == finalizing)) return original.getAsBoolean();
        try (var turn = FullFinishingServices.forServer(level.getServer()).referenceAccess()) {
            return FreshPassengerAdmission.finalizationInsertion(level, finalizing, root,
                () -> Phoenium.liveRuntime().commandSpawnPassengerPublisher(level), original);
        }
    }
}
