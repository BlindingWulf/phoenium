package dev.phoenium.engine;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** S9-E1: the colouring makes the claims of same-colour tiles disjoint, and tile streams are deterministic. */
class TileGeometryTest {
    @Test
    void sameColourTilesHaveDisjointClaims() {
        for (int radius = 1; radius <= 2; radius++) {
            int period = 2 * radius + 1;
            int shift = 2;
            for (int ax = -7; ax <= 7; ax++) for (int az = -7; az <= 7; az++)
                for (int bx = -7; bx <= 7; bx++) for (int bz = -7; bz <= 7; bz++) {
                    if (ax == bx && az == bz) continue;
                    if (TileGeometry.colour(ax, az, period) != TileGeometry.colour(bx, bz, period)) continue;
                    assertTrue(Math.abs(ax - bx) > 2 * radius || Math.abs(az - bz) > 2 * radius,
                        "tiles " + ax + "," + az + " and " + bx + "," + bz + " share colour with overlapping claims");
                    // spot-check the chunk-level claim test on the chunks between them
                    int lowX = (Math.min(ax, bx) - radius) << shift, highX = ((Math.max(ax, bx) + radius + 1) << shift) - 1;
                    int lowZ = (Math.min(az, bz) - radius) << shift, highZ = ((Math.max(az, bz) + radius + 1) << shift) - 1;
                    for (int cx = lowX; cx <= highX; cx += 3) for (int cz = lowZ; cz <= highZ; cz += 3)
                        assertFalse(TileGeometry.inClaim(ax, az, cx, cz, shift, radius)
                            && TileGeometry.inClaim(bx, bz, cx, cz, shift, radius), "chunk " + cx + "," + cz + " in both claims");
                }
        }
    }

    @Test
    void everyBlockOfPeriodTilesHasEachColourOnce() {
        int period = 3;
        for (int originX = -4; originX <= 4; originX++) for (int originZ = -4; originZ <= 4; originZ++) {
            Set<Integer> colours = new HashSet<>();
            for (int dx = 0; dx < period; dx++) for (int dz = 0; dz < period; dz++) {
                int colour = TileGeometry.colour(originX + dx, originZ + dz, period);
                assertTrue(colour >= 0 && colour < period * period);
                assertTrue(colours.add(colour));
            }
        }
    }

    @Test
    void tilesFloorNegativeChunksAndKeysRoundTrip() {
        assertEquals(-1, TileGeometry.tile(-1, 2));
        assertEquals(-1, TileGeometry.tile(-4, 2));
        assertEquals(-2, TileGeometry.tile(-5, 2));
        assertEquals(0, TileGeometry.tile(3, 2));
        for (int x : new int[] {Integer.MIN_VALUE, -70_000, -1, 0, 1, 70_000, Integer.MAX_VALUE})
            for (int z : new int[] {Integer.MIN_VALUE, -3, 0, 5, Integer.MAX_VALUE}) {
                long key = TileGeometry.key(x, z);
                assertEquals(x, TileGeometry.keyX(key));
                assertEquals(z, TileGeometry.keyZ(key));
            }
    }

    @Test
    void streamSeedsAreDeterministicAndDistinct() {
        long seed = TileGeometry.seed(42L, 7L, 1000L, TileGeometry.key(3, -2));
        assertEquals(seed, TileGeometry.seed(42L, 7L, 1000L, TileGeometry.key(3, -2)));
        Set<Long> seen = new HashSet<>();
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++)
            for (long time = 0; time < 4; time++) assertTrue(seen.add(TileGeometry.seed(42L, 7L, time, TileGeometry.key(x, z))));
        assertNotEquals(seed, TileGeometry.seed(43L, 7L, 1000L, TileGeometry.key(3, -2)));
        assertNotEquals(seed, TileGeometry.seed(42L, 8L, 1000L, TileGeometry.key(3, -2)));
    }
}
