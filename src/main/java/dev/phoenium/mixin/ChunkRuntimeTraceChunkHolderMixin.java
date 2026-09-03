package dev.phoenium.mixin;

import dev.phoenium.live.ChunkRuntimeTrace;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Test-only observation of exact 26.2 full/ticking holder promotion and demotion callbacks. */
@Mixin(ChunkHolder.class)
abstract class ChunkRuntimeTraceChunkHolderMixin {
    @Shadow private int oldTicketLevel;
    @Shadow private int ticketLevel;

    @Inject(method = "addSendDependency(Ljava/util/concurrent/CompletableFuture;)V",
        at = @At("HEAD"), require = 1)
    private void phoenium$sendDependencyEnter(CompletableFuture<?> dependency, CallbackInfo ci) {
        ChunkHolder holder = (ChunkHolder) (Object) this;
        ChunkRuntimeTrace.stageForHolder("CHUNK_HOLDER_ADD_SEND_DEPENDENCY_ENTER", holder,
            details(holder) + " dependencyDone=" + done(dependency), dependency);
    }

    @Inject(method = "addSendDependency(Ljava/util/concurrent/CompletableFuture;)V",
        at = @At("RETURN"), require = 1)
    private void phoenium$sendDependencyReturn(CompletableFuture<?> dependency, CallbackInfo ci) {
        ChunkHolder holder = (ChunkHolder) (Object) this;
        ChunkRuntimeTrace.stageForHolder("CHUNK_HOLDER_ADD_SEND_DEPENDENCY_RETURN", holder,
            details(holder) + " dependencyDone=" + done(dependency), dependency);
    }

    @Inject(method = "scheduleFullChunkPromotion(Lnet/minecraft/server/level/ChunkMap;Ljava/util/concurrent/CompletableFuture;Ljava/util/concurrent/Executor;Lnet/minecraft/server/level/FullChunkStatus;)V",
        at = @At("HEAD"), require = 1)
    private void phoenium$fullPromotionEnter(ChunkMap chunkMap,
                                               CompletableFuture<ChunkResult<LevelChunk>> future,
                                               Executor executor, FullChunkStatus status, CallbackInfo ci) {
        ChunkHolder holder = (ChunkHolder) (Object) this;
        ChunkRuntimeTrace.stageForHolder("CHUNK_HOLDER_FULL_PROMOTION_ENTER", holder,
            details(holder) + " status=" + status.name() + " inputFutureDone=" + done(future), future);
    }

    @Inject(method = "scheduleFullChunkPromotion(Lnet/minecraft/server/level/ChunkMap;Ljava/util/concurrent/CompletableFuture;Ljava/util/concurrent/Executor;Lnet/minecraft/server/level/FullChunkStatus;)V",
        at = @At("RETURN"), require = 1)
    private void phoenium$fullPromotionReturn(ChunkMap chunkMap,
                                                CompletableFuture<ChunkResult<LevelChunk>> future,
                                                Executor executor, FullChunkStatus status, CallbackInfo ci) {
        ChunkHolder holder = (ChunkHolder) (Object) this;
        ChunkRuntimeTrace.stageForHolder("CHUNK_HOLDER_FULL_PROMOTION_RETURN", holder,
            details(holder) + " status=" + status.name() + " inputFutureDone=" + done(future),
            holder.getFullChunkFuture());
    }

    @Inject(method = "demoteFullChunk(Lnet/minecraft/server/level/ChunkMap;Lnet/minecraft/server/level/FullChunkStatus;)V",
        at = @At("HEAD"), require = 1)
    private void phoenium$fullDemotionEnter(ChunkMap chunkMap, FullChunkStatus status, CallbackInfo ci) {
        ChunkHolder holder = (ChunkHolder) (Object) this;
        ChunkRuntimeTrace.stageForHolder("CHUNK_HOLDER_DEMOTE_FULL_CHUNK_ENTER", holder,
            details(holder) + " status=" + status.name(), holder.getFullChunkFuture());
    }

    @Inject(method = "demoteFullChunk(Lnet/minecraft/server/level/ChunkMap;Lnet/minecraft/server/level/FullChunkStatus;)V",
        at = @At("RETURN"), require = 1)
    private void phoenium$fullDemotionReturn(ChunkMap chunkMap, FullChunkStatus status, CallbackInfo ci) {
        ChunkHolder holder = (ChunkHolder) (Object) this;
        ChunkRuntimeTrace.stageForHolder("CHUNK_HOLDER_DEMOTE_FULL_CHUNK_RETURN", holder,
            details(holder) + " status=" + status.name(), holder.getFullChunkFuture());
    }

    @Inject(method = "updateFutures(Lnet/minecraft/server/level/ChunkMap;Ljava/util/concurrent/Executor;)V",
        at = @At("HEAD"), require = 1)
    private void phoenium$updateFuturesEnter(ChunkMap chunkMap, Executor executor, CallbackInfo ci) {
        ChunkHolder holder = (ChunkHolder) (Object) this;
        ChunkRuntimeTrace.stageForHolder("CHUNK_HOLDER_UPDATE_FUTURES_ENTER", holder, details(holder), holder);
    }

    @Inject(method = "updateFutures(Lnet/minecraft/server/level/ChunkMap;Ljava/util/concurrent/Executor;)V",
        at = @At("RETURN"), require = 1)
    private void phoenium$updateFuturesReturn(ChunkMap chunkMap, Executor executor, CallbackInfo ci) {
        ChunkHolder holder = (ChunkHolder) (Object) this;
        ChunkRuntimeTrace.stageForHolder("CHUNK_HOLDER_UPDATE_FUTURES_RETURN", holder, details(holder), holder);
    }

    private String details(ChunkHolder holder) {
        return "oldTicketLevel=" + oldTicketLevel
            + " ticketLevel=" + ticketLevel
            + " fullFutureDone=" + done(holder.getFullChunkFuture())
            + " tickingFutureDone=" + done(holder.getTickingChunkFuture())
            + " entityTickingFutureDone=" + done(holder.getEntityTickingChunkFuture())
            + " sendSyncDone=" + done(holder.getSendSyncFuture());
    }

    private static boolean done(CompletableFuture<?> future) {
        return future != null && future.isDone();
    }
}
