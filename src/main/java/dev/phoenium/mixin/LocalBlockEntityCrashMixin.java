package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.phoenium.service.LocalBlockEntityTick;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Preserve the original callback exception and actual native crash block state. */
@Mixin(BlockEntity.class)
abstract class LocalBlockEntityCrashMixin {
    @WrapOperation(method = "fillCrashReportCategory", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState phoenium$ownedCrashState(Level level, BlockPos pos, Operation<BlockState> original) {
        BlockState selected = LocalBlockEntityTick.crashBlockState(level, pos);
        return selected == null ? original.call(level, pos) : selected;
    }
}
