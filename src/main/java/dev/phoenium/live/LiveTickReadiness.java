package dev.phoenium.live;

import dev.phoenium.ownership.OwnershipContext;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import net.minecraft.server.level.*;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Ordinary public ticking prerequisites, observed after the original distance-update stage. */
public final class LiveTickReadiness {
    private LiveTickReadiness() { }
    public interface MapAccess { Stream<ChunkHolder> phoenium$tickCandidates(ChunkStatus status); }
    private record Input(ChunkHolder holder, CompletableFuture<ChunkResult<LevelChunk>> future) { }
    public static final class Receipt {
        private final ServerLevel level;
        private final List<Input> inputs;
        private Receipt(ServerLevel level, List<Input> inputs) { this.level = level; this.inputs = inputs; }
        public boolean current() { return inputs.equals(candidates(level)) && inputs.stream().allMatch(input -> input.future.isDone()); }
        public int count() { return inputs.size(); }
    }
    public static Receipt await(ServerLevel level) {
        if (OwnershipContext.current().isPresent() || !level.getServer().isSameThread())
            throw new IllegalStateException("ticking readiness must run on the owner-free server thread");
        for (;;) {
            List<Input> inputs = candidates(level);
            // Existing original ticking futures already include their radius-one FULL range.
            // No future, ticket, public result, callback or absence is synthesized here.
            level.getServer().managedBlock(() -> inputs.stream().allMatch(input -> input.future.isDone())
                    || !inputs.equals(candidates(level)));
            Receipt receipt = new Receipt(level, inputs);
            if (receipt.current()) return receipt;
        }
    }
    private static List<Input> candidates(ServerLevel level) {
        List<Input> inputs = new ArrayList<>();
        try (var holders = ((MapAccess) level.getChunkSource().chunkMap).phoenium$tickCandidates(ChunkStatus.FULL)) {
            holders.filter(holder -> ChunkLevel.fullStatus(holder.getTicketLevel()).isOrAfter(FullChunkStatus.BLOCK_TICKING))
                    .forEach(holder -> inputs.add(new Input(holder, holder.getTickingChunkFuture())));
        }
        return List.copyOf(inputs);
    }
}
