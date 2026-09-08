package dev.phoenium.mixin;

import dev.phoenium.Phoenium;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Producers and consumers of the pinned LevelTicks container graph. */
@Mixin(LevelTicks.class)
abstract class RegionalLevelTicksMixin<T> {
    @WrapMethod(method = "clearArea")
    private void phoenium$clearArea(BoundingBox area, Operation<Void> original) {
        LevelTicks<T> self = (LevelTicks<T>) (Object) this;
        if (!Phoenium.liveRuntime().mutateRegionalTickArea(self, self, area, null, () -> original.call(area)))
            original.call(area);
    }

    @WrapMethod(method = "copyAreaFrom")
    private void phoenium$copyAreaFrom(LevelTicks<T> source, BoundingBox area, Vec3i offset, Operation<Void> original) {
        if (!Phoenium.liveRuntime().mutateRegionalTickArea((LevelTicks<T>) (Object) this, source, area, offset,
                () -> original.call(source, area, offset))) original.call(source, area, offset);
    }

    @Inject(method = "addContainer", at = @At("TAIL"))
    private void phoenium$containerAdded(ChunkPos pos, LevelChunkTicks<T> container, CallbackInfo ci) {
        Phoenium.liveRuntime().regionalTickContainerAdded((LevelTicks<T>) (Object) this, pos, container);
    }

    @Inject(method = "removeContainer", at = @At("HEAD"))
    private void phoenium$containerRemoved(ChunkPos pos, CallbackInfo ci) {
        var access = (RegionalLevelTicksAccess<T>) this;
        LevelChunkTicks<T> container = access.phoenium$getAllContainers().get(pos.pack());
        if (container != null) Phoenium.liveRuntime().regionalTickContainerRemoved((LevelTicks<T>) (Object) this, pos, container);
    }

    @Inject(method = "schedule", at = @At("HEAD"), cancellable = true)
    private void phoenium$requireTickScheduleOwner(ScheduledTick<T> tick, CallbackInfo ci) {
        if (Phoenium.liveRuntime().scheduleRegionalTick((LevelTicks<T>) (Object) this, tick)) ci.cancel();
    }

    @Inject(method = "schedule", at = @At("TAIL"))
    private void phoenium$tickScheduled(ScheduledTick<T> tick, CallbackInfo ci) {
        var access = (RegionalLevelTicksAccess<T>) this;
        long chunkKey = ChunkPos.pack(tick.pos());
        LevelChunkTicks<T> container = access.phoenium$getAllContainers().get(chunkKey);
        if (container != null) Phoenium.liveRuntime().regionalTickScheduled((LevelTicks<T>) (Object) this, chunkKey, container);
    }

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void phoenium$regionalTick(long currentTick, int maxTicksToProcess,
                                       BiConsumer<BlockPos, T> output, CallbackInfo ci) {
        if (Phoenium.liveRuntime().runRegionalScheduledTicks((LevelTicks<T>) (Object) this, currentTick, maxTicksToProcess, output)) ci.cancel();
    }

    @Inject(method = "willTickThisTick", at = @At("HEAD"), cancellable = true)
    private void phoenium$regionalWillTick(BlockPos pos, T type, CallbackInfoReturnable<Boolean> cir) {
        Boolean regional = Phoenium.liveRuntime().regionalWillTickThisTick((LevelTicks<T>) (Object) this, pos, type);
        if (regional != null) cir.setReturnValue(regional);
    }
}
