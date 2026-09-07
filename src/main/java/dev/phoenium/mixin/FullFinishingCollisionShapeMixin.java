package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.phoenium.chunk.FullFinishingQueries;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockCollisions;
import net.minecraft.world.level.CollisionGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(BlockCollisions.class)
abstract class FullFinishingCollisionShapeMixin {
    @WrapOperation(method = "computeNext()Ljava/lang/Object;",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/shapes/CollisionContext;getCollisionShape(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/CollisionGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/shapes/VoxelShape;"),
        require = 1, expect = 1)
    private VoxelShape phoenium$auditedDynamicShape(CollisionContext context, BlockState state, CollisionGetter world,
                                                  BlockPos pos, Operation<VoxelShape> original) {
        return FullFinishingQueries.collisionShape(state, world, pos, () -> original.call(context, state, world, pos));
    }
}
