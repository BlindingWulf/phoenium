package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.phoenium.Phoenium;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.GeneratingChunkMap;
import net.minecraft.util.StaticCache2D;
import dev.phoenium.chunk.FullRegistrationRuntime;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStep;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Binds the exact adjacent R.handle edge; non-EMPTY futures retain the original handler. */
@Mixin(GenerationChunkHolder.class)
abstract class GenerationHolderCompletionMixin {
    @WrapOperation(
        method = "applyStep(Lnet/minecraft/world/level/chunk/status/ChunkStep;Lnet/minecraft/server/level/GeneratingChunkMap;Lnet/minecraft/util/StaticCache2D;)Ljava/util/concurrent/CompletableFuture;",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/GeneratingChunkMap;applyStep(Lnet/minecraft/server/level/GenerationChunkHolder;Lnet/minecraft/world/level/chunk/status/ChunkStep;Lnet/minecraft/util/StaticCache2D;)Ljava/util/concurrent/CompletableFuture;"),
        require = 1, expect = 1)
    private CompletableFuture<ChunkAccess> phoenium$winningGeneration(GeneratingChunkMap map,
            GenerationChunkHolder holder, ChunkStep step, StaticCache2D<GenerationChunkHolder> cache,
            Operation<CompletableFuture<ChunkAccess>> original) {
        return FullRegistrationRuntime.applyWinning(map, holder, step, cache,
                () -> original.call(map, holder, step, cache));
    }
    @WrapOperation(
        method = "applyStep(Lnet/minecraft/world/level/chunk/status/ChunkStep;Lnet/minecraft/server/level/GeneratingChunkMap;Lnet/minecraft/util/StaticCache2D;)Ljava/util/concurrent/CompletableFuture;",
        at = @At(value = "INVOKE", target = "Ljava/util/concurrent/CompletableFuture;handle(Ljava/util/function/BiFunction;)Ljava/util/concurrent/CompletableFuture;"),
        require = 1, expect = 1)
    private CompletableFuture<ChunkResult<ChunkAccess>> phoenium$bindHolder(
            CompletableFuture<ChunkAccess> returned, BiFunction<ChunkAccess, Throwable, ChunkResult<ChunkAccess>> handler,
            Operation<CompletableFuture<ChunkResult<ChunkAccess>>> original,
            @Local(argsOnly = true) ChunkStep step,
            @Local(argsOnly = true) GeneratingChunkMap map) {
        var full = FullRegistrationRuntime.attach(map, returned, (GenerationChunkHolder) (Object) this, step, handler);
        if (full != null) return full;
        CompletableFuture<ChunkResult<ChunkAccess>> bound = Phoenium.liveRuntime().chunkBinding()
                .attachIfBound(returned, (GenerationChunkHolder) (Object) this, step);
        return bound == null ? original.call(returned, handler) : bound;
    }
}
