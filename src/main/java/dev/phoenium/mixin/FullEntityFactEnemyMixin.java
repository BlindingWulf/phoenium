package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.*;
import dev.phoenium.chunk.FullEntityFacts;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(LivingEntity.class)
abstract class FullEntityFactEnemyMixin {
    @WrapOperation(method = "canAttack", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;canBeSeenAsEnemy()Z"), require = 1)
    private boolean phoenium$enemy(LivingEntity target, Operation<Boolean> original) { return FullEntityFacts.enemy(target, () -> original.call(target)); }
}
