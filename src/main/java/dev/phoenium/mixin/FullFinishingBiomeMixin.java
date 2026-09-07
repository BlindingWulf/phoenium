package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.phoenium.chunk.FullFinishingQueries;
import dev.phoenium.chunk.FullInitializedInputs;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Positional environment sampling keeps the original BIOMES,false and uncached fallback. */
@Mixin(LevelReader.class)
interface FullFinishingBiomeMixin {
    @WrapOperation(method = "getNoiseBiome(III)Lnet/minecraft/core/Holder;",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/LevelReader;getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;"),
        require = 1, expect = 1)
    private ChunkAccess phoenium$environmentInput(LevelReader world, int x, int z, ChunkStatus status, boolean create,
                                                 Operation<ChunkAccess> original) {
        return FullFinishingQueries.query(world, FullInitializedInputs.Purpose.ENVIRONMENT, x, z, status, create,
                () -> original.call(world, x, z, status, create));
    }
}
