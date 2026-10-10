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
    /**
     * S9-E3 {@code phoenium.engine.chunks} (default true): the chunk phase of ServerChunkCache.tickChunks (inhabited
     * time, thunder, natural spawning, precipitation and random block ticks) runs in tiles too.
     */
    public static final boolean CHUNKS = !"false".equalsIgnoreCase(System.getProperty("phoenium.engine.chunks"));
    /**
     * S9-E3 {@code phoenium.engine.schedule}: {@code colours} (default) runs one colour at a time and applies its deferred
     * effects before the next; {@code dag} starts a tile as soon as its lower-colour neighbours (claims that touch) are
     * done and applies every deferred effect after the phase, in the same colour-then-tile order.
     */
    public static final boolean DAG = "dag".equalsIgnoreCase(System.getProperty("phoenium.engine.schedule", "colours"));
    /**
     * S9-E3 {@code phoenium.engine.services} (default true): a tile that must load a chunk or a POI column, or write
     * outside its claim, pauses while the server thread does it with no other tile running (what vanilla does inline);
     * false keeps the S9-E1 traps (an empty answer for a load, the write done in place), counted and logged.
     */
    public static final boolean SERVICES = !"false".equalsIgnoreCase(System.getProperty("phoenium.engine.services"));
    /**
     * S9-E4 {@code phoenium.engine.tracker} (default true): ChunkMap's tracker loops skip each check of a tracked entity
     * against a player that cannot change anything (the entity is beyond the player's view distance + 1 chunks and the
     * player does not see it); false runs every check as vanilla does.
     */
    public static final boolean TRACKER = !"false".equalsIgnoreCase(System.getProperty("phoenium.engine.tracker"));
    /**
     * S9-E6 {@code phoenium.engine.trackerIndex} (default true; needs {@link #TRACKER}): ChunkMap.move and the
     * moved-players pass of ChunkMap.tick visit only the tracked entities the tracker filter passes (found in a tile
     * index of tracked entities and a reverse index of what each player sees) instead of every tracked entity.
     */
    public static final boolean TRACKER_INDEX = TRACKER && !"false".equalsIgnoreCase(System.getProperty("phoenium.engine.trackerIndex"));
    /** S9-E6 {@code phoenium.engine.trackerIndex.check}: ticks between comparisons of the index with the entity map; 0 = none. */
    public static final int TRACKER_INDEX_CHECK = clamp(Integer.getInteger("phoenium.engine.trackerIndex.check", 20), 0, 1 << 20);
    /** S9-E6 {@code phoenium.engine.trackerIndex.full}: every N-th comparison also checks the reverse index member by member. */
    public static final int TRACKER_INDEX_FULL = clamp(Integer.getInteger("phoenium.engine.trackerIndex.full", 10), 1, 1 << 20);
    /**
     * S9-E7 {@code phoenium.engine.spawnMemo} (default true): NaturalSpawner.createState looks each chunk up once per call,
     * with the count maps its mobs increment, instead of once per counted entity; false runs it as vanilla does.
     */
    public static final boolean SPAWN_MEMO = !"false".equalsIgnoreCase(System.getProperty("phoenium.engine.spawnMemo"));
    /** S9-E7 {@code phoenium.engine.spawnMemo.check}: every N-th spawn state of a level is also built the vanilla way and compared; 0 = never. */
    public static final int SPAWN_MEMO_CHECK = clamp(Integer.getInteger("phoenium.engine.spawnMemo.check", 400), 0, 1 << 20);

    /** Blocks between a tile's edge and its claim's edge. */
    public static int claimReachBlocks() { return CLAIM_RADIUS << (TILE_SHIFT + 4); }

    public static String describe() {
        return "engine=tiles tileShift=" + TILE_SHIFT + " tileChunks=" + (1 << TILE_SHIFT) + " claimRadius=" + CLAIM_RADIUS
            + " colours=" + COLOURS + " reachBlocks=" + claimReachBlocks() + " strict=" + STRICT + " chunks=" + CHUNKS
            + " schedule=" + (DAG ? "dag" : "colours") + " services=" + SERVICES + " tracker=" + TRACKER + " trackerIndex=" + TRACKER_INDEX
            + " trackerIndexCheck=" + TRACKER_INDEX_CHECK + " trackerIndexFull=" + TRACKER_INDEX_FULL
            + " spawnMemo=" + SPAWN_MEMO + " spawnMemoCheck=" + SPAWN_MEMO_CHECK;
    }

    private static final java.util.concurrent.ConcurrentLinkedQueue<String> TRANSFORMS = new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** The engine mixin plugin records each class transform it made (shown on the start line). */
    public static void noteTransform(String note) { TRANSFORMS.add(note); }

    public static String transforms() { return TRANSFORMS.isEmpty() ? "none" : String.join(",", TRANSFORMS); }

    private static int clamp(int value, int low, int high) { return Math.max(low, Math.min(high, value)); }
}
