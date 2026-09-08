package dev.phoenium.stage4;

import dev.phoenium.stage4probe.Stage4Trace;

import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelTicks.class)
abstract class Stage4LevelTicksMixin {
    @Inject(method = "schedule(Lnet/minecraft/world/ticks/ScheduledTick;)V", at = @At("HEAD"), require = 1)
    private void phoenium$schedule(ScheduledTick<?> tick, CallbackInfo ci) {
        if (Stage4Trace.fixture(tick.pos())) Stage4Trace.event("tick.schedule", "type=" + tick.type() + " pos=" + tick.pos() + " trigger=" + tick.triggerTick()
            + " priority=" + tick.priority() + " order=" + tick.subTickOrder());
    }

    @WrapMethod(method = "tick(JILjava/util/function/BiConsumer;)V")
    private void phoenium$tick(long gameTime, int limit, java.util.function.BiConsumer<?, ?> action,
                                Operation<Void> original) {
        // The production regional wrapper may replace the original body at a cancellable HEAD.
        // Wrapping the method observes the completed original/service call in either case and
        // cannot manufacture a boundary when the invocation is bypassed.
        try { original.call(gameTime, limit, action); }
        finally { Stage4Trace.observeTick(gameTime); }
    }
}
