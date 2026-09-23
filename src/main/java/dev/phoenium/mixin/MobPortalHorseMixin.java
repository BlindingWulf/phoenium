package dev.phoenium.mixin;

import dev.phoenium.Phoenium;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Retain the exact AbstractHorse animation and state tail after Mob's portal cut. */
@Mixin(AbstractHorse.class)
abstract class MobPortalHorseMixin {
    @Inject(method="tick()V",at=@At(value="INVOKE",
        target="Lnet/minecraft/world/entity/animal/Animal;tick()V",shift=At.Shift.AFTER),
        cancellable=true,require=1,allow=1)
    private void phoenium$returnHorseTick(CallbackInfo ci) {
        if (Phoenium.liveRuntime().mobPortalSuspended((AbstractHorse)(Object)this)) ci.cancel();
    }
}
