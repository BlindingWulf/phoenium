package dev.phoenium.engine.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.phoenium.engine.TileContext;
import dev.phoenium.engine.TileEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.lighting.DynamicGraphMinFixedPoint;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * S9-E1 (engine mode only): a POI released from a tile (a villager killed by a zombie releases its bed and job site)
 * is released after the colour, on the server thread, in tile order; a POI removed meanwhile is skipped. The call
 * answers true at once. S9-E4: dirty marks are deferred, the village-distance tracker's updates run on the server thread.
 */
@Mixin(PoiManager.class)
abstract class EnginePoiManagerMixin {
    @Inject(method = "release", at = @At("HEAD"), cancellable = true)
    private void phoenium$deferRelease(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        TileContext context = TileEngine.current();
        if (context == null) return;
        PoiManager poi = (PoiManager) (Object) this;
        BlockPos at = pos.immutable();
        context.defer(() -> {
            if (poi.getType(at).isPresent()) poi.release(at);
        });
        cir.setReturnValue(true);
    }

    /**
     * S9-E4: a ticket taken from a tile (a villager claiming a bed or a job site) changes the record at once (it lies in
     * the tile's claim); the section's dirty mark and the village-distance tracker update it queues are applied after the
     * colour, on the server thread, in tile order.
     */
    @WrapMethod(method = "setDirty")
    private void phoenium$deferDirty(long sectionPos, Operation<Void> original) {
        TileContext context = TileEngine.current();
        if (context == null) {
            original.call(sectionPos);
            return;
        }
        context.defer(() -> original.call(sectionPos));
    }

    /** S9-E4: the tracker's pending updates (queued by earlier changes) run on the server thread; with none, a read. */
    @WrapOperation(method = "sectionsToVillage", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/ai/village/poi/PoiManager$DistanceTracker;runAllUpdates()V"))
    private void phoenium$villageUpdates(@Coerce DynamicGraphMinFixedPoint tracker, Operation<Void> original) {
        if (TileEngine.current() == null || tracker.getQueueSize() == 0) {
            original.call(tracker);
            return;
        }
        TileEngine.villageUpdates(() -> original.call(tracker));
    }
}
