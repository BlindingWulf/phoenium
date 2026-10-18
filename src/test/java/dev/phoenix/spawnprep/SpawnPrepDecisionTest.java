package dev.phoenix.spawnprep;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Properties;
import org.junit.jupiter.api.Test;

/** Whether this start prepares the spawn area: new worlds yes, finished or cancelled areas are not redone. */
class SpawnPrepDecisionTest {
    private static final String OW = "minecraft:overworld";

    private static SpawnPrepConfig config(int radius, boolean existingWorlds, boolean enabled) {
        Properties p = new Properties();
        p.setProperty("radiusBlocks", String.valueOf(radius));
        p.setProperty("existingWorlds", String.valueOf(existingWorlds));
        p.setProperty("enabled", String.valueOf(enabled));
        return SpawnPrepConfig.from(p);
    }

    private static SpawnPrepMarker marker(SpawnPrepMarker.State state, int radius, int cx, int cz) {
        return new SpawnPrepMarker(1, state, OW, cx, cz, radius, 10, 10, 9, 1, 0, 0L);
    }

    private static String decide(SpawnPrepMarker m, boolean newWorld, SpawnPrepConfig c) {
        SpawnPrepDecision d = SpawnPrepDecision.decide(m, newWorld, c, OW, 0, 0);
        return (d.run() ? "RUN " : "SKIP ") + d.reason();
    }

    @Test
    void newWorldsArePrepared() {
        assertEquals("RUN new-world", decide(null, true, config(256, false, true)));
        assertEquals("SKIP disabled", decide(null, true, config(256, false, false)));
    }

    @Test
    void existingWorldsOnlyWhenAskedFor() {
        assertEquals("SKIP existing-world", decide(null, false, config(256, false, true)));
        assertEquals("RUN existing-world-opt-in", decide(null, false, config(256, true, true)));
    }

    @Test
    void finishedAreasAreNotRedoneOnEveryLaunch() {
        assertEquals("SKIP already-prepared", decide(marker(SpawnPrepMarker.State.COMPLETE, 256, 0, 0), false, config(256, false, true)));
        assertEquals("SKIP already-prepared", decide(marker(SpawnPrepMarker.State.COMPLETE, 1280, 0, 0), false, config(256, false, true)));
        assertEquals("RUN expand", decide(marker(SpawnPrepMarker.State.COMPLETE, 256, 0, 0), false, config(1280, false, true)));
        assertEquals("RUN center-changed", decide(marker(SpawnPrepMarker.State.COMPLETE, 256, 40, 0), false, config(256, false, true)));
    }

    @Test
    void interruptedRunsResumeAndCancelledRunsAreRespected() {
        assertEquals("RUN resume", decide(marker(SpawnPrepMarker.State.PARTIAL, 256, 0, 0), false, config(256, false, true)));
        assertEquals("SKIP cancelled-by-user", decide(marker(SpawnPrepMarker.State.CANCELLED, 256, 0, 0), false, config(256, false, true)));
        assertEquals("RUN radius-changed", decide(marker(SpawnPrepMarker.State.CANCELLED, 256, 0, 0), false, config(512, false, true)));
    }

    @Test
    void markerRoundTripsThroughJson() {
        SpawnPrepMarker m = marker(SpawnPrepMarker.State.PARTIAL, 256, 3, -4);
        assertEquals(m, SpawnPrepMarker.fromJson(m.toJson()));
        assertEquals(null, SpawnPrepMarker.fromJson("{not json"));
    }
}
