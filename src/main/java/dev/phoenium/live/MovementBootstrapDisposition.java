package dev.phoenium.live;

/** Bootstrap-only packet disposition; it never creates a regional replay marker. */
public enum MovementBootstrapDisposition {
    ADMIT,
    DEFER_UNTIL_TELEPORT_OR_BASELINE_CONVERGES,
    REJECT_UNCLASSIFIED;

    public static MovementBootstrapDisposition from(LiveMovementSnapshot.Disposition disposition) {
        return switch (disposition) {
            case CLASSIFIED -> ADMIT;
            case AWAITING_TELEPORT, LAST_GOOD_MISMATCH -> DEFER_UNTIL_TELEPORT_OR_BASELINE_CONVERGES;
            default -> REJECT_UNCLASSIFIED;
        };
    }
}
