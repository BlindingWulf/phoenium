package dev.phoenium.acceptance;

import dev.phoenium.chunk.FullRuntimeAcceptanceProbe;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Test-only witness for the original output-neighbor read reached by vault setChanged. */
@Mixin(Level.class)
abstract class FullRuntimeAcceptanceVaultNeighborMixin {
    @Inject(method = "updateNeighbourForOutputSignal", at = @At("HEAD"))
    private void phoenium$recordVaultNeighbor(BlockPos position, Block block, CallbackInfo callback) {
        FullRuntimeAcceptanceProbe.recordVaultNeighbor(position);
    }
}
