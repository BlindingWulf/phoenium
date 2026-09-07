package dev.phoenium.acceptance;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ChunkGenerationTask;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.util.StaticCache2D;
import dev.phoenium.diagnostics.FullSpawnDiagnosticState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Read-only lifecycle diagnostics for the vanilla task that advances a spawn-search holder.
 * The target filter keeps the live log bounded to the candidate reported by the spawn finder.
 */
@Mixin(ChunkGenerationTask.class)
abstract class FullRuntimeAcceptanceGenerationTaskMixin {
    @Shadow @Final private ChunkPos pos;
    @Shadow @Final private ChunkStatus targetStatus;
    @Shadow private ChunkStatus scheduledStatus;
    @Shadow @Final private List<CompletableFuture<ChunkResult<ChunkAccess>>> scheduledLayer;
    @Shadow @Final private StaticCache2D<GenerationChunkHolder> cache;
    @Shadow private boolean needsGeneration;
    @Shadow private volatile boolean markedForCancellation;
    @Unique private String phoenium$lastState;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void phoenium$created(CallbackInfo callback) {
        if (isSpawnCandidate()) trace("create");
    }

    @Inject(method = "runUntilWait", at = @At("HEAD"))
    private void phoenium$runHead(CallbackInfoReturnable<CompletableFuture<?>> callback) {
        if (isSpawnCandidate()) trace("run-head");
    }

    @Inject(method = "runUntilWait", at = @At("RETURN"))
    private void phoenium$runReturn(CallbackInfoReturnable<CompletableFuture<?>> callback) {
        if (isSpawnCandidate()) trace("run-return future=" + futureState(callback.getReturnValue()));
    }

    @Inject(method = "scheduleNextLayer", at = @At("HEAD"))
    private void phoenium$layer(CallbackInfo callback) {
        if (isSpawnCandidate()) trace("schedule-layer");
    }

    @Inject(method = "markForCancellation", at = @At("HEAD"))
    private void phoenium$cancel(CallbackInfo callback) {
        if (isSpawnCandidate()) trace("cancel");
    }

    @Unique
    private boolean isSpawnCandidate() {
        return FullSpawnDiagnosticState.isTarget(pos);
    }

    @Unique
    private void trace(String event) {
        String current = "PHOENIUM_FULL_GENERATION_TASK event=" + event
                + " pos=" + pos + " target=" + targetStatus
                + " scheduled=" + scheduledStatus + " needsGeneration=" + needsGeneration
                + " cancelled=" + markedForCancellation
                + " layers=" + layerStates()
                + " center=" + holderState();
        if (current.equals(phoenium$lastState)) return;
        phoenium$lastState = current;
        System.out.println(current);
    }

    @Unique
    private String layerStates() {
        if (scheduledLayer == null || scheduledLayer.isEmpty()) return "empty";
        StringBuilder result = new StringBuilder();
        for (CompletableFuture<?> future : scheduledLayer) {
            if (result.length() > 0) result.append(',');
            result.append(futureState(future));
        }
        return result.toString();
    }

    @Unique
    private String holderState() {
        if (cache == null || pos == null) return "none";
        GenerationChunkHolder holder = cache.get(pos.x(), pos.z());
        if (holder == null) return "none";
        StringBuilder result = new StringBuilder("latest=").append(holder.getLatestStatus())
                .append(",full=").append(holder.getFullStatus())
                .append(",ticket=").append(holder.getTicketLevel()).append(",futures=");
        var futures = holder.getAllFutures();
        for (int i = 0; i < futures.size(); i++) {
            if (i > 0) result.append(',');
            var future = futures.get(i);
            result.append(future.getFirst()).append('=').append(futureState(future.getSecond()));
        }
        return result.toString();
    }

    @Unique
    private static String futureState(CompletableFuture<?> future) {
        if (future == null) return "null";
        if (!future.isDone()) return "pending";
        return future.isCompletedExceptionally() ? "exceptional" : "done";
    }
}
