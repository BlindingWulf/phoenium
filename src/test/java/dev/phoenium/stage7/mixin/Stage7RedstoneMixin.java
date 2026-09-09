package dev.phoenium.stage7.mixin;

import dev.phoenium.stage7.Stage7ConcurrentProbe;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
abstract class Stage7RedstoneMixin {
    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void after(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<BlockState> cir) {
        // Observe the inner native mutation while its caller still retains the claim;
        // the outer Level.setBlock wrapper has already revoked it at RETURN.
        Stage7ConcurrentProbe.block(((LevelChunk) (Object) this).getLevel(), pos, state,
                cir.getReturnValue() != null && cir.getReturnValue() != state);
    }
}
