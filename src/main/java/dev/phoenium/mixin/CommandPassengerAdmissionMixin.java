package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.phoenium.Phoenium;
import dev.phoenium.chunk.CommandPassengerAdmission;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Native duplicate-UUID refusal precedes this exact insertion call. */
@Mixin(ServerLevel.class)
abstract class CommandPassengerAdmissionMixin {
    @WrapOperation(method = "tryAddFreshEntityWithPassengers", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/level/ServerLevel;addFreshEntityWithPassengers(Lnet/minecraft/world/entity/Entity;)V"),
        require = 1, expect = 1)
    private void phoenium$commandPassengerIdentity(ServerLevel level, Entity root, Operation<Void> original) {
        if (root.getPassengers().isEmpty()) {
            original.call(level, root);
            return;
        }
        CommandPassengerAdmission.insert(level, root,
            Phoenium.liveRuntime().commandSpawnPassengerPublisher(level),
            () -> original.call(level, root));
    }
}
