package dev.phoenium.live;

/** Bound for one retained portal transfer waiting on destination entity visibility, measured in server ticks.
 * The coordinator advances after every native entity tick callback as well as at its own phases, so the number
 * of advance() calls in one tick grows with the source level's entity count; a call budget ran out inside a
 * single tick while a generated exit chunk was still loading. */
final class PortalVisibilityDeadline {
    static final int TICKS = 200;
    private boolean started;
    private long firstTick;

    /** True once this wait has spanned more than {@link #TICKS} server ticks since its first observation. */
    boolean expired(long tick) {
        if (!started) { started = true; firstTick = tick; }
        return tick - firstTick > TICKS;
    }
}
