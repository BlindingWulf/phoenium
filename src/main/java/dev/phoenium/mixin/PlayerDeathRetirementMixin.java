package dev.phoenium.mixin;

import dev.phoenium.chunk.PlayerRespawnHandoff;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Marks the exact player only after tickDeath's actual native remove returns. */
@Mixin(LivingEntity.class)
abstract class PlayerDeathRetirementMixin {
    @WrapOperation(method = "tickDeath()V", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/LivingEntity;remove(Lnet/minecraft/world/entity/Entity$RemovalReason;)V"),
        require = 1, allow = 1)
    private void phoenium$deathRetirementCompleted(LivingEntity entity, Entity.RemovalReason reason, Operation<Void> original) {
        if (entity == (Object) this && reason == Entity.RemovalReason.KILLED && entity instanceof ServerPlayer player)
            PlayerRespawnHandoff.captureDeathPosition(player);
        original.call(entity, reason);
        if (entity == (Object) this && reason == Entity.RemovalReason.KILLED && entity instanceof ServerPlayer player)
            PlayerRespawnHandoff.recordDeathRemovalCompleted(player);
    }
}
