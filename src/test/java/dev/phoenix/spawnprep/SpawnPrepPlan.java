package dev.phoenix.spawnprep;

import java.util.ArrayList;
import java.util.List;

/** Which chunks spawn preparation covers: a square around the spawn chunk, nearest rings first. */
public final class SpawnPrepPlan {
    private SpawnPrepPlan() {
    }

    public record Pos(int x, int z) {
    }

    /** Blocks → whole chunks, rounded up, so the configured radius is always covered. */
    public static int chunkRadius(int radiusBlocks) {
        return (Math.max(0, radiusBlocks) + 15) / 16;
    }

    /** Ring 0 (the spawn chunk), then ring 1, … ring r; an interrupted run has still prepared the area players see first. */
    public static List<Pos> order(int centerX, int centerZ, int chunkRadius) {
        int side = 2 * chunkRadius + 1;
        List<Pos> out = new ArrayList<>(side * side);
        out.add(new Pos(centerX, centerZ));
        for (int ring = 1; ring <= chunkRadius; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                out.add(new Pos(centerX + dx, centerZ - ring));
                out.add(new Pos(centerX + dx, centerZ + ring));
            }
            for (int dz = -ring + 1; dz <= ring - 1; dz++) {
                out.add(new Pos(centerX - ring, centerZ + dz));
                out.add(new Pos(centerX + ring, centerZ + dz));
            }
        }
        return out;
    }
}
