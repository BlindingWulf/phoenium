package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.phoenium.Phoenium;
import dev.phoenium.ownership.OwnershipContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.timers.TimerQueue;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Pinned 26.2: tickTime advances the /schedule timer queue exactly once; the live prefix defers it owner-free. */
@Mixin(ServerLevel.class)
abstract class ScheduledFunctionBoundaryMixin {
    @WrapOperation(method = "tickTime", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/timers/TimerQueue;tick(Ljava/lang/Object;J)V"), require = 1, expect = 1)
    private void phoenium$deferScheduledFunctions(TimerQueue<?> queue, Object context, long time, Operation<Void> original) {
        if (Phoenium.liveRuntime().scheduledFunctions().capture(OwnershipContext.current().isPresent(),
                () -> original.call(queue, context, time))) return;
        original.call(queue, context, time);
    }
}
