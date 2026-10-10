package dev.phoenium.engine.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.phoenium.engine.TileContext;
import dev.phoenium.engine.TileEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;

/**
 * S9-E1 (engine mode only): a block write from a tile outside its claim. S9-E3: a service (the server thread writes it
 * while no tile runs); services off: a trap (counted and logged, the write done in place; strict throws).
 */
@Mixin(LevelChunk.class)
abstract class EngineLevelChunkMixin {
    @WrapMethod(method = "setBlockState")
    private @Nullable BlockState phoenium$claimedWrite(BlockPos pos, BlockState state, int flags, Operation<BlockState> original) {
        TileContext context = TileEngine.current();
        if (context == null || context.claims(pos.getX() >> 4, pos.getZ() >> 4)) return original.call(pos, state, flags);
        return TileEngine.write(context, (LevelChunk) (Object) this, pos, () -> original.call(pos, state, flags));
    }
}
