package dev.phoenium.engine;

import dev.phoenium.engine.mixin.EngineTrackedEntityAccess;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * S9-E13 R-C2 (engine mode; {@link EngineConfig#MOVE_SPLIT}, default off; owner decision 2026-10-10, STRUCT-2): the
 * tracker update of the players' move packets as a parallel evaluation and a serial apply.
 *
 * <p>A move packet's ChunkMap.move(player) (from ServerChunkCache.move inside handleMovePlayer, not riding) is deferred to
 * the end of the queued packets of the frame, once per player, in the order of the players' first moves. Vanilla orders
 * packets only within one connection, so where another player's packets fall between two moves is already arbitrary;
 * nothing else in packet handling reads what the tracker update changes (seenBy, pairing, the player's section, ticket
 * and chunk view) except later moves of the same frame, which see it after the flush instead.
 *
 * <p>The flush first evaluates every deferred move on the tile threads and the server thread: ChunkMap.move's tracker
 * loop (the player's own tracker against every player of the level, then the trackers {@link EngineTrackerIndex} finds
 * for the player, through the S9-E4 filter) runs vanilla's TrackedEntity.updatePlayer with its two changes, the seenBy
 * add and removePlayer, turned into records of what they would change. Nothing changes during the evaluation, so it
 * reads the same state on every thread. Then, on the server thread and in order, each move re-runs updatePlayer for its
 * recorded checks (vanilla's code, which checks again against the state at that point), re-runs its own tracker against
 * the players whose chunk view an earlier move of this flush changed, and runs the rest of ChunkMap.move (section change,
 * distance manager, chunk tracking) with an empty tracker loop.
 *
 * <p>That apply equals a serial run of the deferred moves in order: a move's checks of other trackers depend on its own
 * player's view (changed only by its own rest, which comes after them), the entities' positions and the seenBy sets (an
 * earlier move can only change a pair this move evaluated identically, and the re-run sees the change); its own tracker's
 * checks also depend on the other players' views, which is why the changed ones are re-run. With
 * {@link EngineConfig#MOVE_SPLIT_CHECK} every N-th flush also evaluates each move serially right before its apply and
 * counts every change the apply would miss (moveSplitMismatch, must stay 0; the missed change is then made, so a check
 * run stays vanilla's). Fewer than {@link EngineConfig#MOVE_SPLIT_MIN} moves, or an index that cannot answer, run the
 * deferred moves serially as vanilla's move. moveSplitStray (an evaluation record with no move being evaluated) must
 * stay 0.
 *
 * <p>ServerChunkCache.move's waypoint update (locator bar) after a deferred ChunkMap.move is deferred with it and runs right
 * after the move's apply, so it reads the updated chunk view as in vanilla. A move whose evaluation or apply throws (a
 * third-party hook) is logged and counted (moveSplitFailures, must stay 0) and run once more as vanilla's whole move; the
 * other moves of the flush go on.
 */
public final class EngineMoveSplit {
    private EngineMoveSplit() { }

    /** A ChunkMap that evaluates and applies deferred moves (the engine's ChunkMap tracker mixin). */
    public interface Target {
        ServerLevel phoenium$level();

        /** Server thread, before an evaluation: whether the tracker index can answer (rebuilt first when marked). */
        boolean phoenium$splitReady();

        /** Any thread, while nothing changes the trackers: move(player)'s tracker checks, recording what they would change. */
        void phoenium$evaluateMove(ServerPlayer player);

        /** Server thread: the player's own tracker against {@code players} (their chunk view changed); returns the checks run. */
        int phoenium$recheckOwn(ServerPlayer player, List<ServerPlayer> players);

        /** Server thread: ChunkMap.move(player); while {@link #restOnly} its tracker loop visits nothing. */
        void phoenium$moveNow(ServerPlayer player);

        /** The player's own tracker (TrackedEntity) in this ChunkMap, or null. */
        Object phoenium$ownTracker(ServerPlayer player);
    }

    /** One deferred move and the tracker checks its evaluation found would change something. */
    static final class Pending {
        final Target map;
        final ServerPlayer player;
        boolean valid;
        boolean failed;
        boolean waypoints;
        final ArrayList<Object> trackers = new ArrayList<>();
        final ArrayList<ServerPlayer> targets = new ArrayList<>();

        Pending(Target map, ServerPlayer player) {
            this.map = map;
            this.player = player;
        }

        boolean has(Object tracker, ServerPlayer target) {
            for (int i = 0, n = this.trackers.size(); i < n; i++) {
                if (this.trackers.get(i) == tracker && this.targets.get(i) == target) return true;
            }
            return false;
        }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("Phoenium");
    private static final AtomicInteger MISMATCH_LOGS = new AtomicInteger();
    private static final AtomicInteger FAILURE_LOGS = new AtomicInteger();
    private static final LongAdder FAILURES = new LongAdder();
    private static final LongAdder STRAY = new LongAdder();
    private static final ThreadLocal<Pending> RECORDING = new ThreadLocal<>();
    /** Set by the server thread around an evaluation; the tile threads see it through the job's publication. */
    private static volatile boolean evaluating;
    // server thread
    private static boolean draining;
    private static int movePackets;
    private static boolean restOnly;
    /** The player of the last ChunkMap.move this hook deferred (ServerChunkCache.move's waypoint update follows it). */
    private static ServerPlayer lastDeferred;
    private static int checkCountdown;
    private static final ArrayList<Pending> PENDING = new ArrayList<>();
    private static final Reference2ObjectOpenHashMap<ServerPlayer, Pending> BY_PLAYER = new Reference2ObjectOpenHashMap<>();
    private static final ArrayList<ServerPlayer> VIEW_CHANGED = new ArrayList<>();
    private static final ArrayList<Target> READY = new ArrayList<>();
    private static long deferred;
    private static long inline;
    private static long flushes;
    private static long parallelFlushes;
    private static long serialFlushes;
    private static long entries;
    private static long skipped;
    private static long changes;
    private static long rechecked;
    private static long checks;
    private static long mismatch;
    private static long workNanos;

    // ---- the hooks ----

    /** Head of the frame's queued packets (server thread): move packets may defer their tracker update until {@link #flush}. */
    public static void beginPackets() {
        if (!PENDING.isEmpty()) flush();   // what a frame that threw left behind
        draining = EngineConfig.MOVE_SPLIT && TileEngine.onServerThread();
    }

    /** End of the queued packets: nothing more is deferred. */
    public static void endPackets() {
        if (TileEngine.onServerThread()) draining = false;
    }

    /** ServerboundMovePlayerPacket.handle around handleMovePlayer (counted on the server thread only). */
    public static void movePacketStart() {
        if (TileEngine.onServerThread()) movePackets++;
    }

    public static void movePacketEnd() {
        if (movePackets > 0 && TileEngine.onServerThread()) movePackets--;
    }

    /** ServerChunkCache.move's ChunkMap.move(player): true if deferred to the end of the queued packets. */
    public static boolean defer(Object chunkMap, ServerPlayer player) {
        lastDeferred = null;
        if (!draining || movePackets == 0 || player.isPassenger() || !TileEngine.onServerThread() || TileEngine.current() != null) return false;
        Target map = (Target) chunkMap;
        if (player.level() != map.phoenium$level()) return false;
        Pending pending = BY_PLAYER.get(player);
        if (pending == null) {
            pending = new Pending(map, player);
            BY_PLAYER.put(player, pending);
            PENDING.add(pending);
        } else if (pending.map != map) {
            inline++;
            return false;
        }
        deferred++;
        lastDeferred = player;
        return true;
    }

    /** ServerChunkCache.move's waypoint update right after a deferred ChunkMap.move: true if it waits for that move's apply. */
    public static boolean deferWaypoints(ServerPlayer player) {
        if (lastDeferred != player || !TileEngine.onServerThread()) return false;
        lastDeferred = null;
        Pending pending = BY_PLAYER.get(player);
        if (pending == null) return false;
        pending.waypoints = true;
        return true;
    }

    /** ChunkMap.move's tracker loop is left out (the apply has run its checks). */
    public static boolean restOnly() { return restOnly; }

    /** TrackedEntity.updatePlayer's seenBy add and removePlayer: record instead of changing (an evaluation is running). */
    public static boolean evaluating() { return evaluating; }

    /** In an evaluation: updatePlayer would add {@code connection} to or remove it from {@code tracker}'s seenBy. */
    public static void record(Object tracker, ServerPlayerConnection connection) {
        Pending pending = RECORDING.get();
        if (pending == null) {
            STRAY.increment();
            return;
        }
        pending.trackers.add(tracker);
        pending.targets.add(connection.getPlayer());
    }

    // ---- the flush ----

    /** After the queued packets (server thread): evaluates the deferred moves in parallel, then applies them in order. */
    public static void flush() {
        lastDeferred = null;
        int count = PENDING.size();
        if (count == 0) return;
        long start = EngineTimers.start();
        try {
            flushes++;
            entries += count;
            for (int i = 0; i < count; i++) {
                Pending pending = PENDING.get(i);
                pending.valid = !pending.player.isRemoved() && pending.player.level() == pending.map.phoenium$level();
            }
            if (count >= EngineConfig.MOVE_SPLIT_MIN && TileEngine.canRunParallel() && ready()) {
                long work;
                evaluating = true;
                try {
                    work = TileEngine.runParallel(count, 1, i -> evaluate(PENDING.get(i)), false);
                } finally {
                    evaluating = false;
                }
                if (work >= 0L) {
                    workNanos += work;
                    parallelFlushes++;
                    long applyStart = EngineTimers.start();
                    try {
                        apply();
                    } finally {
                        EngineTimers.add(EngineTimers.MOVE_SPLIT_APPLY, applyStart);
                    }
                    return;
                }
            }
            serialFlushes++;
            for (int i = 0; i < count; i++) {
                Pending pending = PENDING.get(i);
                if (!pending.valid) {
                    skipped++;
                    continue;
                }
                try {
                    pending.map.phoenium$moveNow(pending.player);
                    waypoints(pending);
                } catch (RuntimeException | LinkageError failure) {
                    failed(pending, "serial move", failure);
                }
            }
        } finally {
            PENDING.clear();
            BY_PLAYER.clear();
            VIEW_CHANGED.clear();
            READY.clear();
            EngineTimers.add(EngineTimers.MOVE_SPLIT, start);
        }
    }

    private static boolean ready() {
        for (int i = 0, n = PENDING.size(); i < n; i++) {
            Pending pending = PENDING.get(i);
            if (!pending.valid || READY.contains(pending.map)) continue;
            if (!pending.map.phoenium$splitReady()) return false;
            READY.add(pending.map);
        }
        return true;
    }

    private static void evaluate(Pending pending) {
        if (!pending.valid) return;
        RECORDING.set(pending);
        try {
            pending.map.phoenium$evaluateMove(pending.player);
        } catch (RuntimeException | LinkageError failure) {
            pending.failed = true;   // applied as vanilla's whole move on the server thread
            failed(pending, "evaluation", failure);
        } finally {
            RECORDING.set(null);
        }
    }

    /** After a deferred move's apply: its deferred waypoint update, as ServerChunkCache.move does after ChunkMap.move. */
    private static void waypoints(Pending pending) {
        if (pending.waypoints && pending.player.isReceivingWaypoints()) pending.map.phoenium$level().getWaypointManager().updatePlayer(pending.player);
    }

    private static void failed(Pending pending, String where, Throwable failure) {
        FAILURES.increment();
        if (FAILURE_LOGS.incrementAndGet() <= 5) {
            LOGGER.error("PHOENIUM_ENGINE moveSplit {} failed for {}", where, pending.player.getScoreboardName(), failure);
        }
    }

    /** A move whose evaluation or apply failed: vanilla's whole move (tracker loop included), once. */
    private static void recover(Pending pending) {
        restOnly = false;
        try {
            pending.map.phoenium$moveNow(pending.player);
            waypoints(pending);
        } catch (RuntimeException | LinkageError failure) {
            failed(pending, "recovery move", failure);
        }
    }

    private static void apply() {
        boolean check = EngineConfig.MOVE_SPLIT_CHECK > 0 && --checkCountdown <= 0;
        if (check) checkCountdown = EngineConfig.MOVE_SPLIT_CHECK;
        for (int p = 0, count = PENDING.size(); p < count; p++) {
            Pending pending = PENDING.get(p);
            if (!pending.valid) {
                skipped++;
                continue;
            }
            Object view = pending.player.getChunkTrackingView();
            if (pending.failed) {
                recover(pending);
            } else {
                try {
                    if (check) check(pending);
                    for (int i = 0, n = pending.trackers.size(); i < n; i++) {
                        ((EngineTrackedEntityAccess) pending.trackers.get(i)).phoenium$updatePlayer(pending.targets.get(i));
                    }
                    changes += pending.trackers.size();
                    if (!VIEW_CHANGED.isEmpty()) rechecked += pending.map.phoenium$recheckOwn(pending.player, VIEW_CHANGED);
                    restOnly = true;
                    try {
                        pending.map.phoenium$moveNow(pending.player);
                    } finally {
                        restOnly = false;
                    }
                    waypoints(pending);
                } catch (RuntimeException | LinkageError failure) {
                    failed(pending, "apply", failure);
                    recover(pending);
                }
            }
            if (pending.player.getChunkTrackingView() != view) VIEW_CHANGED.add(pending.player);
        }
    }

    /** The move evaluated serially at its turn: every change it finds must be one the apply re-runs. */
    private static void check(Pending pending) {
        Pending serial = new Pending(pending.map, pending.player);
        evaluating = true;
        RECORDING.set(serial);
        try {
            pending.map.phoenium$evaluateMove(pending.player);
        } finally {
            evaluating = false;
            RECORDING.set(null);
        }
        checks++;
        Object own = pending.map.phoenium$ownTracker(pending.player);
        for (int i = 0, n = serial.trackers.size(); i < n; i++) {
            Object tracker = serial.trackers.get(i);
            ServerPlayer target = serial.targets.get(i);
            if (pending.has(tracker, target) || tracker == own && VIEW_CHANGED.contains(target)) continue;
            mismatch++;
            if (MISMATCH_LOGS.incrementAndGet() <= 10) {
                LOGGER.warn("PHOENIUM_ENGINE moveSplit mismatch player={} entity={} target={}", pending.player.getScoreboardName(),
                    ((EngineTrackedEntityAccess) tracker).phoenium$entity(), target.getScoreboardName());
            }
            ((EngineTrackedEntityAccess) tracker).phoenium$updatePlayer(target);   // the check run stays vanilla's
        }
    }

    static String counters() {
        return "moveSplitDeferred=" + deferred + " moveSplitInline=" + inline + " moveSplitFlushes=" + flushes
            + " moveSplitParallel=" + parallelFlushes + " moveSplitSerial=" + serialFlushes + " moveSplitEntries=" + entries
            + " moveSplitSkipped=" + skipped + " moveSplitChanges=" + changes + " moveSplitRechecked=" + rechecked
            + " moveSplitChecks=" + checks + " moveSplitWorkUs=" + workNanos / 1000 + " moveSplitMismatch=" + mismatch
            + " moveSplitStray=" + STRAY.sum() + " moveSplitFailures=" + FAILURES.sum();
    }
}
