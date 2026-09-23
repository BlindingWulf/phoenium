package dev.phoenium.stage7repair.mixin;

import dev.phoenium.stage7repair.PortalLifecycleObserverBridge;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observation only: no cancellation or modification of the native removal. */
@Mixin(Entity.class)
abstract class PortalSourceRetirementObserver {
    @Inject(method = "setRemoved", at = @At("HEAD"), require = 1)
    private void phoenium$observePortalSourceRetirement(Entity.RemovalReason reason, CallbackInfo ci) {
        PortalLifecycleObserverBridge.sourceRetiring((Entity) (Object) this, reason);
    }
}
