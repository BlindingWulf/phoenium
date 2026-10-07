package dev.phoenium.service;

import dev.phoenium.chunk.ChunkProtocolException;
import dev.phoenium.ownership.OwnershipContext;
import dev.phoenium.topology.RoutingKey;
import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Continuation for one owned vanilla neighbour-update cascade that reaches a chunk vanilla would
 * block on (S8-F6). No mirrored loop: the native {@code CollectingNeighborUpdater} keeps running
 * its own steps. A step is cut only at a pure preflight, either before its first read or, for the
 * audited {@code LiquidBlock.shouldSpreadLiquid} reads, before any mutation of that step, and only when
 * vanilla's own fast path would not return the chunk ({@code getChunkNow == null}).
 *
 * <p>The cut retains the updater's exact stack and chain count. The owner then returns, the chunk
 * is selected and awaited owner-free through the poll-turn return fence, and the retained stack is
 * restored and run under the re-resolved current owner. Root cascades that start after a cut are
 * deferred in FIFO order behind the retained one. Nothing is skipped, replaced by air or read
 * from a foreign or unfinished chunk. Declared deviation: the owned body's own code after the cut
 * (not its neighbour updates) runs before the deferred remainder, which vanilla would have run
 * first while blocking the server thread.</p>
 */
public final class NeighborCascadeContinuation implements AutoCloseable {
    /** The native updater, seen through its mixin. Steps stay opaque native objects. */
    public interface Port {
        Object phoenium$cascadeLevel();
        ArrayDeque<Object> phoenium$cascadeStack();
        List<Object> phoenium$cascadeLayer();
        int phoenium$cascadeCount();
        void phoenium$cascadeCount(int count);
        int phoenium$cascadeMaxChained();
        void phoenium$cascadeRunUpdates();
    }
    /** Native step introspection, readiness, and the owner-free chunk wait. */
    public interface Environment {
        /** The position a step reads before anything else, or null when it reads nothing first. */
        BlockPos startRead(Object step);
        /** The position whose block receives the step's direct neighborChanged dispatch, or null. */
        BlockPos dispatchTarget(Object step);
        Object snapshot(Object step);
        void restore(Object step, Object snapshot);
        /** A server level whose own server thread is the current thread. */
        boolean suspendable(Object level);
        /** True exactly when the native getter returns without a synchronous chunk wait. */
        boolean readable(Object level, BlockPos position);
        ChunkWait await(Object level, BlockPos position);
    }
    /** One owner-free FULL selection for the blocked chunk. Completion publishes metadata only. */
    public interface ChunkWait extends AutoCloseable {
        ManagedBlockPollTurnFence fence();
        void managedBlock(BooleanSupplier ready);
        CompletionStage<?> dependency();
        void prepare();
        boolean current();
        @Override void close();
    }
    public enum Phase { ARMED, SUSPENDED, RESUMING, SETTLED, SHUTDOWN, FAILED, CLOSED }

    private static final Logger LOGGER = LoggerFactory.getLogger("Phoenium");
    private static final ThreadLocal<NeighborCascadeContinuation> ACTIVE = new ThreadLocal<>();
    private static final Set<RegionalServiceKind> KINDS = EnumSet.of(RegionalServiceKind.RUN_SCHEDULED_BLOCK_TICK,
        RegionalServiceKind.RUN_SCHEDULED_FLUID_TICK, RegionalServiceKind.BLOCK_EVENT);
    static final int SAME_CHUNK_WAITS = 4, STALE_RETRIES = 8;

