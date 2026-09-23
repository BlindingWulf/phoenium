package dev.phoenium.mixin;

import dev.phoenium.Phoenium;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.Mannequin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Retain the ArmorStand destination lookup before it can run under a source owner. */
@Mixin(Entity.class)
abstract class ArmorStandPortalEntityMixin {
    @Inject(method="handlePortal()V",at=@At(value="INVOKE",target=
        "Lnet/minecraft/world/entity/PortalProcessor;getPortalDestination(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/world/level/portal/TeleportTransition;"),
        cancellable=true,require=1,allow=1)
    private void phoenium$retainArmorStandDestination(CallbackInfo ci) {
        if (((Object)this instanceof ArmorStand || (Object)this instanceof Mannequin)
                && Phoenium.liveRuntime().suspendArmorStandPortalDestination((LivingEntity)(Object)this,
                    ((Entity)(Object)this).portalProcess)) {
            Profiler.get().pop();ci.cancel();
        }
    }
}
