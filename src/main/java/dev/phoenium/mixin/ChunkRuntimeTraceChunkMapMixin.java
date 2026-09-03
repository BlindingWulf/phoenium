package dev.phoenium.mixin;

import dev.phoenium.live.ChunkRuntimeTrace;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ChunkGenerationTask;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.StaticCache2D;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Test-only observation points for the exact Minecraft 26.2 ChunkMap pipeline. */
@Mixin(ChunkMap.class)
abstract class ChunkRuntimeTraceChunkMapMixin {
    @Shadow @Final private ServerLevel level;

    @Inject(method = "scheduleChunkLoad(Lnet/minecraft/world/level/ChunkPos;)Ljava/util/concurrent/CompletableFuture;",
        at = @At("HEAD"), require = 1)
    private void phoenium$chunkLoadScheduled(ChunkPos pos, CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        ChunkRuntimeTrace.stage("CHUNK_MAP_SCHEDULE_CHUNK_LOAD_ENTER", level, pos, null, null, null);
    }

    @Inject(method = "scheduleChunkLoad(Lnet/minecraft/world/level/ChunkPos;)Ljava/util/concurrent/CompletableFuture;",
        at = @At("RETURN"), require = 1)
    private void phoenium$chunkLoadScheduledReturn(ChunkPos pos, CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        ChunkRuntimeTrace.stage("CHUNK_MAP_SCHEDULE_CHUNK_LOAD_RETURN", level, pos, null, cir.getReturnValue(), null);
    }

    @Inject(method = "readChunk(Lnet/minecraft/world/level/ChunkPos;)Ljava/util/concurrent/CompletableFuture;",
        at = @At("HEAD"), require = 1)
    private void phoenium$chunkReadStarted(ChunkPos pos,
                                           CallbackInfoReturnable<CompletableFuture<Optional<CompoundTag>>> cir) {
        ChunkRuntimeTrace.stage("CHUNK_MAP_READ_CHUNK_ENTER", level, pos, null, null, null);
    }

    @Inject(method = "readChunk(Lnet/minecraft/world/level/ChunkPos;)Ljava/util/concurrent/CompletableFuture;",
        at = @At("RETURN"), require = 1)
    private void phoenium$chunkReadReturned(ChunkPos pos,
                                             CallbackInfoReturnable<CompletableFuture<Optional<CompoundTag>>> cir) {
        ChunkRuntimeTrace.stage("CHUNK_MAP_READ_CHUNK_RETURN", level, pos, null, cir.getReturnValue(), null);
    }

    @Inject(method = "lambda$scheduleChunkLoad$1(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/world/level/chunk/storage/SerializableChunkData;",
        at = @At("HEAD"), require = 1)
    private void phoenium$chunkParseStarted(ChunkPos pos, CompoundTag tag,
                                             CallbackInfoReturnable<SerializableChunkData> cir) {
        ChunkRuntimeTrace.stage("SERIALIZABLE_DATA_PARSE_ENTER", level, pos, null, tag, null);
    }

    @Inject(method = "lambda$scheduleChunkLoad$1(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/world/level/chunk/storage/SerializableChunkData;",
        at = @At("RETURN"), require = 1)
    private void phoenium$chunkParseReturned(ChunkPos pos, CompoundTag tag,
                                              CallbackInfoReturnable<SerializableChunkData> cir) {
        ChunkRuntimeTrace.stage("SERIALIZABLE_DATA_PARSE_RETURN", level, pos, null, cir.getReturnValue(), null);
    }

    @Inject(method = "lambda$scheduleChunkLoad$3(Lnet/minecraft/world/level/ChunkPos;Ljava/util/Optional;)Lnet/minecraft/world/level/chunk/ChunkAccess;",
        at = @At("HEAD"), require = 1)
    private void phoenium$chunkApplicationStarted(ChunkPos pos, Optional<?> parsed,
                                                  CallbackInfoReturnable<ChunkAccess> cir) {
        ChunkRuntimeTrace.stage("CHUNK_MAP_APPLICATION_ENTER", level, pos,
            "parsedDataPresent=" + parsed.isPresent(), parsed, null);
    }

    @Inject(method = "lambda$scheduleChunkLoad$3(Lnet/minecraft/world/level/ChunkPos;Ljava/util/Optional;)Lnet/minecraft/world/level/chunk/ChunkAccess;",
        at = @At("RETURN"), require = 1)
    private void phoenium$chunkApplicationReturned(ChunkPos pos, Optional<?> parsed,
                                                    CallbackInfoReturnable<ChunkAccess> cir) {
        ChunkRuntimeTrace.stage("CHUNK_MAP_APPLICATION_RETURN", level, pos,
            "parsedDataPresent=" + parsed.isPresent(), cir.getReturnValue(), null);
    }

