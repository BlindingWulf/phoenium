package dev.phoenium.engine.mixin;

import dev.phoenium.engine.TileContext;
import dev.phoenium.engine.TileEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.util.debug.LevelDebugSynchronizers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * S9-E4 (engine mode only): a POI ticket change reported from a tile (a villager taking or releasing a POI) reaches the
 * debug synchronizer after the colour, on the server thread, in tile order.
 */
@Mixin(LevelDebugSynchronizers.class)
abstract class EngineDebugSynchronizersMixin {
    @Shadow public abstract void updatePoi(BlockPos pos);

    @Inject(method = "updatePoi", at = @At("HEAD"), cancellable = true)
    private void phoenium$deferPoi(BlockPos pos, CallbackInfo ci) {
        TileContext context = TileEngine.current();
        if (context == null) return;
        BlockPos at = pos.immutable();
        context.defer(() -> this.updatePoi(at));
        ci.cancel();
    }
}
