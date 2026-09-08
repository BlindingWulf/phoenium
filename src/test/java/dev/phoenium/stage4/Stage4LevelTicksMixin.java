package dev.phoenium.stage4;

import dev.phoenium.stage4probe.Stage4Trace;

import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
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

    @Inject(method = "tick(JILjava/util/function/BiConsumer;)V", at = @At("HEAD"), require = 1)
    private void phoenium$tick(long gameTime, int limit, java.util.function.BiConsumer<?, ?> action, CallbackInfo ci) {
        // Per-tick batch boundaries are intentionally omitted: the fixture compares callbacks.
    }

    @Inject(method = "tick(JILjava/util/function/BiConsumer;)V", at = @At("RETURN"), require = 1)
    private void phoenium$tickReturn(long gameTime, int limit, java.util.function.BiConsumer<?, ?> action, CallbackInfo ci) {
        // Per-tick batch boundaries are intentionally omitted: the fixture compares callbacks.
    }
}