    private sealed interface Segment permits Retained, Root { Port updater(); }
    private record Retained(Port updater, List<Object> topFirst, int count) implements Segment { }
    private record Root(Port updater, BlockPos position, Object update) implements Segment { }
    private static final class InFlight {
        final Object level; final BlockPos target; boolean dispatched; BlockPos deep;
        InFlight(Object level, BlockPos target) { this.level = level; this.target = target; }
    }
    private static final class Scope { final InFlight flight; boolean used, reads; Scope(InFlight flight) { this.flight = flight; } }
    /** Stackless and private: thrown only by an audited read wrapper before the native read and
     * caught only by the matching neighborChanged wrapper, never by a crash-report handler. */
    private static final class Suspend extends RuntimeException {
        final transient Scope scope; final BlockPos position;
        Suspend(Scope scope, BlockPos position) { super(null, null, false, false); this.scope = scope; this.position = position; }
    }

    private final Environment environment;
    private final RoutingKey key;
    private final RegionalServiceKind kind;
    private final Thread thread = Thread.currentThread();
    private final ArrayDeque<Segment> segments = new ArrayDeque<>();
    private Phase phase = Phase.ARMED;
    private InFlight inFlight;
    private Scope scope;
    private Object blockedLevel;
    private BlockPos blocked;
    private long suspensions, waits;

    private NeighborCascadeContinuation(Environment environment, RoutingKey key, RegionalServiceKind kind) {
        this.environment = environment; this.key = key; this.kind = kind;
    }

    public static boolean armable(RegionalServiceKind kind) { return KINDS.contains(kind); }

    /** Null when this service kind owns no plain cascade, or when the thread is already inside a
     * native return-fence wait (its pumped tasks keep the existing fail-closed behaviour). */
    static NeighborCascadeContinuation arm(Environment environment, RoutingKey key, RegionalServiceKind kind) {
        if (environment == null || !armable(kind) || ACTIVE.get() != null
                || ManagedBlockPollTurnFence.reservationActive()) return null;
        return new NeighborCascadeContinuation(environment, Objects.requireNonNull(key), kind);
    }

    public Phase phase() { return phase; }
    public long suspensions() { return suspensions; }

    RegionalServiceDispatcher.RegionalServiceWork wrap(RegionalServiceDispatcher.RegionalServiceWork body) {
        Objects.requireNonNull(body);
        return execution -> {
            if (ACTIVE.get() != null || phase != Phase.ARMED || Thread.currentThread() != thread)
                throw new IllegalStateException("neighbor cascade body entered twice or off its arm thread");
            ACTIVE.set(this);
            try { body.run(execution); }
            finally { ACTIVE.remove(); inFlight = null; scope = null; }
        };
    }

    // ---- native hooks -------------------------------------------------------------------------

    /** Wraps {@code NeighborUpdates.runNext} inside {@code CollectingNeighborUpdater.runUpdates}. */
    public static boolean runNext(Port updater, Object step, BooleanSupplier original) {
        var active = ACTIVE.get();
        return active == null ? original.getAsBoolean() : active.step(updater, step, original);
    }

    /** {@code addAndRun} HEAD: a root cascade after a cut joins the FIFO behind the retained one. */
    public static boolean deferRoot(Port updater, BlockPos position, Object update) {
        var active = ACTIVE.get();
        if (active == null || active.phase != Phase.SUSPENDED || updater.phoenium$cascadeCount() != 0) return false;
        active.segments.addLast(new Root(updater, position.immutable(), Objects.requireNonNull(update)));
        return true;
    }

    /** Wraps {@code LiquidBlock.neighborChanged}: only the in-flight step's direct dispatch may cut. */
    public static void neighborChanged(Object level, BlockPos position, Runnable original) {
        var active = ACTIVE.get();
        var flight = active == null ? null : active.inFlight;
        if (flight == null || flight.dispatched || flight.target == null || flight.level != level
                || active.scope != null || !flight.target.equals(position)) { original.run(); return; }
        flight.dispatched = true;
        var opened = active.scope = new Scope(flight);
        try { original.run(); }
        catch (Suspend suspend) {
            if (suspend.scope != opened) throw suspend;
            flight.deep = suspend.position;
        } finally { active.scope = null; }
    }