    @Inject(method = "lambda$scheduleChunkLoad$4(Lnet/minecraft/world/level/ChunkPos;Ljava/lang/Throwable;)Lnet/minecraft/world/level/chunk/ChunkAccess;",
        at = @At("HEAD"), require = 1)
    private void phoenium$chunkLoadFailureContinuation(ChunkPos pos, Throwable failure,
                                                        CallbackInfoReturnable<ChunkAccess> cir) {
        ChunkRuntimeTrace.stage("CHUNK_MAP_LOAD_FAILURE_CONTINUATION", level, pos,
            failure == null ? "none" : failure.getClass().getName(), null, null);
    }

    @Inject(method = "handleChunkLoadFailure(Ljava/lang/Throwable;Lnet/minecraft/world/level/ChunkPos;)Lnet/minecraft/world/level/chunk/ChunkAccess;",
        at = @At("HEAD"), require = 1)
    private void phoenium$chunkLoadFailureHandler(Throwable failure, ChunkPos pos,
                                                   CallbackInfoReturnable<ChunkAccess> cir) {
        ChunkRuntimeTrace.stage("CHUNK_MAP_HANDLE_LOAD_FAILURE", level, pos,
            failure == null ? "none" : failure.getClass().getName(), null, null);
    }

    @Inject(method = "createEmptyChunk(Lnet/minecraft/world/level/ChunkPos;)Lnet/minecraft/world/level/chunk/ChunkAccess;",
        at = @At("HEAD"), require = 1)
    private void phoenium$emptyChunkStarted(ChunkPos pos, CallbackInfoReturnable<ChunkAccess> cir) {
        ChunkRuntimeTrace.stage("CHUNK_MAP_CREATE_EMPTY_CHUNK_ENTER", level, pos, null, null, null);
    }

    @Inject(method = "createEmptyChunk(Lnet/minecraft/world/level/ChunkPos;)Lnet/minecraft/world/level/chunk/ChunkAccess;",
        at = @At("RETURN"), require = 1)
    private void phoenium$emptyChunkReturned(ChunkPos pos, CallbackInfoReturnable<ChunkAccess> cir) {
        ChunkRuntimeTrace.stage("CHUNK_MAP_CREATE_EMPTY_CHUNK_RETURN", level, pos, null, cir.getReturnValue(), null);
    }

    @Inject(method = "markPositionReplaceable(Lnet/minecraft/world/level/ChunkPos;)V", at = @At("HEAD"), require = 1)
    private void phoenium$positionReplaceable(ChunkPos pos, CallbackInfo ci) {
        ChunkRuntimeTrace.stage("CHUNK_MAP_MARK_POSITION_REPLACEABLE", level, pos, null, null, null);
    }

    @Inject(method = "markPosition(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/level/chunk/status/ChunkType;)B",
        at = @At("HEAD"), require = 1)
    private void phoenium$positionMarked(ChunkPos pos, net.minecraft.world.level.chunk.status.ChunkType type,
                                         CallbackInfoReturnable<Byte> cir) {
        ChunkRuntimeTrace.stage("CHUNK_MAP_MARK_POSITION_ENTER", level, pos,
            type == null ? "null" : type.name(), null, null);
    }

    @Inject(method = "markPosition(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/level/chunk/status/ChunkType;)B",
        at = @At("RETURN"), require = 1)
    private void phoenium$positionMarkedReturn(ChunkPos pos, net.minecraft.world.level.chunk.status.ChunkType type,
                                                CallbackInfoReturnable<Byte> cir) {
        ChunkRuntimeTrace.stage("CHUNK_MAP_MARK_POSITION_RETURN", level, pos,
            type == null ? "null" : type.name(), cir.getReturnValue(), null);
    }

    @Inject(method = "applyStep(Lnet/minecraft/server/level/GenerationChunkHolder;Lnet/minecraft/world/level/chunk/status/ChunkStep;Lnet/minecraft/util/StaticCache2D;)Ljava/util/concurrent/CompletableFuture;",
        at = @At("HEAD"), require = 1)
    private void phoenium$generationStepStarted(GenerationChunkHolder holder, ChunkStep step,
                                                StaticCache2D<GenerationChunkHolder> cache,
                                                CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        ChunkRuntimeTrace.stage("CHUNK_MAP_GENERATION_STEP_ENTER", level, holder.getPos(),
            step.targetStatus().getName(), null, holder);
    }

    @Inject(method = "applyStep(Lnet/minecraft/server/level/GenerationChunkHolder;Lnet/minecraft/world/level/chunk/status/ChunkStep;Lnet/minecraft/util/StaticCache2D;)Ljava/util/concurrent/CompletableFuture;",
        at = @At("RETURN"), require = 1)
    private void phoenium$generationStepReturned(GenerationChunkHolder holder, ChunkStep step,
                                                 StaticCache2D<GenerationChunkHolder> cache,
                                                 CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        ChunkRuntimeTrace.stage("CHUNK_MAP_GENERATION_STEP_RETURN", level, holder.getPos(),
            step.targetStatus().getName(), cir.getReturnValue(), holder);
    }

