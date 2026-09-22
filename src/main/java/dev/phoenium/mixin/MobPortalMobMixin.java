package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.phoenium.Phoenium;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mob.class)
abstract class MobPortalMobMixin {
    @WrapMethod(method="tick()V")
    private void phoenium$mobPortalTick(Operation<Void> original) {
        Mob self=(Mob)(Object)this;
        if(Phoenium.liveRuntime().mobPortalSuspended(self)) return;
        Phoenium.liveRuntime().beginMobPortalTick(self);
        try { original.call(); Phoenium.liveRuntime().completeMobPortalTick(self); }
        finally { Phoenium.liveRuntime().finishMobPortalTick(self); }
    }
    @Inject(method="baseTick()V",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/LivingEntity;baseTick()V",shift=At.Shift.AFTER),cancellable=true,require=1,allow=1)
    private void phoenium$returnMobBase(CallbackInfo ci) { if(Phoenium.liveRuntime().mobPortalSuspended((Mob)(Object)this)) ci.cancel(); }
    @Inject(method="tick()V",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/LivingEntity;tick()V",shift=At.Shift.AFTER),cancellable=true,require=1,allow=1)
    private void phoenium$returnMobTick(CallbackInfo ci) { if(Phoenium.liveRuntime().mobPortalSuspended((Mob)(Object)this)) ci.cancel(); }
}
