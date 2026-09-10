package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.phoenium.service.GeyserFloodgateSkinReconnectBridge;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Guards Geyser's already-queued Floodgate skin-uploader reconnect with Geyser's own closed state.
 * The locked provider can queue reconnect() before shutdown, then close the uploader while the
 * delayed task remains eligible to run on ScheduledThreadPoolExecutor after shutdown().
 */
@Pseudo
@Mixin(targets = "org.geysermc.geyser.skin.FloodgateSkinUploader", remap = false)
abstract class GeyserFloodgateSkinUploaderMixin {
    @Shadow private volatile boolean closed;

    @WrapOperation(
        method = "reconnectLater(Lorg/geysermc/geyser/GeyserImpl;)V",
        at = @At(value = "INVOKE",
            target = "Ljava/util/concurrent/ScheduledExecutorService;schedule(Ljava/lang/Runnable;JLjava/util/concurrent/TimeUnit;)Ljava/util/concurrent/ScheduledFuture;",
            remap = false),
        require = 1, expect = 1)
    private ScheduledFuture<?> phoenium$guardDelayedReconnect(ScheduledExecutorService executor,
            Runnable reconnect, long delay, TimeUnit unit, Operation<ScheduledFuture<?>> original) {
        return original.call(executor,
            GeyserFloodgateSkinReconnectBridge.guard(() -> closed, reconnect), delay, unit);
    }
}
