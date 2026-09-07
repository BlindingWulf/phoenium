package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.phoenium.chunk.FullFinishingQueries;
import dev.phoenium.chunk.FullInitializedInputs;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ServerLevel.class)
abstract class FullFinishingDifficultyMixin {
    @WrapOperation(method = "getCurrentDifficultyAt(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/DifficultyInstance;",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;"),
        require = 1, expect = 1)
    private ChunkAccess phoenium$difficultyInput(ServerLevel world, int x, int z, ChunkStatus status, boolean create,
                                                Operation<ChunkAccess> original) {
        return FullFinishingQueries.query(world, FullInitializedInputs.Purpose.DIFFICULTY, x, z, status, create,
                () -> original.call(world, x, z, status, create));
    }
}
