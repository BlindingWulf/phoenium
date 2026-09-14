package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.phoenium.live.MobDecisionContext;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(GoalSelector.class)
abstract class MobDecisionGoalSelectorMixin {
    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/ai/goal/WrappedGoal;canUse()Z"), require = 1, allow = 1)
    private boolean phoenium$inactiveAcquisition(WrappedGoal goal, Operation<Boolean> original) {
        return MobDecisionContext.allow(goal.getGoal()) && original.call(goal);
    }
}
