package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.phoenium.live.MobDecisionContext;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Mob.class)
abstract class MobDecisionMixin {
    @WrapOperation(method = "serverAiStep", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/ai/goal/GoalSelector;tick()V"), require = 2, allow = 2)
    private void phoenium$decisionContext(GoalSelector selector, Operation<Void> original) {
        MobDecisionContext.selector((Mob) (Object) this, () -> original.call(selector));
    }
}
