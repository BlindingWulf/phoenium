package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.phoenium.chunk.FullWorldgenEnvironment;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(WorldGenRegion.class)
abstract class FullWorldgenEnvironmentMixin {
    @WrapOperation(method = "getCurrentDifficultyAt", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;getMoonBrightness(Lnet/minecraft/core/BlockPos;)F"), require = 1)
    private float phoenium$moon(ServerLevel world, BlockPos position, Operation<Float> original) {
        return FullWorldgenEnvironment.moonBrightness(world,
                ((ChunkMapLoadAccess) world.getChunkSource().chunkMap).phoenium$mainExecutor(),
                () -> original.call(world, position));
    }
}
