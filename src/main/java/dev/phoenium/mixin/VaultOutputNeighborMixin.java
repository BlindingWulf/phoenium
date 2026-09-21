package dev.phoenium.mixin;

import dev.phoenium.service.VaultBlockEntityTickContinuation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Level.class)
abstract class VaultOutputNeighborMixin {
    @Inject(method = "updateNeighbourForOutputSignal", at = @At("HEAD"), cancellable = true, require = 1, allow = 1)
    private void phoenium$retainNeighbors(BlockPos pos, Block block, CallbackInfo ci) {
        if (VaultBlockEntityTickContinuation.output((Level) (Object) this, pos, block)) ci.cancel();
    }
}
