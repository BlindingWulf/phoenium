package dev.phoenium.engine.mixin;

import dev.phoenium.engine.TileContext;
import dev.phoenium.engine.TileEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickPriority;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * S9-E1 (engine mode only): a block or fluid tick scheduled from a tile is scheduled after the colour, on the server
 * thread, re-stamped with the level's next sub-tick order, so same-tick ordering follows tile order for any worker count.
 */
@Mixin(LevelTicks.class)
abstract class EngineLevelTicksMixin<T> {
    @Shadow public abstract void schedule(ScheduledTick<T> tick);

    @Inject(method = "schedule", at = @At("HEAD"), cancellable = true)
    private void phoenium$deferSchedule(ScheduledTick<T> tick, CallbackInfo ci) {
        TileContext context = TileEngine.current();
        if (context == null) return;
        ServerLevel level = context.level();
        T type = tick.type();
        BlockPos pos = tick.pos().immutable();
        long triggerTick = tick.triggerTick();
        TickPriority priority = tick.priority();
        context.defer(() -> this.schedule(new ScheduledTick<>(type, pos, triggerTick, priority, level.nextSubTickCount())));
        ci.cancel();
    }
}
