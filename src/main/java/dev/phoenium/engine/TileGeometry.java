package dev.phoenium.engine;

/**
 * S9-E1: tile, colour and claim arithmetic (pure; no game classes). A tile is a square of 2^shift chunks; its key
 * packs the tile coordinates. Tile (tx, tz) has colour (tx mod P) + P * (tz mod P) with P = 2r + 1, so two tiles of
 * one colour are at least P tiles apart on some axis and their claims (all tiles within Chebyshev distance r) are
 * disjoint.
 */
public final class TileGeometry {
    private TileGeometry() { }

    public static int tile(int chunkCoordinate, int shift) { return chunkCoordinate >> shift; }

    public static long key(int tileX, int tileZ) { return ((long) tileX << 32) | (tileZ & 0xFFFFFFFFL); }

    public static int keyX(long key) { return (int) (key >> 32); }

    public static int keyZ(long key) { return (int) key; }

    public static int colour(int tileX, int tileZ, int period) {
        return Math.floorMod(tileX, period) + period * Math.floorMod(tileZ, period);
    }

    /** Whether chunk (chunkX, chunkZ) lies in the claim of tile (tileX, tileZ). */
    public static boolean inClaim(int tileX, int tileZ, int chunkX, int chunkZ, int shift, int radius) {
        return Math.abs((chunkX >> shift) - tileX) <= radius && Math.abs((chunkZ >> shift) - tileZ) <= radius;
    }

    /** Deterministic, well-mixed seed of a tile's level random stream for one tick (SplitMix64 finaliser). */
    public static long seed(long worldSeed, long dimension, long gameTime, long tileKey) {
        return mix(mix(mix(worldSeed ^ dimension) ^ gameTime * 0x9E3779B97F4A7C15L) ^ tileKey);
    }

    static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
