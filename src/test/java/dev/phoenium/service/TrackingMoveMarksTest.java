package dev.phoenium.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TrackingMoveMarksTest {
    private static final class Tracker implements TrackingMoveMarks.Tracker {
        Object mark;
        @Override public Object phoenium$moveMark() { return mark; }
        @Override public void phoenium$moveMark(Object plan) { mark = plan; }
    }
    private static final class Mover implements TrackingMoveMarks.Mover {
        Object plan;
        @Override public Object phoenium$activeMovePlan() { return plan; }
        @Override public void phoenium$activeMovePlan(Object plan) { this.plan = plan; }
    }

    /** Only a tracker marked with the moving player's own active plan is treated as selected; without an
     * active plan nothing is skipped, so non-regional moves keep the full callback. */
    @Test void onlyTheActivePlansOwnMarkSelects() {
        var tracker = new Tracker(); var mover = new Mover();
        Object plan = new Object(), foreign = new Object();
        assertFalse(TrackingMoveMarks.unselected(tracker, mover));
        tracker.mark = plan;
        assertFalse(TrackingMoveMarks.unselected(tracker, mover));
        mover.plan = plan;
        assertFalse(TrackingMoveMarks.unselected(tracker, mover));
        tracker.mark = foreign;
        assertTrue(TrackingMoveMarks.unselected(tracker, mover));
        tracker.mark = null;
        assertTrue(TrackingMoveMarks.unselected(tracker, mover));
        assertFalse(TrackingMoveMarks.unselected(new Object(), mover), "an unmarkable tracker keeps the full callback");
        assertFalse(TrackingMoveMarks.unselected(tracker, new Object()), "an unmarkable player keeps the full callback");
        TrackingMoveMarks.mark(java.util.List.of(tracker, new Object()), mover, foreign);
        assertSame(foreign, tracker.mark); assertSame(foreign, mover.plan);
        TrackingMoveMarks.unmark(java.util.List.of(tracker), mover, foreign);
        assertNull(tracker.mark); assertNull(mover.plan);
    }
}
