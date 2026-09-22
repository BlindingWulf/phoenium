package dev.phoenium.mixin;

import dev.phoenium.Phoenium;
import net.minecraft.world.entity.monster.cubemob.AbstractCubeMob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Retain the cube-only native tick tail after the shared Mob portal cut. */
@Mixin(AbstractCubeMob.class)
abstract class MobPortalCubeMixin {
    @Inject(method="tick()V",at=@At(value="INVOKE",
        target="Lnet/minecraft/world/entity/AgeableMob;tick()V",shift=At.Shift.AFTER),
        cancellable=true,require=1,allow=1)
    private void phoenium$returnCubeTick(CallbackInfo ci) {
        if (Phoenium.liveRuntime().mobPortalSuspended((AbstractCubeMob)(Object)this)) ci.cancel();
    }
}
