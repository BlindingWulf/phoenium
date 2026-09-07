package dev.phoenium.acceptance;

import java.util.concurrent.CompletableFuture;
import dev.phoenium.diagnostics.FullSpawnDiagnosticState;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Logs only the vanilla spawn-search ticket/future used by PrepareSpawnTask. */
@Mixin(ServerChunkCache.class)
abstract class FullRuntimeAcceptanceSpawnSearchMixin {
    @Shadow public ChunkMap chunkMap;
    @Unique private int phoenium$spawnSearches;

    @Inject(method = "addTicketAndLoadWithRadius", at = @At("HEAD"))
    private void phoenium$spawnSearchHead(TicketType type, ChunkPos pos, int radius,
                                           CallbackInfoReturnable<CompletableFuture<?>> callback) {
        if (type == TicketType.SPAWN_SEARCH) FullSpawnDiagnosticState.record(pos, null);
        int sequence = ++phoenium$spawnSearches;
        if (sequence > 24 && sequence % 100 != 0) return;
        System.out.println("PHOENIUM_FULL_SPAWN_SEARCH_HEAD sequence=" + sequence + " type=" + type
                + " spawnSearchIdentity=" + (type == TicketType.SPAWN_SEARCH)
                + " pos=" + pos + " radius=" + radius);
    }

    @Inject(method = "addTicketAndLoadWithRadius", at = @At("RETURN"))
    private void phoenium$spawnSearch(TicketType type, ChunkPos pos, int radius,
                                      CallbackInfoReturnable<CompletableFuture<?>> callback) {
        if (type != TicketType.SPAWN_SEARCH) return;
        int sequence = phoenium$spawnSearches;
        if (sequence > 24 && sequence % 100 != 0) return;
        ChunkHolder holder = chunkMap.getUpdatingChunkIfPresent(pos.pack());
        String holderState = holder == null ? "none" : "latest=" + holder.getLatestStatus()
                + ",full=" + holder.getFullStatus() + ",ticket=" + holder.getTicketLevel()
                + ",futures=" + futureStates(holder);
        CompletableFuture<?> future = callback.getReturnValue();
        FullSpawnDiagnosticState.record(pos, holder);
        System.out.println("PHOENIUM_FULL_SPAWN_SEARCH sequence=" + sequence + " pos=" + pos
                + " radius=" + radius + " future=" + futureState(future) + " holder=" + holderState);
    }

    @Unique
    private static String futureStates(ChunkHolder holder) {
        StringBuilder states = new StringBuilder();
        for (var entry : holder.getAllFutures()) {
            if (states.length() > 0) states.append(',');
            states.append(entry.getFirst()).append('=').append(futureState(entry.getSecond()));
        }
        return states.toString();
    }

    @Unique
    private static String futureState(CompletableFuture<?> future) {
        if (future == null) return "null";
        if (!future.isDone()) return "pending";
        return future.isCompletedExceptionally() ? "exceptional" : "done";
    }
}
