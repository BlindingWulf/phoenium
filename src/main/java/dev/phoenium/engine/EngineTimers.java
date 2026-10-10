package dev.phoenium.engine;

/**
 * S9-E8 (engine mode): wall time of the main sections of each frame on the server thread (System.nanoTime around each
 * call; nested sections are inclusive). A frame is MinecraftServer.processPacketsAndTick: the queued packets, then
 * tickServer (autosave, tickChildren: the level ticks, then connections, player list, chunk sending). In each level tick:
 * scheduled block and fluid ticks, raids, the chunk source (distance manager updates; tickChunks = spawn state + chunk
 * phase + the rest; the tracker, ChunkMap.tick(); unloads and saves, ChunkMap.tick(haveTime)), block events, the
 * entities (entity phase, serial entities, applies), block entities and the entity manager. JFR's execution sampler takes
 * a few samples per period across all threads, so with the tile threads busy it under-samples the server thread; these
 * are the shares of the serial work. Server thread only; the counters are cumulative (the harness diffs two marks).
 */
public final class EngineTimers {
    private EngineTimers() { }

    public static final int PACKETS = 0;
    public static final int SERVER_TICK = 1;
    public static final int AUTOSAVE = 2;
    public static final int CHILDREN = 3;
    public static final int LEVELS = 4;
    public static final int LEVEL_TICKS = 5;
    public static final int RAIDS = 6;
    public static final int CHUNK_SOURCE = 7;
    public static final int DISTANCE = 8;
    public static final int TICK_CHUNKS = 9;
    public static final int SPAWN_STATE = 10;
    public static final int TRACKER = 11;
    public static final int CHUNK_UNLOAD = 12;
    public static final int BLOCK_EVENTS = 13;
    public static final int ENTITIES = 14;
    public static final int BLOCK_ENTITIES = 15;
    public static final int ENTITY_MANAGER = 16;
    public static final int CONNECTIONS = 17;
    public static final int PLAYER_LIST = 18;
    public static final int CHUNK_SEND = 19;
    /**
     * S9-E9: parts of the tracker (ChunkMap.tick()): every player's chunk tracking; the loop's collected sendChanges
     * calls (parallel, with their delivery); the moved-players pass. The rest of the tracker is the loop itself (pairing
     * on section change, collection).
     */
    public static final int TRACKER_CHUNKS = 20;
    public static final int TRACKER_SEND = 21;
    public static final int TRACKER_MOVED = 22;
    private static final String[] NAMES = {"Packets", "ServerTick", "Autosave", "Children", "Levels", "LevelTicks", "Raids",
        "ChunkSource", "Distance", "TickChunks", "SpawnState", "Tracker", "ChunkUnload", "BlockEvents", "Entities",
        "BlockEntities", "EntityManager", "Connections", "PlayerList", "ChunkSend", "TrackerChunks", "TrackerSend", "TrackerMoved"};
    private static final long[] NANOS = new long[NAMES.length];
    private static long frames;

    public static long start() { return System.nanoTime(); }

    /** Adds the time since {@code start} to a section (server thread; any other thread is ignored). */
    public static void add(int section, long start) {
        if (TileEngine.onServerThread()) NANOS[section] += System.nanoTime() - start;
    }

    /** One more frame (processPacketsAndTick) timed. */
    public static void frame() {
        if (TileEngine.onServerThread()) frames++;
    }

    static String counters() {
        StringBuilder out = new StringBuilder("timeFrames=").append(frames);
        for (int i = 0; i < NAMES.length; i++) out.append(" time").append(NAMES[i]).append("Us=").append(NANOS[i] / 1000);
        return out.toString();
    }
}
