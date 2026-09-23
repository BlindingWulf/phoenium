package dev.phoenium.stage7repair.mixin;

import dev.phoenium.stage7repair.PortalLifecycleObserverBridge;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observes the actual ChunkMap admission edge, including failed calls that lack RETURN. */
@Mixin(ChunkMap.class)
abstract class PortalTrackingAdmissionObserver {
    @Shadow @Final private ServerLevel level;

    @Inject(method = "addEntity", at = @At("HEAD"), require = 1)
    private void phoenium$observeTrackingHead(Entity entity, CallbackInfo ci) {
        PortalLifecycleObserverBridge.tracking(level, entity, false);
    }
    @Inject(method = "addEntity", at = @At("RETURN"), require = 1)
    private void phoenium$observeTrackingReturn(Entity entity, CallbackInfo ci) {
        PortalLifecycleObserverBridge.tracking(level, entity, true);
    }
}
