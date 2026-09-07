package dev.phoenium.acceptance;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import dev.phoenium.diagnostics.FullChunkLoadCounterDiagnostics;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLoadCounter;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;

/**
 * Read-only diagnostics for the disposable FULL acceptance harness.  This does not call
 * ChunkLoadCounter.pendingChunks(), because that vanilla accessor mutates its tracking list.
 */
@Mixin(ChunkLoadCounter.class)
abstract class FullRuntimeAcceptanceChunkLoadCounterMixin implements FullChunkLoadCounterDiagnostics {
    @Shadow @Final private List<ChunkHolder> pendingChunks;
    @Shadow private int totalChunks;

    @Override
    public String phoenium$diagnosticSummary() {
        int latestFull = 0;
        StringBuilder unmet = new StringBuilder();
        for (ChunkHolder holder : pendingChunks) {
            if (holder.getLatestStatus() == ChunkStatus.FULL) {
                latestFull++;
                continue;
            }
            if (unmet.length() > 0) unmet.append(';');
            unmet.append(holder.getPos()).append("{latest=").append(holder.getLatestStatus())
                    .append(",full=").append(holder.getFullStatus())
                    .append(",ticket=").append(holder.getTicketLevel()).append(",futures=");
            List<com.mojang.datafixers.util.Pair<ChunkStatus,
                    CompletableFuture<ChunkResult<ChunkAccess>>>> futures = holder.getAllFutures();
            for (int i = 0; i < futures.size(); i++) {
                if (i > 0) unmet.append(',');
                var future = futures.get(i);
                unmet.append(future.getFirst()).append('=').append(future.getSecond().isDone() ? "done" : "pending");
                if (future.getSecond().isCompletedExceptionally()) unmet.append("!");
            }
            unmet.append('}');
            if (unmet.length() > 5000) {
                unmet.append("...");
                break;
            }
        }
        return "total=" + totalChunks + " tracked=" + pendingChunks.size() + " latestFull=" + latestFull
                + " unmet=" + (pendingChunks.size() - latestFull) + " details=" + unmet;
    }
}
