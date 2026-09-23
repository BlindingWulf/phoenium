package dev.phoenium.mixin;

import dev.phoenium.Phoenium;
import net.minecraft.world.entity.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntity.class)
abstract class MobPortalLivingMixin {
    @Inject(method="baseTick()V",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/Entity;baseTick()V",shift=At.Shift.AFTER),cancellable=true,require=1,allow=1)
    private void phoenium$returnMobLivingBase(CallbackInfo ci) {
        if((Object)this instanceof Mob m && Phoenium.liveRuntime().mobPortalSuspended(m)
                || (Object)this instanceof net.minecraft.world.entity.decoration.ArmorStand stand
                    && Phoenium.liveRuntime().armorStandPortalSuspended(stand)
                || (Object)this instanceof net.minecraft.world.entity.decoration.Mannequin mannequin
                    && Phoenium.liveRuntime().armorStandPortalSuspended(mannequin)) ci.cancel();
    }
    @Inject(method="tick()V",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/Entity;tick()V",shift=At.Shift.AFTER),cancellable=true,require=1,allow=1)
    private void phoenium$returnMobLivingTick(CallbackInfo ci) {
        if((Object)this instanceof Mob m && Phoenium.liveRuntime().mobPortalSuspended(m)
                || (Object)this instanceof net.minecraft.world.entity.decoration.ArmorStand stand
                    && Phoenium.liveRuntime().armorStandPortalSuspended(stand)
                || (Object)this instanceof net.minecraft.world.entity.decoration.Mannequin mannequin
                    && Phoenium.liveRuntime().armorStandPortalSuspended(mannequin)) ci.cancel();
    }
}
