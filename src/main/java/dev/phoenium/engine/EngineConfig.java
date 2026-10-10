package dev.phoenium.engine;

/**
 * S9-E1: settings of the colored-tile engine (design: .audit/stage9-20261009/design/TRACKB_TILE_ENGINE_DESIGN.md).
 * Read once, at mixin bootstrap, from system properties. Nothing here references a Minecraft class: the mixin config
 * plugins load this class before the game classes exist. With {@link #ENABLED} false no engine code runs and every
 * Phoenium mixin applies exactly as before.
 */
public final class EngineConfig {
    private EngineConfig() { }

    /** {@code -Dphoenium.engine.tiles=true}: engine mode (default off). */
    public static final boolean ENABLED = Boolean.getBoolean("phoenium.engine.tiles");
    /** Tile threads besides the server thread, which runs tiles too; 0 runs every tile on the server thread. */
    public static final int WORKERS = clamp(Integer.getInteger("phoenium.engine.workers", 3), 0, 64);
    /** A tile is 2^TILE_SHIFT chunks on a side. */
    public static final int TILE_SHIFT = clamp(Integer.getInteger("phoenium.engine.tileShift", 2), 0, 6);
    /** Every effect of a tile's work lies within CLAIM_RADIUS tiles (Chebyshev) of the tile: its claim. */
    public static final int CLAIM_RADIUS = clamp(Integer.getInteger("phoenium.engine.claimRadius", 1), 1, 8);
    /** Tiles of one colour are a multiple of PERIOD tiles apart on each axis, so their claims are disjoint. */
    public static final int PERIOD = 2 * CLAIM_RADIUS + 1;
    public static final int COLOURS = PERIOD * PERIOD;
    /** Traps (a load-miss chunk request, a block write outside the claim) throw instead of being counted. */
    public static final boolean STRICT = Boolean.getBoolean("phoenium.engine.strict");

    /** Blocks between a tile's edge and its claim's edge. */
    public static int claimReachBlocks() { return CLAIM_RADIUS << (TILE_SHIFT + 4); }

    public static String describe() {
        return "engine=tiles tileShift=" + TILE_SHIFT + " tileChunks=" + (1 << TILE_SHIFT) + " claimRadius=" + CLAIM_RADIUS
            + " colours=" + COLOURS + " reachBlocks=" + claimReachBlocks() + " strict=" + STRICT;
    }

    private static final java.util.concurrent.ConcurrentLinkedQueue<String> TRANSFORMS = new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** The engine mixin plugin records each class transform it made (shown on the start line). */
    public static void noteTransform(String note) { TRANSFORMS.add(note); }

    public static String transforms() { return TRANSFORMS.isEmpty() ? "none" : String.join(",", TRANSFORMS); }

    private static int clamp(int value, int low, int high) { return Math.max(low, Math.min(high, value)); }
}
