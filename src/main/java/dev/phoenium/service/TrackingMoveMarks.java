package dev.phoenium.service;

/**
 * S9-D5: identity marks for ChunkMap.move's per-tracker {@code updatePlayer} callback. While a regional move plan
 * runs its original body, the moving player carries the plan and each selected tracker carries it as its mark.
 * For any other tracker {@link RegionalTrackingService#movedPlayerUpdate} returns before the original call, so the
 * callback can skip it without the regional-service lookup, the plan ThreadLocal or a lambda. Only the move that
 * created a plan writes that plan, so a mark equal to it proves selection; another thread's concurrent mark write
 * can only replace one foreign value with another.
 */
public final class TrackingMoveMarks {
    private TrackingMoveMarks() { }
    public interface Tracker {
        Object phoenium$moveMark();
        void phoenium$moveMark(Object plan);
    }
    public interface Mover {
        Object phoenium$activeMovePlan();
        void phoenium$activeMovePlan(Object plan);
    }
    /** True when {@code player}'s active move plan exists and did not select {@code tracker}. An object without
     * the mark interfaces is never skipped. */
    public static boolean unselected(Object tracker, Object player) {
        Object plan = player instanceof Mover mover ? mover.phoenium$activeMovePlan() : null;
        return plan != null && tracker instanceof Tracker marked && marked.phoenium$moveMark() != plan;
    }
    static void mark(Iterable<Object> selected, Object player, Object plan) {
        for (Object tracker : selected) if (tracker instanceof Tracker marked) marked.phoenium$moveMark(plan);
        if (player instanceof Mover mover) mover.phoenium$activeMovePlan(plan);
    }
    static void unmark(Iterable<Object> selected, Object player, Object plan) {
        if (player instanceof Mover mover) mover.phoenium$activeMovePlan(null);
        for (Object tracker : selected)
            if (tracker instanceof Tracker marked && marked.phoenium$moveMark() == plan) marked.phoenium$moveMark(null);
    }
}
