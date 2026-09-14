package dev.phoenium.a3.mixin;
import dev.phoenium.a3.MobDecisionServerProbe;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(WrappedGoal.class)
abstract class GoalObservationMixin {
    @Inject(method="canUse",at=@At("HEAD"),require=1)
    private void acquire(CallbackInfoReturnable<Boolean> ci){MobDecisionServerProbe.acquisition(((WrappedGoal)(Object)this).getGoal());}
}
