package dev.phoenium.a3.mixin;
import dev.phoenium.a3.MobDecisionServerProbe;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Mob.class)
abstract class MobObservationMixin {
    @Inject(method="tick",at=@At("HEAD"),require=1)
    private void tick(CallbackInfo ci){MobDecisionServerProbe.tick((Mob)(Object)this,false);}
    @Inject(method="serverAiStep",at=@At("HEAD"),require=1)
    private void ai(CallbackInfo ci){MobDecisionServerProbe.tick((Mob)(Object)this,true);}
    @Inject(method="checkDespawn",at=@At("HEAD"),require=1)
    private void despawn(CallbackInfo ci){MobDecisionServerProbe.despawn((Mob)(Object)this);}
}
