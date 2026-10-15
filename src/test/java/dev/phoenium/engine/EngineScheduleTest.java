package dev.phoenium.engine;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * S9-E19 (D43): a phase's width is its total tile weight over its longest dependency chain (dag) or over the sum of
 * each colour's largest tile (colour by colour). A clustered phase (every claim touches every other: the phone's
 * players at spawn) is a chain of width 1 and runs on the server thread alone; spread tiles keep the tile threads.
 */
class EngineScheduleTest {
    private static final int SPAN = 2 * EngineConfig.CLAIM_RADIUS;

    /** Tiles at the given tile positions, one entity's weight each unless {@code weights} says otherwise. */
    @SuppressWarnings("unchecked")
    private static ArrayList<TileWork>[] colours(int[][] positions, int[] weights) {
        ArrayList<TileWork>[] colours = new ArrayList[EngineConfig.COLOURS];
        for (int i = 0; i < colours.length; i++) colours[i] = new ArrayList<>();
        List<TileWork> all = new ArrayList<>();
        for (int i = 0; i < positions.length; i++) {
            int x = positions[i][0];
            int z = positions[i][1];
            TileWork tile = new TileWork();
            tile.reset(TileGeometry.key(x, z), x, z, TileGeometry.colour(x, z, EngineConfig.PERIOD));
            int weight = weights == null ? 1 : weights[i];
            for (int k = 0; k < weight; k++) tile.tickChunks.add(null);
            colours[tile.colour].add(tile);
            all.add(tile);
        }
        // successors as TileEngine.runDag builds them: a touching neighbour of a higher colour waits
        for (TileWork tile : all) {
            for (TileWork other : all) {
                if (other != tile && Math.abs(other.tileX - tile.tileX) <= SPAN && Math.abs(other.tileZ - tile.tileZ) <= SPAN
                        && other.colour > tile.colour)
                    tile.addSuccessor(other);
            }
        }
        return colours;
    }

    private static int[][] block(int x0, int z0, int side) {
        int[][] out = new int[side * side][];
        for (int z = 0; z < side; z++) for (int x = 0; x < side; x++) out[z * side + x] = new int[] {x0 + x, z0 + z};
        return out;
    }

    private static int[][] concat(int[][] a, int[][] b) {
        int[][] out = new int[a.length + b.length][];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    @Test
    void aClusterOfTouchingTilesIsAChainOfWidthOne() {
        // 3x3 tiles: every pair lies within 2 tiles, so each waits for every lower colour (the phone's still-8 phase)
        assertEquals(1.0, EngineSchedule.dagWidth(colours(block(0, 0, 3), null)), 1e-9);
    }

    @Test
    void twoFarClustersDoubleTheWidth() {
        assertEquals(2.0, EngineSchedule.dagWidth(colours(concat(block(0, 0, 3), block(30, 0, 3)), null)), 1e-9);
    }

    @Test
    void isolatedTilesAreAsWideAsTheirCount() {
        int[][] spread = {{0, 0}, {9, 0}, {18, 0}, {0, 9}, {9, 9}};
        assertEquals(5.0, EngineSchedule.dagWidth(colours(spread, null)), 1e-9);
    }

    @Test
    void theHeaviestChainBoundsTheWidth() {
        // two isolated tiles, weights 3 and 1: the longest chain is the 3, total 4
        int[][] spread = {{0, 0}, {9, 0}};
        assertEquals(4.0 / 3.0, EngineSchedule.dagWidth(colours(spread, new int[] {3, 1})), 1e-9);
    }

    @Test
    void anEmptyTileCountsAsOne() {
        int[][] spread = {{0, 0}, {9, 0}};
        assertEquals(2.0, EngineSchedule.dagWidth(colours(spread, new int[] {0, 0})), 1e-9);
    }

    @Test
    void theDagWidthLeavesTheScheduleUntouched() {
        ArrayList<TileWork>[] colours = colours(block(0, 0, 3), null);
        EngineSchedule.dagWidth(colours);
        for (ArrayList<TileWork> work : colours) {
            for (TileWork tile : work) {
                assertEquals(0L, tile.earliest, "critical-path field is the measured one's");
                assertEquals(tile.predecessors, countPredecessors(colours, tile));
            }
        }
    }

    private static int countPredecessors(ArrayList<TileWork>[] colours, TileWork target) {
        int n = 0;
        for (ArrayList<TileWork> work : colours)
            for (TileWork tile : work)
                for (int i = 0; i < tile.successorCount; i++) if (tile.successors[i] == target) n++;
        return n;
    }

    @Test
    void colourWidthIsTotalOverEachColoursLargest() {
        // block(0,0,3): nine colours with one tile each: colour by colour is serial anyway
        assertEquals(1.0, EngineSchedule.colourWidth(colours(block(0, 0, 3), null)), 1e-9);
        // four tiles of one colour (3 apart on each axis), equal weights: width 4
        int[][] sameColour = {{0, 0}, {3, 0}, {0, 3}, {3, 3}};
        assertEquals(4.0, EngineSchedule.colourWidth(colours(sameColour, null)), 1e-9);
        // same, one tile three times heavier: total 6 over largest 3
        assertEquals(2.0, EngineSchedule.colourWidth(colours(sameColour, new int[] {3, 1, 1, 1})), 1e-9);
    }

    @Test
    void serialBelowTheMinimumWidthOrForASingleTile() {
        assertTrue(EngineSchedule.serial(1.0, 9, 1.5));
        assertTrue(EngineSchedule.serial(1.49, 9, 1.5));
        assertFalse(EngineSchedule.serial(1.5, 9, 1.5));
        assertFalse(EngineSchedule.serial(4.9, 40, 1.5));
        assertTrue(EngineSchedule.serial(1.0, 1, 1.5), "one tile never needs the tile threads");
    }

    @Test
    void minimumWidthPropertyParsesSafely() {
        assertEquals(1.5, EngineConfig.minWidth(null), 1e-9);
        assertEquals(0.0, EngineConfig.minWidth("0"), 1e-9);
        assertEquals(2.5, EngineConfig.minWidth(" 2.5 "), 1e-9);
        assertEquals(0.0, EngineConfig.minWidth("-3"), 1e-9);
        assertEquals(64.0, EngineConfig.minWidth("1e9"), 1e-9);
        assertEquals(1.5, EngineConfig.minWidth("NaN"), 1e-9);
        assertEquals(1.5, EngineConfig.minWidth("wide"), 1e-9);
    }

    @Test
    void minimumWidthZeroKeepsD42Behaviour() {
        assertFalse(EngineSchedule.serial(1.0, 9, 0.0));
        assertFalse(EngineSchedule.serial(1.0, 1, 0.0), "D42 published a job even for one tile");
    }
}
