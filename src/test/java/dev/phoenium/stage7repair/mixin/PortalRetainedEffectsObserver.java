package dev.phoenium.stage7repair.mixin;

import dev.phoenium.live.CrossDimensionReplacementBoundary;
import dev.phoenium.live.NativePortalTransfer;
import dev.phoenium.ownership.ObjectOwnership;
import dev.phoenium.stage7repair.PortalLifecycleObserverBridge;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Test-only observation; marker is invoked inside the exact source/destination owner body. */
@Mixin(value = NativePortalTransfer.class, remap = false)
abstract class PortalRetainedEffectsObserver {
    @Shadow @Final private Entity source;
    @Shadow @Final private ServerLevel destinationLevel;
    @Shadow @Final private CrossDimensionReplacementBoundary.Token token;

    @Inject(method = "retained", at = @At("HEAD"), require = 1)
    private static void phoenium$retained(Entity entity, ObjectOwnership binding,
            CrossDimensionReplacementBoundary.Token token, ServerLevel destination, String reason,
            CallbackInfo ci) {
        PortalLifecycleObserverBridge.retained(entity, binding, token, destination, reason);
    }

    @Inject(method = "marker", at = @At("HEAD"), require = 1)
    private void phoenium$marker(String phase, CallbackInfo ci) {
        PortalLifecycleObserverBridge.marker(phase, source, destinationLevel, token);
    }

    @Inject(method = "addressable", at = @At("RETURN"), require = 1)
    private static void phoenium$addressable(Entity entity, ObjectOwnership binding, CallbackInfo ci) {
        if (NativePortalTransfer.inserting(entity))
            PortalLifecycleObserverBridge.wrapper("ADDRESSABLE", null, entity,
                (ServerLevel) entity.level(), binding, true);
    }

    @Inject(method = "callbackInstalled", at = @At("RETURN"), require = 1)
    private static void phoenium$callbackInstalled(Entity entity, CallbackInfo ci) {
        if (NativePortalTransfer.inserting(entity))
            PortalLifecycleObserverBridge.wrapper("CALLBACK_INSTALLED", null, entity,
                (ServerLevel) entity.level(), null, true);
    }

    @Inject(method = "settled", at = @At("RETURN"), require = 1)
    private void phoenium$settled(CallbackInfo ci) {
        PortalLifecycleObserverBridge.settled(source);
    }
}
