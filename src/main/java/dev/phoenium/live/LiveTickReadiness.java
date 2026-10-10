package dev.phoenium.live;

import dev.phoenium.ownership.OwnershipContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
import java.util.stream.Stream;
import net.minecraft.server.level.*;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Ordinary public ticking prerequisites, observed after the original distance-update stage. */
public final class LiveTickReadiness {
    private LiveTickReadiness() { }
    /** One nominal server tick: the S8-F4 bound on a live readiness wait. */
    public static final long TICK_BUDGET_NANOS = 50_000_000L;
    /**
     * S9-D8: the live tick's readiness budget. Vanilla never waits for a ticking future: a chunk whose future is still
     * outstanding is skipped and ticks once its holder promotion runs in the server's between-tick task window. A
     * zero budget takes the same receipt the S8-F4 expiry path already takes, without first holding the tick while
     * every pumped task re-walks the candidate set (the join-storm and new-terrain MSPT spikes in Stage 9).
     * {@code -Dphoenium.live.readinessBudgetMs=50} restores the Stage 8 bounded wait.
     */
    public static final long LIVE_BUDGET_NANOS = liveBudgetNanos();
    private static long liveBudgetNanos() {
        long millis = Long.getLong("phoenium.live.readinessBudgetMs", 0L);
        if (millis < 0 || millis > TICK_BUDGET_NANOS / 1_000_000L)
            throw new IllegalStateException("phoenium.live.readinessBudgetMs must be within 0.." + TICK_BUDGET_NANOS / 1_000_000L);
        return millis * 1_000_000L;
    }
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
    /** Unbounded: the receipt is current only while every candidate is settled. */
    private static final Budget UNBOUNDED = new Budget(null, 0L);
    /** A tick deadline on a monotonic clock. Once it has passed, outstanding candidates no longer hold the tick. */
    private record Budget(LongSupplier clock, long deadline) {
        boolean expired() { return clock != null && clock.getAsLong() - deadline >= 0; }
    }
    public static final class Receipt {
        private final ServerLevel level;
        private final List<Input> inputs;
        private final Budget budget;
        private final int pending, count;
        private Receipt(ServerLevel level, List<Input> inputs, Budget budget) {
            this.level = level; this.inputs = inputs; this.budget = budget;
            this.pending = (int) inputs.stream().filter(input -> !input.future.isDone()).count();
            this.count = inputs.size();
        }
        /** S9-D14: a zero-budget receipt: it holds nothing, so it records only the counts it reports. */
        private Receipt(ServerLevel level, int pending, int count) {
            this.level = level; this.inputs = null; this.budget = null; this.pending = pending; this.count = count;
        }
        /**
         * Same candidates and slots, and either every original ticking future has settled or the tick budget has passed.
         * A candidate still outstanding after the budget is not ticked: its {@code getTickingChunk()} stays null, exactly
         * as vanilla skips a chunk whose ticking future has not completed. A zero-budget receipt is always current: its
         * budget had passed when it was taken, and it observes no candidate identity.
         */
        public boolean current() { return inputs == null || sameCandidates(inputs, level) && (budget.expired() || settled(inputs)); }
        public int count() { return count; }
        /** Candidates whose original ticking future was still outstanding when this receipt was taken. */
        public int pending() { return pending; }
    }
    public static Receipt await(ServerLevel level) {
        requireCoordinator(level);
        return observeUntilSettled(level, UNBOUNDED);
    }
    /**
     * Live tick entry: readiness may hold the tick for at most {@code budgetNanos} of {@code clock}. Waiting for every
     * candidate without a bound lets generation demand that outruns the generators (a fast elytra flight into new
     * terrain) hold one tick until the watchdog ends the server (S8-F4): each pumped task can publish a new unsettled
     * candidate, so the candidate set never settles.
     */
    public static Receipt awaitWithin(ServerLevel level, long budgetNanos, LongSupplier clock) {
        if (budgetNanos < 0) throw new IllegalArgumentException("negative readiness budget");
        Objects.requireNonNull(clock, "clock");
        requireCoordinator(level);
        if (budgetNanos == 0) return zeroBudget(level);
        return observeUntilSettled(level, new Budget(clock, clock.getAsLong() + budgetNanos));
    }
    /**
     * S9-D14: a zero budget (the live default since S9-D8) has expired before the first observation, so the tick is
     * never held and the candidate and slot observation decides nothing. One walk counts the candidates whose
     * original ticking future is outstanding (the receipt's only report) instead of capturing every provider slot
     * and re-walking the candidates to confirm them.
     */
    private static Receipt zeroBudget(ServerLevel level) {
        int[] counts = {0, 0};
        try (var holders = tickCandidates(level)) {
            // S9-D15: C2ME's getTickingChunkFuture wraps its BLOCK_TICKING slot in a new handle() future per call; the
            // wrapper is done exactly when that slot is, so the slot itself is asked.
            holders.forEach(holder -> { counts[1]++; if (!tickingDone(holder)) counts[0]++; });
        }
        return new Receipt(level, counts[0], counts[1]);
    }
    private static boolean tickingDone(ChunkHolder holder) {
        if (C2meReadinessSlot.supports(holder)) return ((CompletableFuture<?>) C2meReadinessSlot.identity(holder)).isDone();
        return holder.getTickingChunkFuture().isDone();
    }
    /** A receipt observes a producer slot; it does not prevent native slot replacement. The original budget still applies. */
    public static Receipt refresh(Receipt receipt) {
        Objects.requireNonNull(receipt, "receipt");
        requireCoordinator(receipt.level);
        while (!receipt.current()) receipt = observeUntilSettled(receipt.level, receipt.budget);
        return receipt;
    }
    private static void requireCoordinator(ServerLevel level) {
        if (OwnershipContext.current().isPresent() || !level.getServer().isSameThread())
            throw new IllegalStateException("ticking readiness must run on the owner-free server thread");
    }
    private static Receipt observeUntilSettled(ServerLevel level, Budget budget) {
        for (;;) {
            List<Input> inputs = capture(level);
            // Existing original ticking futures already include their radius-one FULL range.
            // No future, ticket, public result, callback or absence is synthesized here.
            // S9-D4: managedBlock re-tests this after every pumped task. A completed future never becomes
            // incomplete, so the settled scan resumes at the first input it last found outstanding, and the
            // candidate comparison walks the live candidates without building a list.
            // S9-D8: the expiry test first: once the budget has passed (always, for a zero budget) the condition
            // holds without the settled scan or the candidate walk. The disjunction itself is unchanged.
            int[] firstOutstanding = {0};
            level.getServer().managedBlock(() -> budget.expired() || settledFrom(inputs, firstOutstanding)
                    || !sameCandidates(inputs, level));
            Receipt receipt = new Receipt(level, inputs, budget);
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
    private static boolean settledFrom(List<Input> inputs, int[] firstOutstanding) {
        for (int i = firstOutstanding[0]; i < inputs.size(); i++) {
            if (!inputs.get(i).future.isDone()) { firstOutstanding[0] = i; return false; }
        }
        firstOutstanding[0] = inputs.size();
        return true;
    }
    /** Every live candidate (holder and slot identity) is still observed, in order, with none added or removed; no
     * candidate list is allocated. */
    private static boolean sameCandidates(List<Input> inputs, ServerLevel level) {
        int[] index = {0};
        boolean[] same = {true};
        try (var holders = tickCandidates(level)) {
            holders.forEach(holder -> {
                Object slot = Objects.requireNonNull(slotIdentity(holder), "slot");
                int i = index[0]++;
                if (same[0] && (i >= inputs.size() || inputs.get(i).candidate.holder != holder
                        || inputs.get(i).candidate.slot != slot)) same[0] = false;
            });
        }
        return same[0] && index[0] == inputs.size();
    }
}