    /** Wraps the {@code shouldSpreadLiquid} call at the head of {@code LiquidBlock.neighborChanged}. */
    public static boolean spreadCheck(BooleanSupplier original) {
        var active = ACTIVE.get();
        var opened = active == null ? null : active.scope;
        if (opened == null || opened.used) return original.getAsBoolean();
        opened.used = true; opened.reads = true;
        try { return original.getAsBoolean(); }
        finally { opened.reads = false; }
    }

    /** Before an audited read inside {@code shouldSpreadLiquid}; never calls the native getter. */
    public static void deepRead(Object level, BlockPos position) {
        var active = ACTIVE.get();
        var opened = active == null ? null : active.scope;
        if (opened == null || !opened.reads || opened.flight.level != level) return;
        if (!active.environment.readable(level, position)) throw new Suspend(opened, position.immutable());
    }

    /** Before the audited {@code setBlockAndUpdate}: every later read keeps the native guard. */
    public static void beforeMutation() {
        var active = ACTIVE.get();
        if (active != null && active.scope != null) active.scope.reads = false;
    }

    private boolean step(Port updater, Object step, BooleanSupplier original) {
        if (phase == Phase.SUSPENDED) throw new IllegalStateException("neighbor update ran behind a suspended cascade");
        Object level = updater.phoenium$cascadeLevel();
        boolean cuttable = inFlight == null && scope == null && environment.suspendable(level);
        if (cuttable) {
            BlockPos start = environment.startRead(step);
            if (start != null && !environment.readable(level, start)) { suspend(updater, step, level, start); return false; }
        }
        var flight = new InFlight(level, cuttable ? environment.dispatchTarget(step) : null);
        Object snapshot = cuttable ? environment.snapshot(step) : null;
        var previous = inFlight;
        inFlight = flight;
        boolean result;
        try { result = original.getAsBoolean(); }
        finally { inFlight = previous; }
        if (flight.deep == null) return result;
        environment.restore(step, snapshot);
        suspend(updater, step, level, flight.deep);
        return false;
    }

    private void suspend(Port updater, Object step, Object level, BlockPos position) {
        var stack = updater.phoenium$cascadeStack();
        if (!updater.phoenium$cascadeLayer().isEmpty() || stack.peek() != step)
            throw new IllegalStateException("neighbor cascade cut is not at a native step boundary");
        if (phase != Phase.ARMED && phase != Phase.RESUMING)
            throw new IllegalStateException("neighbor cascade cut in phase " + phase);
        // ArrayDeque.push is addFirst, so iteration is top-first; the cut step stays on top.
        segments.addFirst(new Retained(updater, List.copyOf(stack), updater.phoenium$cascadeCount()));
        stack.clear();
        stack.push(step); // native runUpdates pops exactly this step, exits, and resets its state
        blockedLevel = level; blocked = position.immutable(); suspensions++;
        phase = Phase.SUSPENDED;
    }

    // ---- owner-free wait and owned resume -----------------------------------------------------

    /** Called by the dispatcher after the owned body drained, at the same owner-free boundary. */
    void finish(RegionalServiceDispatcher dispatcher) {
        if (phase == Phase.ARMED) { phase = Phase.SETTLED; return; }
        if (phase != Phase.SUSPENDED) throw new IllegalStateException("neighbor cascade finish in phase " + phase);
        if (OwnershipContext.current().isPresent() || ACTIVE.get() != null || Thread.currentThread() != thread)
            throw new IllegalStateException("neighbor cascade wait retained owner authority");
        int stale = 0, sameChunk = 0;
        try {
            while (phase == Phase.SUSPENDED) {
                long chunk = ChunkPos.pack(blocked);
                var state = awaitOnce(dispatcher);
                if (state == ManagedBlockPollTurnFence.State.SETTLED) {
                    stale = 0;
                    // A FULL result the native fast path still cannot see must not spin forever.
                    sameChunk = phase == Phase.SUSPENDED && ChunkPos.pack(blocked) == chunk ? sameChunk + 1 : 0;
                    if (sameChunk >= SAME_CHUNK_WAITS)
                        throw new ChunkProtocolException("neighbor cascade chunk stayed unreadable after its FULL wait: " + blocked);
                    continue;
                }
                if (state == ManagedBlockPollTurnFence.State.SHUTDOWN) {
                    LOGGER.warn("Phoenium neighbor cascade at {} retired by shutdown with {} retained segment(s)", blocked, segments.size());
                    segments.clear(); phase = Phase.SHUTDOWN; return;
                }
                if (++stale > STALE_RETRIES)
                    throw new ChunkProtocolException("neighbor cascade could not reacquire its owner for " + key + " at " + blocked);
                dispatcher.ensureClosure(key, 0);
            }
        } catch (RuntimeException | Error failure) {
            phase = Phase.FAILED; segments.clear(); throw failure;
        }
    }

