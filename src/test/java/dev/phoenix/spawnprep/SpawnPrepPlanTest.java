package dev.phoenix.spawnprep;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Spawn preparation: which chunks, in which order. */
class SpawnPrepPlanTest {
    @Test
    void radiusInBlocksRoundsUpToWholeChunks() {
        assertEquals(0, SpawnPrepPlan.chunkRadius(0));
        assertEquals(1, SpawnPrepPlan.chunkRadius(1));
        assertEquals(1, SpawnPrepPlan.chunkRadius(16));
        assertEquals(16, SpawnPrepPlan.chunkRadius(256));
        assertEquals(80, SpawnPrepPlan.chunkRadius(1280));
    }

    @Test
    void squareAroundTheSpawnChunk() {
        assertEquals(1, SpawnPrepPlan.order(0, 0, 0).size());
        assertEquals(1089, SpawnPrepPlan.order(0, 0, 16).size());      // 256 blocks: 33 x 33
        assertEquals(25921, SpawnPrepPlan.order(0, 0, 80).size());     // 1280 blocks: 161 x 161
    }

    @Test
    void nearestRingsFirstSoAPartialRunStillCoversTheSpawn() {
        List<SpawnPrepPlan.Pos> order = SpawnPrepPlan.order(10, -3, 2);
        assertEquals(new SpawnPrepPlan.Pos(10, -3), order.get(0));
        int previous = 0;
        Set<SpawnPrepPlan.Pos> seen = new HashSet<>();
        for (SpawnPrepPlan.Pos p : order) {
            int ring = Math.max(Math.abs(p.x() - 10), Math.abs(p.z() + 3));
            assertTrue(ring >= previous, "ring order broken at " + p);
            assertTrue(ring <= 2, "outside the square: " + p);
            assertTrue(seen.add(p), "duplicate " + p);
            previous = ring;
        }
        assertEquals(25, seen.size());
    }
}
