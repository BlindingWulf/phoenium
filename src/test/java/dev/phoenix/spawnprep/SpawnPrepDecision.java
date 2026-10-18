package dev.phoenix.spawnprep;

/** Whether this launch prepares the spawn area, and why (the reason is logged for the management app). */
public record SpawnPrepDecision(boolean run, String reason) {
    public static SpawnPrepDecision decide(SpawnPrepMarker marker, boolean newWorld, SpawnPrepConfig config, String dimension,
                                           int centerX, int centerZ) {
        if (!config.enabled()) return new SpawnPrepDecision(false, "disabled");
        if (marker == null) {
            if (newWorld) return new SpawnPrepDecision(true, "new-world");
            return config.existingWorlds() ? new SpawnPrepDecision(true, "existing-world-opt-in")
                                           : new SpawnPrepDecision(false, "existing-world");
        }
        if (!marker.dimension().equals(dimension) || marker.centerX() != centerX || marker.centerZ() != centerZ) {
            return new SpawnPrepDecision(true, "center-changed");
        }
        return switch (marker.state()) {
            case COMPLETE -> marker.radiusBlocks() >= config.radiusBlocks() ? new SpawnPrepDecision(false, "already-prepared")
                                                                           : new SpawnPrepDecision(true, "expand");
            case CANCELLED -> marker.radiusBlocks() == config.radiusBlocks() ? new SpawnPrepDecision(false, "cancelled-by-user")
                                                                            : new SpawnPrepDecision(true, "radius-changed");
            case PARTIAL, RUNNING -> new SpawnPrepDecision(true, "resume");
        };
    }
}
