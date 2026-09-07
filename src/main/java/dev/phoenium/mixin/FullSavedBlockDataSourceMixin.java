package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.phoenium.chunk.FullSavedContextInputs;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.contents.data.BlockDataSource;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Keeps the original block-NBT source body while selecting its exact retained initialized chunk. */
@Mixin(BlockDataSource.class)
abstract class FullSavedBlockDataSourceMixin {
    @WrapOperation(method = "getData", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;isLoaded(Lnet/minecraft/core/BlockPos;)Z"), require = 1)
    private boolean phoenium$loaded(ServerLevel world, BlockPos position, Operation<Boolean> original) {
        return FullSavedContextInputs.blockLoaded(world, position, () -> original.call(world, position));
    }

    @WrapOperation(method = "getData", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;getBlockEntity(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;"), require = 1)
    private BlockEntity phoenium$blockEntity(ServerLevel world, BlockPos position, Operation<BlockEntity> original) {
        return FullSavedContextInputs.blockEntity(world, position, () -> original.call(world, position));
    }
}
