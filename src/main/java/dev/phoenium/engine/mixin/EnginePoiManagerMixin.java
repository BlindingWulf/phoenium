package dev.phoenium.engine.mixin;

import dev.phoenium.engine.TileContext;
import dev.phoenium.engine.TileEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * S9-E1 (engine mode only): a POI released from a tile (a villager killed by a zombie releases its bed and job site)
 * is released after the colour, on the server thread, in tile order; a POI removed meanwhile is skipped. The call
 * answers true at once.
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
}
