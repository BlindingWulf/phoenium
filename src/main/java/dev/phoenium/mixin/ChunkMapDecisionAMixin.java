package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.phoenium.Phoenium;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStep;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Replaces only the exact EMPTY scheduleChunkLoad registration before vanilla builds D/R. */
@Mixin(ChunkMap.class)
abstract class ChunkMapDecisionAMixin {
    @WrapOperation(
        method = "applyStep(Lnet/minecraft/server/level/GenerationChunkHolder;Lnet/minecraft/world/level/chunk/status/ChunkStep;Lnet/minecraft/util/StaticCache2D;)Ljava/util/concurrent/CompletableFuture;",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ChunkMap;scheduleChunkLoad(Lnet/minecraft/world/level/ChunkPos;)Ljava/util/concurrent/CompletableFuture;"),
        require = 1, expect = 1)
    private CompletableFuture<ChunkAccess> phoenium$bindEmpty(ChunkMap map, ChunkPos position,
            Operation<CompletableFuture<ChunkAccess>> original,
            @Local(argsOnly = true) GenerationChunkHolder holder,
            @Local(argsOnly = true) ChunkStep step) {
        return Phoenium.liveRuntime().chunkBinding().register(map, holder, step, position);
    }
}
