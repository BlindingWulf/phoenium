package dev.phoenium.stage7repair.mixin;

import dev.phoenium.stage7repair.PortalLifecycleObserverBridge;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observation only around the existing native insertion and its tracking callbacks. */
@Mixin(ServerLevel.class)
abstract class PortalInsertionObserver {
    @Inject(method = "addDuringTeleport", at = @At("HEAD"), require = 1)
    private void phoenium$observeInsertionHead(Entity entity, CallbackInfo ci) {
        PortalLifecycleObserverBridge.insertion((ServerLevel) (Object) this, entity, false);
    }
    @Inject(method = "addDuringTeleport", at = @At("RETURN"), require = 1)
    private void phoenium$observeInsertionReturn(Entity entity, CallbackInfo ci) {
        PortalLifecycleObserverBridge.insertion((ServerLevel) (Object) this, entity, true);
    }
}
