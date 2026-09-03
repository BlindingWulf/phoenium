package dev.phoenium.mixin;

import dev.phoenium.live.ChunkRuntimeTrace;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.GeneratingChunkMap;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Test-only observation of holder generation and future publication. */
@Mixin(GenerationChunkHolder.class)
abstract class ChunkRuntimeTraceGenerationChunkHolderMixin {
    @Inject(method = "applyStep(Lnet/minecraft/world/level/chunk/status/ChunkStep;Lnet/minecraft/server/level/GeneratingChunkMap;Lnet/minecraft/util/StaticCache2D;)Ljava/util/concurrent/CompletableFuture;",
        at = @At("HEAD"), require = 1)
    private void phoenium$holderGenerationStep(ChunkStep step, GeneratingChunkMap chunkMap,
                                                StaticCache2D<GenerationChunkHolder> cache,
                                                CallbackInfoReturnable<CompletableFuture<ChunkResult<ChunkAccess>>> cir) {
        GenerationChunkHolder holder = (GenerationChunkHolder) (Object) this;
        ChunkRuntimeTrace.stageForHolder("GENERATION_HOLDER_APPLY_STEP_ENTER", holder,
            step.targetStatus().getName(), null);
    }

    @Inject(method = "applyStep(Lnet/minecraft/world/level/chunk/status/ChunkStep;Lnet/minecraft/server/level/GeneratingChunkMap;Lnet/minecraft/util/StaticCache2D;)Ljava/util/concurrent/CompletableFuture;",
        at = @At("RETURN"), require = 1)
    private void phoenium$holderGenerationStepReturn(ChunkStep step, GeneratingChunkMap chunkMap,
                                                     StaticCache2D<GenerationChunkHolder> cache,
                                                     CallbackInfoReturnable<CompletableFuture<ChunkResult<ChunkAccess>>> cir) {
        GenerationChunkHolder holder = (GenerationChunkHolder) (Object) this;
        ChunkRuntimeTrace.stageForHolder("GENERATION_HOLDER_APPLY_STEP_RETURN", holder,
            step.targetStatus().getName(), cir.getReturnValue());
    }

    @Inject(method = "completeFuture(Lnet/minecraft/world/level/chunk/status/ChunkStatus;Lnet/minecraft/world/level/chunk/ChunkAccess;)V",
        at = @At("HEAD"), require = 1)
    private void phoenium$holderPublication(ChunkStatus status, ChunkAccess chunk, CallbackInfo ci) {
        GenerationChunkHolder holder = (GenerationChunkHolder) (Object) this;
        ChunkRuntimeTrace.stageForHolder("GENERATION_HOLDER_COMPLETE_FUTURE_ENTER", holder,
            status.getName(), chunk);
    }

    @Inject(method = "completeFuture(Lnet/minecraft/world/level/chunk/status/ChunkStatus;Lnet/minecraft/world/level/chunk/ChunkAccess;)V",
        at = @At("RETURN"), require = 1)
    private void phoenium$holderPublicationReturn(ChunkStatus status, ChunkAccess chunk, CallbackInfo ci) {
        GenerationChunkHolder holder = (GenerationChunkHolder) (Object) this;
        ChunkRuntimeTrace.stageForHolder("GENERATION_HOLDER_COMPLETE_FUTURE_RETURN", holder,
            status.getName(), chunk);
    }

    @Inject(method = "replaceProtoChunk(Lnet/minecraft/world/level/chunk/ImposterProtoChunk;)V",
        at = @At("HEAD"), require = 1)
    private void phoenium$holderReplaceProto(ImposterProtoChunk chunk, CallbackInfo ci) {
        GenerationChunkHolder holder = (GenerationChunkHolder) (Object) this;
        ChunkRuntimeTrace.stageForHolder("GENERATION_HOLDER_REPLACE_PROTO_CHUNK_ENTER", holder,
            null, chunk);
    }

    @Inject(method = "replaceProtoChunk(Lnet/minecraft/world/level/chunk/ImposterProtoChunk;)V",
        at = @At("RETURN"), require = 1)
    private void phoenium$holderReplaceProtoReturn(ImposterProtoChunk chunk, CallbackInfo ci) {
        GenerationChunkHolder holder = (GenerationChunkHolder) (Object) this;
        ChunkRuntimeTrace.stageForHolder("GENERATION_HOLDER_REPLACE_PROTO_CHUNK_RETURN", holder,
            null, chunk);
    }
}
