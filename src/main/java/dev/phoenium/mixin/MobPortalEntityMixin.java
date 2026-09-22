package dev.phoenium.mixin;

import dev.phoenium.Phoenium;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.world.entity.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
abstract class MobPortalEntityMixin {
    @Inject(method="handlePortal()V",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/PortalProcessor;getPortalDestination(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/world/level/portal/TeleportTransition;"),cancellable=true,require=1,allow=1)
    private void phoenium$retainMobDestination(CallbackInfo ci) {
        if((Object)this instanceof Mob mob && Phoenium.liveRuntime().suspendMobPortalDestination(mob,mob.portalProcess)) {
            Profiler.get().pop();ci.cancel();
        }
    }
    @Inject(method="baseTick()V",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/Entity;handlePortal()V",shift=At.Shift.AFTER),cancellable=true,require=1,allow=1)
    private void phoenium$returnMobBase(CallbackInfo ci) {
        if((Object)this instanceof Mob mob && Phoenium.liveRuntime().mobPortalSuspended(mob)) {
            Profiler.get().pop();ci.cancel();
        }
    }
}
