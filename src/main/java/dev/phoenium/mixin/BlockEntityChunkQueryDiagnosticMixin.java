package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.phoenium.chunk.BlockEntityChunkQueryDiagnostic;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Level.class)
abstract class BlockEntityChunkQueryDiagnosticMixin {
    @WrapMethod(method = "getBlockState")
    private BlockState phoenium$observeBlockEntityRead(BlockPos position, Operation<BlockState> original) {
        // S9-D4: with no diagnostic scope open anywhere, blockState() would run the original unchanged.
        if (BlockEntityChunkQueryDiagnostic.idle()) return original.call(position);
        return BlockEntityChunkQueryDiagnostic.blockState((Level) (Object) this, position, () -> original.call(position));
    }
}
