package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.phoenium.service.NeighborCascadeContinuation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.redstone.Orientation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** S8-F6 audited deep read site. shouldSpreadLiquid (no exception table, pinned) performs all of
 * its neighbour reads before its only mutation, and it is the first call in neighborChanged. A cut
 * is decided before the native read and travels only to this class's neighborChanged wrapper. */
@Mixin(LiquidBlock.class)
abstract class NeighborCascadeLiquidMixin {
    private static final String NEIGHBOR_CHANGED = "neighborChanged(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;Lnet/minecraft/world/level/redstone/Orientation;Z)V";
    private static final String SHOULD_SPREAD = "shouldSpreadLiquid(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z";

    @WrapMethod(method = NEIGHBOR_CHANGED, require = 1)
    private void phoenium$cascadeNeighbor(BlockState state, Level level, BlockPos pos, Block block, Orientation orientation,
                                          boolean movedByPiston, Operation<Void> original) {
        NeighborCascadeContinuation.neighborChanged(level, pos,
            () -> original.call(state, level, pos, block, orientation, movedByPiston));
    }

    @WrapOperation(method = NEIGHBOR_CHANGED,
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/LiquidBlock;" + SHOULD_SPREAD), require = 1, expect = 1)
    private boolean phoenium$cascadeSpread(LiquidBlock self, Level level, BlockPos pos, BlockState state, Operation<Boolean> original) {
        return NeighborCascadeContinuation.spreadCheck(() -> original.call(self, level, pos, state));
    }

    @WrapOperation(method = SHOULD_SPREAD,
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"),
        require = 2, expect = 2)
    private BlockState phoenium$cascadeBlockRead(Level level, BlockPos pos, Operation<BlockState> original) {
        NeighborCascadeContinuation.deepRead(level, pos);
        return original.call(level, pos);
    }

    @WrapOperation(method = SHOULD_SPREAD,
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getFluidState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/material/FluidState;"),
        require = 2, expect = 2)
    private FluidState phoenium$cascadeFluidRead(Level level, BlockPos pos, Operation<FluidState> original) {
        NeighborCascadeContinuation.deepRead(level, pos);
        return original.call(level, pos);
    }

    @WrapOperation(method = SHOULD_SPREAD,
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"),
        require = 2, expect = 2)
    private boolean phoenium$cascadeMutation(Level level, BlockPos pos, BlockState state, Operation<Boolean> original) {
        NeighborCascadeContinuation.beforeMutation();
        return original.call(level, pos, state);
    }
}
