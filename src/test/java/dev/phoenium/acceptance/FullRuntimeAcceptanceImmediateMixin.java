package dev.phoenium.acceptance;

import dev.phoenium.chunk.FullRuntimeAcceptanceProbe;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Test-only witness for the original IMMEDIATE block-entity lookup. */
@Mixin(LevelChunk.class)
abstract class FullRuntimeAcceptanceImmediateMixin {
    @Inject(method = "getBlockEntity(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/chunk/LevelChunk$EntityCreationType;)"
            + "Lnet/minecraft/world/level/block/entity/BlockEntity;", at = @At("RETURN"))
    private void phoenium$recordImmediate(BlockPos position, LevelChunk.EntityCreationType creation,
                                           CallbackInfoReturnable<BlockEntity> callback) {
        FullRuntimeAcceptanceProbe.recordImmediateShulker((LevelChunk) (Object) this, position, creation,
                callback.getReturnValue());
    }
}
