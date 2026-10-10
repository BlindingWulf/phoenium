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
    /**
     * S9-E8 {@code phoenium.engine.protection.check}: ticks between checks of every protection membership (A3, projectile
     * cap, pearl cap) against its object (stale tile/world, orphan); 0 = never. Only with a protection enabled.
     */
    public static final int PROTECTION_CHECK = clamp(Integer.getInteger("phoenium.engine.protection.check", 20), 0, 1 << 20);
    /**
     * S9-E9 {@code phoenium.engine.sendFlush} (default true): a packet a tile thread sends while the server suspends
     * flushing (MinecraftServer.tickChildren) waits for the end-of-tick flush, as the server thread's do; vanilla flushes
     * every packet sent from another thread at once (a socket write per packet).
     */
    public static final boolean SEND_FLUSH = !"false".equalsIgnoreCase(System.getProperty("phoenium.engine.sendFlush"));
    /**
     * S9-E9 {@code phoenium.engine.trackerSend} (default true): ChunkMap.tick() collects its loop's
     * ServerEntity.sendChanges calls and runs them on the tile threads after the loop, before the moved-players pass
     * (pairing stays serial, in vanilla order; item frames, which tick shared map data, run in place). Their packets are
     * held per thread and delivered per connection, one thread per connection.
     */
    public static final boolean TRACKER_SEND = !"false".equalsIgnoreCase(System.getProperty("phoenium.engine.trackerSend"));
    /** S9-E9 {@code phoenium.engine.trackerSend.min}: fewer collected calls than this run on the server thread. */
    public static final int TRACKER_SEND_MIN = clamp(Integer.getInteger("phoenium.engine.trackerSend.min", 256), 1, 1 << 24);
    /**
     * S9-E9 {@code phoenium.engine.spawnSlices} (default true; needs {@link #SPAWN_MEMO}): NaturalSpawner.createState
     * counts contiguous slices of the entity list on the tile threads, each with its own memo and local caps, and merges
     * them in slice order (charges concatenated, so the potential sums are vanilla's; counts summed).
     */
    public static final boolean SPAWN_SLICES = SPAWN_MEMO && !"false".equalsIgnoreCase(System.getProperty("phoenium.engine.spawnSlices"));
    /** S9-E9 {@code phoenium.engine.spawnSlices.min}: entities per slice at least; fewer entities than this count serially. */
    public static final int SPAWN_SLICE_MIN = clamp(Integer.getInteger("phoenium.engine.spawnSlices.min", 512), 1, 1 << 24);

    /**
     * S9-E11 {@code phoenium.engine.spins} (default 8192, D29's): rounds of Thread.onSpinWait (about 0.2-0.5 ms at 8192)
     * before a waiting thread parks: a tile thread between jobs or without a ready tile, the server thread waiting for a
     * job's last tile.
     */
    public static final int SPINS = clamp(Integer.getInteger("phoenium.engine.spins", 8_192), 0, 1 << 24);
    /**
     * S9-E11 {@code phoenium.engine.idleParkUs} (default 1000, D29's): a tile thread without a job parks this long at a
     * time. A published job unparks every tile thread, so a longer park only removes the idle polling.
     */
    public static final int IDLE_PARK_US = clamp(Integer.getInteger("phoenium.engine.idleParkUs", 1_000), 20, 1_000_000);
    /**
     * S9-E11 {@code phoenium.engine.dagWake} (default false, D29's polling): in a dag job a tile thread with no ready tile
     * parks up to {@link #DAG_PARK_US} and is unparked when a finished tile readies another or the job ends, instead of
     * polling every 20 us until the phase ends. The server thread keeps polling (it serves the tiles' requests).
     */
    public static final boolean DAG_WAKE = Boolean.getBoolean("phoenium.engine.dagWake");
    /** S9-E11 {@code phoenium.engine.dagParkUs} (default 1000): with dagWake, the longest single park of a waiting tile thread. */
    public static final int DAG_PARK_US = clamp(Integer.getInteger("phoenium.engine.dagParkUs", 1_000), 20, 1_000_000);

    /**
     * S9-E12 {@code phoenium.engine.autosaveSpread} (default false): the periodic autosave (every 6000 ticks, players
     * online) saves the player files and level data in its tick as vanilla does, and its chunk saves and entity stores over
     * the following ticks ({@link EngineAutosave}). /save-all and the shutdown save stay synchronous.
     */
    public static final boolean AUTOSAVE_SPREAD = Boolean.getBoolean("phoenium.engine.autosaveSpread");
    /** S9-E12 {@code phoenium.engine.autosaveSpread.entities} (default true): the entity stores spread too; false stores them in the autosave tick. */
    public static final boolean AUTOSAVE_SPREAD_ENTITIES = AUTOSAVE_SPREAD
        && !"false".equalsIgnoreCase(System.getProperty("phoenium.engine.autosaveSpread.entities"));
    /**
     * S9-E14 {@code phoenium.engine.autosaveSpread.players} (default true): the player saves (player data, stats,
     * advancements) spread too, after the autosave tick; false saves them in the autosave tick.
     */
    public static final boolean AUTOSAVE_SPREAD_PLAYERS = AUTOSAVE_SPREAD
        && !"false".equalsIgnoreCase(System.getProperty("phoenium.engine.autosaveSpread.players"));
    /** S9-E12 {@code phoenium.engine.autosaveSpread.chunks} (default 64): saves per level queue and tick at most. */
    public static final int AUTOSAVE_CHUNKS = clamp(Integer.getInteger("phoenium.engine.autosaveSpread.chunks", 64), 1, 1 << 20);
    /** S9-E12 {@code phoenium.engine.autosaveSpread.budgetUs} (default 2000): time per level queue and tick at most (one key always runs). */
    public static final int AUTOSAVE_BUDGET_US = clamp(Integer.getInteger("phoenium.engine.autosaveSpread.budgetUs", 2_000), 50, 1_000_000);

    /**
     * S9-E13 R-C2 {@code phoenium.engine.moveSplit} (default false; needs {@link #TRACKER_INDEX}): a move packet's
     * ChunkMap.move(player) waits for the end of the frame's queued packets; there the deferred moves' tracker checks are
     * evaluated on the tile threads and applied on the server thread in order ({@link EngineMoveSplit}).
     */
    public static final boolean MOVE_SPLIT = TRACKER_INDEX && Boolean.getBoolean("phoenium.engine.moveSplit");
    /** S9-E13 {@code phoenium.engine.moveSplit.min} (default 4): fewer deferred moves than this run serially (vanilla's move, in order). */
    public static final int MOVE_SPLIT_MIN = clamp(Integer.getInteger("phoenium.engine.moveSplit.min", 4), 1, 1 << 20);
    /** S9-E13 {@code phoenium.engine.moveSplit.check}: every N-th parallel flush also evaluates each move serially and compares; 0 = never. */
    public static final int MOVE_SPLIT_CHECK = clamp(Integer.getInteger("phoenium.engine.moveSplit.check", 0), 0, 1 << 20);

    /** Blocks between a tile's edge and its claim's edge. */
    public static int claimReachBlocks() { return CLAIM_RADIUS << (TILE_SHIFT + 4); }

    public static String describe() {
        return "engine=tiles tileShift=" + TILE_SHIFT + " tileChunks=" + (1 << TILE_SHIFT) + " claimRadius=" + CLAIM_RADIUS
            + " colours=" + COLOURS + " reachBlocks=" + claimReachBlocks() + " strict=" + STRICT + " chunks=" + CHUNKS
            + " schedule=" + (DAG ? "dag" : "colours") + " services=" + SERVICES + " tracker=" + TRACKER + " trackerIndex=" + TRACKER_INDEX
            + " trackerIndexCheck=" + TRACKER_INDEX_CHECK + " trackerIndexFull=" + TRACKER_INDEX_FULL
            + " spawnMemo=" + SPAWN_MEMO + " spawnMemoCheck=" + SPAWN_MEMO_CHECK + " protectionCheck=" + PROTECTION_CHECK
            + " sendFlush=" + SEND_FLUSH + " trackerSend=" + TRACKER_SEND + " trackerSendMin=" + TRACKER_SEND_MIN
            + " spawnSlices=" + SPAWN_SLICES + " spawnSlicesMin=" + SPAWN_SLICE_MIN
            + " spins=" + SPINS + " idleParkUs=" + IDLE_PARK_US + " dagWake=" + DAG_WAKE + " dagParkUs=" + DAG_PARK_US
            + " autosaveSpread=" + AUTOSAVE_SPREAD + " autosaveSpreadEntities=" + AUTOSAVE_SPREAD_ENTITIES
            + " autosaveSpreadPlayers=" + AUTOSAVE_SPREAD_PLAYERS
            + " autosaveSpreadChunks=" + AUTOSAVE_CHUNKS + " autosaveSpreadBudgetUs=" + AUTOSAVE_BUDGET_US
            + " moveSplit=" + MOVE_SPLIT + " moveSplitMin=" + MOVE_SPLIT_MIN + " moveSplitCheck=" + MOVE_SPLIT_CHECK;
    }

    private static final java.util.concurrent.ConcurrentLinkedQueue<String> TRANSFORMS = new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** The engine mixin plugin records each class transform it made (shown on the start line). */
    public static void noteTransform(String note) { TRANSFORMS.add(note); }

    public static String transforms() { return TRANSFORMS.isEmpty() ? "none" : String.join(",", TRANSFORMS); }

    private static int clamp(int value, int low, int high) { return Math.max(low, Math.min(high, value)); }
}
