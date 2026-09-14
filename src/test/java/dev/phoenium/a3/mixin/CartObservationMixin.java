package dev.phoenium.a3.mixin;
import dev.phoenium.a3.MobDecisionServerProbe;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(AbstractMinecart.class)
abstract class CartObservationMixin {
    @Inject(method="tick",at=@At("HEAD"),require=1)
    private void tick(CallbackInfo ci){MobDecisionServerProbe.tick((AbstractMinecart)(Object)this,false);}
}
