package dev.phoenium.mixin;

import dev.phoenium.live.ChunkRuntimeTrace;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Test-only observation of the FULL conversion and post-load callbacks. */
@Mixin(ChunkStatusTasks.class)
abstract class ChunkRuntimeTraceChunkStatusTasksMixin {
    @Inject(method = "full(Lnet/minecraft/world/level/chunk/status/WorldGenContext;Lnet/minecraft/world/level/chunk/status/ChunkStep;Lnet/minecraft/util/StaticCache2D;Lnet/minecraft/world/level/chunk/ChunkAccess;)Ljava/util/concurrent/CompletableFuture;",
        at = @At("HEAD"), require = 1)
    private static void phoenium$fullStarted(WorldGenContext context, ChunkStep step,
                                              StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
                                              CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        ChunkRuntimeTrace.stageByTarget("CHUNK_STATUS_FULL_ENTER", chunk.getPos(),
            step.targetStatus().getName(), chunk, null);
    }

    @Inject(method = "full(Lnet/minecraft/world/level/chunk/status/WorldGenContext;Lnet/minecraft/world/level/chunk/status/ChunkStep;Lnet/minecraft/util/StaticCache2D;Lnet/minecraft/world/level/chunk/ChunkAccess;)Ljava/util/concurrent/CompletableFuture;",
        at = @At("RETURN"), require = 1)
    private static void phoenium$fullReturned(WorldGenContext context, ChunkStep step,
                                               StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
                                               CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        ChunkRuntimeTrace.stageByTarget("CHUNK_STATUS_FULL_RETURN", chunk.getPos(),
            step.targetStatus().getName(), cir.getReturnValue(), null);
    }

    @Inject(method = "lambda$full$0(Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/chunk/status/WorldGenContext;Lnet/minecraft/server/level/GenerationChunkHolder;)Lnet/minecraft/world/level/chunk/ChunkAccess;",
        at = @At("HEAD"), require = 1)
    private static void phoenium$fullConversionStarted(ChunkAccess chunk, WorldGenContext context,
                                                       GenerationChunkHolder holder,
                                                       CallbackInfoReturnable<ChunkAccess> cir) {
        ChunkRuntimeTrace.stageForHolder("CHUNK_STATUS_FULL_CONVERSION_ENTER", holder, null, chunk);
    }

    @Inject(method = "lambda$full$0(Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/world/level/chunk/status/WorldGenContext;Lnet/minecraft/server/level/GenerationChunkHolder;)Lnet/minecraft/world/level/chunk/ChunkAccess;",
        at = @At("RETURN"), require = 1)
    private static void phoenium$fullConversionReturned(ChunkAccess chunk, WorldGenContext context,
                                                        GenerationChunkHolder holder,
                                                        CallbackInfoReturnable<ChunkAccess> cir) {
        ChunkRuntimeTrace.stageForHolder("CHUNK_STATUS_FULL_CONVERSION_RETURN", holder, null,
            cir.getReturnValue());
    }

    @Inject(method = "lambda$full$1(Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ProtoChunk;Lnet/minecraft/world/level/chunk/LevelChunk;)V",
        at = @At("HEAD"), require = 1)
    private static void phoenium$fullPostLoadStarted(ChunkAccess original, ServerLevel level,
                                                     ProtoChunk proto, LevelChunk full, CallbackInfo ci) {
        ChunkRuntimeTrace.stage("CHUNK_STATUS_FULL_POST_LOAD_ENTER", level, full.getPos(),
            null, full, null);
    }

    @Inject(method = "lambda$full$1(Lnet/minecraft/world/level/chunk/ChunkAccess;Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ProtoChunk;Lnet/minecraft/world/level/chunk/LevelChunk;)V",
        at = @At("RETURN"), require = 1)
    private static void phoenium$fullPostLoadReturned(ChunkAccess original, ServerLevel level,
                                                      ProtoChunk proto, LevelChunk full, CallbackInfo ci) {
        ChunkRuntimeTrace.stage("CHUNK_STATUS_FULL_POST_LOAD_RETURN", level, full.getPos(),
            null, full, null);
    }
}
