package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.phoenium.chunk.FullFinishingQueries;
import dev.phoenium.chunk.FullInitializedInputs;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Exact collision query and original dynamic-shape IMMEDIATE block-entity edge. */
@Mixin(Level.class)
abstract class FullFinishingLevelQueriesMixin {
    @WrapOperation(method = "getChunkForCollisions(II)Lnet/minecraft/world/level/BlockGetter;",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;"),
        require = 1, expect = 1)
    private ChunkAccess phoenium$collisionInput(Level world, int x, int z, ChunkStatus status, boolean create,
                                               Operation<ChunkAccess> original) {
        return FullFinishingQueries.query(world, FullInitializedInputs.Purpose.COLLISION, x, z, status, create,
                () -> original.call(world, x, z, status, create));
    }

    @WrapOperation(method = "getBlockEntity(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getChunkAt(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/chunk/LevelChunk;"),
        require = 1, expect = 1)
    private LevelChunk phoenium$dynamicShapeInput(Level world, BlockPos pos, Operation<LevelChunk> original) {
        return FullFinishingQueries.dynamicChunk(world, pos, () -> original.call(world, pos));
    }

    @WrapOperation(method = "getBlockEntity(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/LevelChunk;getBlockEntity(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/chunk/LevelChunk$EntityCreationType;)Lnet/minecraft/world/level/block/entity/BlockEntity;"),
        require = 1, expect = 1)
    private BlockEntity phoenium$dynamicShapeMutation(LevelChunk chunk, BlockPos pos, LevelChunk.EntityCreationType creation,
                                                      Operation<BlockEntity> original) {
        return FullFinishingQueries.dynamicBlockEntity(chunk, pos, creation, () -> original.call(chunk, pos, creation));
    }
}
