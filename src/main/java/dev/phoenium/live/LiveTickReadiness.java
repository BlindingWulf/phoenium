package dev.phoenium.live;

import dev.phoenium.ownership.OwnershipContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;
import net.minecraft.server.level.*;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Ordinary public ticking prerequisites, observed after the original distance-update stage. */
public final class LiveTickReadiness {
    private LiveTickReadiness() { }
    public interface MapAccess { Stream<ChunkHolder> phoenium$tickCandidates(ChunkStatus status); }
    /** Provider adapters may expose the stable producer slot behind a public wrapper future. */
    public interface TickingSlotAccess { Object phoenium$tickingSlotIdentity(); }

    private static final class Candidate {
        final ChunkHolder holder;
        final Object slot;
        Candidate(ChunkHolder holder, Object slot) {
            this.holder = Objects.requireNonNull(holder, "holder");
            this.slot = Objects.requireNonNull(slot, "slot");
        }
        @Override public boolean equals(Object other) {
            return other instanceof Candidate candidate && holder == candidate.holder && slot == candidate.slot;
        }
        @Override public int hashCode() {
            return 31 * System.identityHashCode(holder) + System.identityHashCode(slot);
        }
    }

    private record Input(Candidate candidate, CompletableFuture<ChunkResult<LevelChunk>> future) { }
    public static final class Receipt {
        private final ServerLevel level;
        private final List<Input> inputs;
        private Receipt(ServerLevel level, List<Input> inputs) { this.level = level; this.inputs = inputs; }
        public boolean current() { return sameCandidates(inputs, candidates(level)) && settled(inputs); }
        public int count() { return inputs.size(); }
    }
    public static Receipt await(ServerLevel level) {
        requireCoordinator(level);
        return observeUntilSettled(level);
    }
    /** A receipt observes a producer slot; it does not prevent native slot replacement. */
    public static Receipt refresh(Receipt receipt) {
        Objects.requireNonNull(receipt, "receipt");
        requireCoordinator(receipt.level);
        while (!receipt.current()) receipt = observeUntilSettled(receipt.level);
        return receipt;
    }
    private static void requireCoordinator(ServerLevel level) {
        if (OwnershipContext.current().isPresent() || !level.getServer().isSameThread())
            throw new IllegalStateException("ticking readiness must run on the owner-free server thread");
    }
    private static Receipt observeUntilSettled(ServerLevel level) {
        for (;;) {
            List<Input> inputs = capture(level);
            // Existing original ticking futures already include their radius-one FULL range.
            // No future, ticket, public result, callback or absence is synthesized here.
            level.getServer().managedBlock(() -> settled(inputs) || !sameCandidates(inputs, candidates(level)));
            Receipt receipt = new Receipt(level, inputs);
            if (receipt.current()) return receipt;
        }
    }
    private static List<Input> capture(ServerLevel level) {
        List<Input> inputs = new ArrayList<>();
        try (var holders = tickCandidates(level)) {
            holders.forEach(holder -> inputs.add(capture(holder)));
        }
        return List.copyOf(inputs);
    }
    private static Input capture(ChunkHolder holder) {
        if (C2meReadinessSlot.supports(holder)) {
            C2meReadinessSlot.Observation observation = C2meReadinessSlot.capture(holder);
            return new Input(new Candidate(holder, observation.slot()), observation.future());
        }
        if (!(holder instanceof TickingSlotAccess access)) {
            CompletableFuture<ChunkResult<LevelChunk>> future = holder.getTickingChunkFuture();
            return new Input(new Candidate(holder, future), future);
        }
        for (;;) {
            Object before = Objects.requireNonNull(access.phoenium$tickingSlotIdentity(), "ticking slot identity");
            CompletableFuture<ChunkResult<LevelChunk>> future = holder.getTickingChunkFuture();
            Object after = Objects.requireNonNull(access.phoenium$tickingSlotIdentity(), "ticking slot identity");
            if (before == after) return new Input(new Candidate(holder, after), future);
        }
    }
    private static List<Candidate> candidates(ServerLevel level) {
        List<Candidate> candidates = new ArrayList<>();
        try (var holders = tickCandidates(level)) {
            holders.forEach(holder -> candidates.add(new Candidate(holder, slotIdentity(holder))));
        }
        return List.copyOf(candidates);
    }
    private static Stream<ChunkHolder> tickCandidates(ServerLevel level) {
        return ((MapAccess) level.getChunkSource().chunkMap).phoenium$tickCandidates(ChunkStatus.FULL)
                .filter(holder -> ChunkLevel.fullStatus(holder.getTicketLevel()).isOrAfter(FullChunkStatus.BLOCK_TICKING));
    }
    private static Object slotIdentity(ChunkHolder holder) {
        if (C2meReadinessSlot.supports(holder)) return C2meReadinessSlot.identity(holder);
        if (holder instanceof TickingSlotAccess access)
            return Objects.requireNonNull(access.phoenium$tickingSlotIdentity(), "ticking slot identity");
        return holder.getTickingChunkFuture();
    }
    private static boolean settled(List<Input> inputs) {
        return inputs.stream().allMatch(input -> input.future.isDone());
    }
    private static boolean sameCandidates(List<Input> inputs, List<Candidate> candidates) {
        if (inputs.size() != candidates.size()) return false;
        for (int i = 0; i < inputs.size(); i++) {
            if (!inputs.get(i).candidate.equals(candidates.get(i))) return false;
        }
        return true;
    }
}
