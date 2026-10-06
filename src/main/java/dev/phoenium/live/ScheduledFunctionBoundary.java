package dev.phoenium.live;

import java.util.Objects;

/**
 * Vanilla advances the server's /schedule timer queue from ServerLevel.tickTime, which the live tick runs inside
 * the prefix owner turn. A scheduled function is a global command source (Folia runs it on the global region):
 * it may name any dimension, so it must never execute with one region's owner authority. The prefix captures
 * that single TimerQueue tick uncalled; the scheduled coordinator replays it owner-free before scheduled
 * block/fluid ticks, which is the same position in the vanilla tick. A capture that never reaches the
 * coordinator is discarded uncalled, so its due events stay queued for the next tick. Server thread only: the
 * live tick dispatch, its prefix owner step and its coordinator steps all run there.
 */
public final class ScheduledFunctionBoundary {
    private boolean armed;
    private Runnable deferred;
    private long captures;
    private long replays;
    private long discards;

    public void arm() {
        if (armed || deferred != null) throw new IllegalStateException("scheduled-function boundary armed with retained work");
        armed = true;
    }

    public void disarm() { armed = false; }

    /** Returns true when the timer-queue tick was deferred and must not run now. */
    public boolean capture(boolean ownerPresent, Runnable timerTick) {
        Objects.requireNonNull(timerTick, "timerTick");
        if (!armed || !ownerPresent) return false;
        if (deferred != null) throw new IllegalStateException("second scheduled-function tick in one live prefix");
        deferred = timerTick;
        captures++;
        return true;
    }

    public void runOwnerFree(boolean ownerPresent) {
        if (armed) throw new IllegalStateException("scheduled functions replayed inside the live prefix");
        if (ownerPresent) throw new IllegalStateException("scheduled functions replayed with retained owner authority");
        Runnable call = deferred;
        deferred = null;
        if (call == null) return;
        replays++;
        call.run();
    }

    public void discard() {
        armed = false;
        if (deferred != null) {
            deferred = null;
            discards++;
        }
    }

    public long captures() { return captures; }
    public long replays() { return replays; }
    public long discards() { return discards; }
}
