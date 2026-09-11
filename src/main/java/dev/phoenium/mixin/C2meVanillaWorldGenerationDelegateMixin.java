package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.phoenium.Phoenium;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

/** Exact locked-C2ME bridge for the two pre-FULL ChunkStep.apply call sites. */
@Pseudo
@Mixin(targets = "com.ishland.c2me.rewrites.chunksystem.common.statuses.VanillaWorldGenerationDelegate", remap = false)
abstract class C2meVanillaWorldGenerationDelegateMixin {
    private static final String APPLY = "Lnet/minecraft/world/level/chunk/status/ChunkStep;apply("
            + "Lnet/minecraft/world/level/chunk/status/WorldGenContext;"
            + "Lnet/minecraft/util/StaticCache2D;"
            + "Lnet/minecraft/world/level/chunk/ChunkAccess;)Ljava/util/concurrent/CompletableFuture;";

    @WrapOperation(
        method = "lambda$upgradeToThis$1",
        at = @At(value = "INVOKE", target = APPLY, remap = true),
        require = 1, expect = 1)
    private CompletableFuture<ChunkAccess> phoenium$c2meLoadingStep(
            ChunkStep step, WorldGenContext generationContext, StaticCache2D<GenerationChunkHolder> cache,
            ChunkAccess chunk, Operation<CompletableFuture<ChunkAccess>> original,
            @Coerce Object context) {
        return Phoenium.liveRuntime().chunkBinding().c2meGeneration((Object) this, context, step, chunk,
                () -> original.call(step, generationContext, cache, chunk));
    }

    @WrapOperation(
        method = "lambda$upgradeToThis$5",
        at = @At(value = "INVOKE", target = APPLY, remap = true),
        require = 1, expect = 1)
    private CompletableFuture<ChunkAccess> phoenium$c2meGenerationStep(
            ChunkStep step, WorldGenContext generationContext, StaticCache2D<GenerationChunkHolder> cache,
            ChunkAccess chunk, Operation<CompletableFuture<ChunkAccess>> original,
            @Coerce Object context) {
        return Phoenium.liveRuntime().chunkBinding().c2meGeneration((Object) this, context, step, chunk,
                () -> original.call(step, generationContext, cache, chunk));
    }
}
