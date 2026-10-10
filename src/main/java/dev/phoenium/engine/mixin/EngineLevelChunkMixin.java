package dev.phoenium.engine.mixin;

import dev.phoenium.engine.TileContext;
import dev.phoenium.engine.TileEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** S9-E1 (engine mode only): a block write from a tile outside its claim is a trap (counted and logged; strict throws). */
@Mixin(LevelChunk.class)
abstract class EngineLevelChunkMixin {
    @Inject(method = "setBlockState", at = @At("HEAD"))
    private void phoenium$claimedWrite(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<BlockState> cir) {
        TileContext context = TileEngine.current();
        if (context != null && !context.claims(pos.getX() >> 4, pos.getZ() >> 4))
            TileEngine.writeTrap(context, (LevelChunk) (Object) this, pos);
    }
}
