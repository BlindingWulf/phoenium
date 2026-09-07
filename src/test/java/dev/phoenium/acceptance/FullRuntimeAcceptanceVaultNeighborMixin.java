package dev.phoenium.acceptance;

import dev.phoenium.chunk.FullRuntimeAcceptanceProbe;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Test-only witness for the original output-neighbor read reached by vault setChanged. */
@Mixin(Level.class)
abstract class FullRuntimeAcceptanceVaultNeighborMixin {
    @WrapOperation(method = "updateNeighbourForOutputSignal", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)"
                    + "Lnet/minecraft/world/level/block/state/BlockState;"), require = 2, expect = 2)
    private BlockState phoenium$recordVaultNeighbor(Level level, BlockPos position,
                                                     Operation<BlockState> original) {
        BlockState state = original.call(level, position);
        if (level instanceof net.minecraft.server.level.ServerLevel serverLevel)
            FullRuntimeAcceptanceProbe.recordVaultNeighborRead(serverLevel, position);
        return state;
    }
}