    @Inject(method = "runGenerationTask(Lnet/minecraft/server/level/ChunkGenerationTask;)V", at = @At("HEAD"), require = 1)
    private void phoenium$generationTaskStarted(ChunkGenerationTask task, CallbackInfo ci) {
        GenerationChunkHolder holder = task.getCenter();
        ChunkRuntimeTrace.stage("CHUNK_MAP_RUN_GENERATION_TASK_ENTER", level, holder.getPos(),
            task.targetStatus.getName(), null, holder);
    }

    @Inject(method = "lambda$runGenerationTask$0(Lnet/minecraft/server/level/ChunkGenerationTask;)V",
        at = @At("HEAD"), require = 1)
    private void phoenium$generationWorkerStarted(ChunkGenerationTask task, CallbackInfo ci) {
        GenerationChunkHolder holder = task.getCenter();
        ChunkRuntimeTrace.stage("CHUNK_MAP_GENERATION_WORKER_ENTER", level, holder.getPos(),
            task.targetStatus.getName(), null, holder);
    }

    @Inject(method = "prepareTickingChunk(Lnet/minecraft/server/level/ChunkHolder;)Ljava/util/concurrent/CompletableFuture;",
        at = @At("HEAD"), require = 1)
    private void phoenium$tickingPreparationStarted(ChunkHolder holder,
                                                    CallbackInfoReturnable<CompletableFuture<ChunkResult<LevelChunk>>> cir) {
        ChunkRuntimeTrace.stage("CHUNK_MAP_PREPARE_TICKING_CHUNK_ENTER", level, holder.getPos(), null, null, holder);
    }

    @Inject(method = "lambda$prepareTickingChunk$2(Lnet/minecraft/server/level/ChunkHolder;Ljava/util/List;)Lnet/minecraft/world/level/chunk/LevelChunk;",
        at = @At("HEAD"), require = 1)
    private void phoenium$tickingConversionStarted(ChunkHolder holder, List<?> chunks,
                                                   CallbackInfoReturnable<LevelChunk> cir) {
        ChunkRuntimeTrace.stage("CHUNK_MAP_TICKING_CONVERSION_ENTER", level, holder.getPos(),
            "listSize=" + chunks.size(), chunks, holder);
    }

    @Inject(method = "lambda$prepareTickingChunk$2(Lnet/minecraft/server/level/ChunkHolder;Ljava/util/List;)Lnet/minecraft/world/level/chunk/LevelChunk;",
        at = @At("RETURN"), require = 1)
    private void phoenium$tickingConversionReturned(ChunkHolder holder, List<?> chunks,
                                                    CallbackInfoReturnable<LevelChunk> cir) {
        ChunkRuntimeTrace.stage("CHUNK_MAP_TICKING_CONVERSION_RETURN", level, holder.getPos(),
            "listSize=" + chunks.size(), cir.getReturnValue(), holder);
    }

    @Inject(method = "lambda$prepareTickingChunk$3(Lnet/minecraft/server/level/ChunkHolder;Lnet/minecraft/world/level/chunk/LevelChunk;Ljava/lang/Object;)V",
        at = @At("HEAD"), require = 1)
    private void phoenium$readyToSendCallback(ChunkHolder holder, LevelChunk chunk, Object ignored, CallbackInfo ci) {
        ChunkRuntimeTrace.stage("CHUNK_MAP_READY_TO_SEND_CALLBACK", level, chunk.getPos(), null, chunk, holder);
    }

    @Inject(method = "onChunkReadyToSend(Lnet/minecraft/server/level/ChunkHolder;Lnet/minecraft/world/level/chunk/LevelChunk;)V",
        at = @At("HEAD"), require = 1)
    private void phoenium$chunkReadyToSend(ChunkHolder holder, LevelChunk chunk, CallbackInfo ci) {
        ChunkRuntimeTrace.stage("CHUNK_MAP_ON_CHUNK_READY_TO_SEND", level, chunk.getPos(), null, chunk, holder);
    }

    @Inject(method = "prepareAccessibleChunk(Lnet/minecraft/server/level/ChunkHolder;)Ljava/util/concurrent/CompletableFuture;",
        at = @At("HEAD"), require = 1)
    private void phoenium$accessiblePreparationStarted(ChunkHolder holder,
                                                       CallbackInfoReturnable<CompletableFuture<ChunkResult<LevelChunk>>> cir) {
        ChunkRuntimeTrace.stage("CHUNK_MAP_PREPARE_ACCESSIBLE_CHUNK_ENTER", level, holder.getPos(), null, null, holder);
    }
}
