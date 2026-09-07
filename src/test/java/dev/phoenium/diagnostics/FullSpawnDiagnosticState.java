package dev.phoenium.diagnostics;

import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Server-local state shared by the acceptance-only spawn diagnostics. */
public final class FullSpawnDiagnosticState {
    private static volatile ChunkPos targetPosition;
    private static volatile ChunkHolder targetHolder;

    private FullSpawnDiagnosticState() { }

    public static void record(ChunkPos position, ChunkHolder holder) {
        targetPosition = position;
        targetHolder = holder;
    }

    public static boolean isTarget(ChunkPos position) {
        return position != null && position.equals(targetPosition);
    }

    public static String summary() {
        ChunkPos position = targetPosition;
        ChunkHolder holder = targetHolder;
        if (position == null || holder == null) return "target=" + position + " holder=none";
        StringBuilder result = new StringBuilder("target=").append(position)
                .append(" holderLatest=").append(holder.getLatestStatus())
                .append(",holderFull=").append(holder.getFullStatus())
                .append(",holderTicket=").append(holder.getTicketLevel()).append(",holderFutures=");
        var futures = holder.getAllFutures();
        for (int i = 0; i < futures.size(); i++) {
            if (i > 0) result.append(',');
            var future = futures.get(i);
            result.append(future.getFirst()).append('=').append(futureState(future.getSecond()));
        }
        return result.toString();
    }

    private static String futureState(CompletableFuture<?> future) {
        if (future == null) return "null";
        if (!future.isDone()) return "pending";
        return future.isCompletedExceptionally() ? "exceptional" : "done";
    }
}