    private ManagedBlockPollTurnFence.State awaitOnce(RegionalServiceDispatcher dispatcher) {
        long query = ++waits;
        try (var wait = environment.await(blockedLevel, blocked)) {
            wait.prepare();
            try (var reservation = wait.fence().reserve(suspensions, query, wait.dependency(),
                    () -> phase == Phase.SUSPENDED && waits == query && wait.current(),
                    dispatcher::requireNaturalSpawnOwnerFree)
                    .prepareAtObservation(wait::prepare).shutdownWhen(() -> !dispatcher.naturalSpawnOpen())) {
                reservation.awaitAndResume(wait::managedBlock,
                    () -> dispatcher.resumeNeighborCascade(reservation, key, kind, execution -> resume()));
                return reservation.state();
            }
        }
    }

    /** Runs only under the reserved current owner. Re-suspension stops at the new front segment. */
    private void resume() {
        if (OwnershipContext.current().isEmpty() || Thread.currentThread() != thread || ACTIVE.get() != null)
            throw new IllegalStateException("neighbor cascade resumed without its current owner");
        if (phase != Phase.SUSPENDED) throw new IllegalStateException("neighbor cascade resumed in phase " + phase);
        phase = Phase.RESUMING;
        ACTIVE.set(this);
        try {
            while (phase == Phase.RESUMING && !segments.isEmpty()) {
                var segment = segments.removeFirst();
                var updater = segment.updater();
                var stack = updater.phoenium$cascadeStack();
                if (updater.phoenium$cascadeCount() != 0 || !stack.isEmpty() || !updater.phoenium$cascadeLayer().isEmpty())
                    throw new IllegalStateException("neighbor updater is not idle at cascade resume");
                switch (segment) {
                    case Retained retained -> {
                        for (int i = retained.topFirst().size() - 1; i >= 0; i--) stack.push(retained.topFirst().get(i));
                        updater.phoenium$cascadeCount(retained.count());
                    }
                    case Root root -> {
                        // Native addAndRun with count == 0: runningAlready=false.
                        int max = updater.phoenium$cascadeMaxChained();
                        boolean tooMany = max >= 0 && 0 >= max;
                        updater.phoenium$cascadeCount(1);
                        if (!tooMany) stack.push(root.update());
                        else if (max == 0) LOGGER.error("Too many chained neighbor updates. Skipping the rest. First skipped position: {}",
                            root.position().toShortString());
                    }
                }
                updater.phoenium$cascadeRunUpdates();
            }
            if (phase == Phase.RESUMING) phase = Phase.SETTLED;
        } catch (RuntimeException | Error failure) {
            phase = Phase.FAILED; segments.clear(); throw failure;
        } finally { ACTIVE.remove(); inFlight = null; scope = null; }
    }

    @Override public void close() {
        if (ACTIVE.get() == this) ACTIVE.remove();
        if (phase == Phase.SUSPENDED || phase == Phase.RESUMING) phase = Phase.FAILED;
        else if (phase == Phase.ARMED) phase = Phase.CLOSED;
        segments.clear(); inFlight = null; scope = null; blockedLevel = null;
    }
}
