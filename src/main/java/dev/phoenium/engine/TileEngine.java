package dev.phoenium.engine;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.phoenium.engine.mixin.EngineChunkCacheAccess;
import dev.phoenium.engine.mixin.EngineLevelAccess;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ClassInstanceMultiMap;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.animal.dolphin.Dolphin;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.entity.vehicle.minecart.MinecartCommandBlock;
import net.minecraft.world.entity.vehicle.minecart.MinecartHopper;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.entity.EntityTickList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * S9-E1: the colored-tile engine (design: .audit/stage9-20261009/design/TRACKB_TILE_ENGINE_DESIGN.md §7). In engine
 * mode the entity loop of ServerLevel.tick runs here: entities are sorted into tiles (or the serial set), each colour's
 * tiles run on the tile threads and the server thread, the server thread applies each colour's deferred effects in
 * tile order, and the serial set runs last on the server thread as vanilla code.
 *
 * <p>S9-E3: the chunk phase of ServerChunkCache.tickChunks runs here too ({@link EngineConfig#CHUNKS}): its loops call
 * tickSpawningChunk and tickChunk as before, the calls are recorded per tile instead of run, and the tiles run them
 * colour by colour before the custom spawners. With {@link EngineConfig#DAG} the entity phase starts each tile as soon as
 * its lower-colour neighbours are done and applies the deferred effects after the phase.
 *
 * <p>S9-E9: serial work outside the phases that splits into independent items runs as a parallel-for on the same threads
 * ({@link #runParallel}): the tracker's sendChanges calls ({@link EngineTrackerSends}, their packets held per thread and
 * delivered per connection) and the spawn state's entity count in slices ({@link EngineSpawnMemo}).
 */
public final class TileEngine {
    private TileEngine() { }

    private static final Logger LOGGER = LoggerFactory.getLogger("Phoenium");
    /** Mixed into the chunk phase's tile seeds so a tile's chunk and entity streams of one tick differ. */
    private static final long CHUNK_SALT = 0x2545F4914F6CDD1DL;

    private static final TileContext MAIN = new TileContext();
    private static Thread serverThread;
    private static EngineWorkerPool pool;
    private static volatile boolean running;
    private static boolean colourRunning;
    private static final IdentityHashMap<ServerLevel, LevelPhase> PHASES = new IdentityHashMap<>();
    private static final IdentityHashMap<ServerLevel, LevelPhase> CHUNK_PHASES = new IdentityHashMap<>();
    /** The chunk phase recording its level's chunk calls, between tickChunks' head and the run (server thread). */
    private static LevelPhase collecting;
    private static long phaseSequence;
    private static long colourSequence;
    private static final PhaseStats ENTITY = new PhaseStats();
    private static final PhaseStats CHUNK = new PhaseStats();
    private static long parallelEntities;
    private static long serialEntities;
    private static long spawnChunks;
    private static long tickChunks;
    private static long chunkFallbacks;
    private static long serialFallbacks;
    private static final LongAdder LOAD_TRAPS = new LongAdder();
    private static final LongAdder WRITE_TRAPS = new LongAdder();
    private static final LongAdder STORAGE_TRAPS = new LongAdder();
    private static final LongAdder LOAD_SERVICES = new LongAdder();
    private static final LongAdder STORAGE_SERVICES = new LongAdder();
    private static final LongAdder WRITE_SERVICES = new LongAdder();
    private static final LongAdder VILLAGE_SERVICES = new LongAdder();
    private static final LongAdder STRUCTURE_SERVICES = new LongAdder();
    private static long services;
    private static long serviceNanos;
    private static final AtomicInteger TRAP_LOGS = new AtomicInteger();
    /** S9-E9: parallel-for jobs run; every thread's outbox; the delivery's connections (server thread). */
    private static long parallelFors;
    private static EngineOutbox[] outboxes;
    private static final ArrayList<Object> HELD_TARGETS = new ArrayList<>();
    private static final IdentityHashMap<Object, Boolean> HELD_SEEN = new IdentityHashMap<>();

    /** Per phase kind: counts and wall/tile times (server thread). */
    private static final class PhaseStats {
        long phases;
        long colourRuns;
        long parallelRuns;
        long tiles;
        long maxColourTiles;
        long deferredApplied;
        long phaseNanos;
        long tileNanos;
        long applyNanos;
        long serialNanos;
        /** S9-E8: per phase, the longest dependency chain of measured tile times (dag) or the sum of each colour's largest tile. */
        long criticalNanos;
        /** S9-E8: per phase, the largest single tile time. */
        long largestNanos;

        String describe(String prefix) {
            return prefix + "Phases=" + phases + " " + prefix + "ColourRuns=" + colourRuns + " " + prefix + "ParallelRuns=" + parallelRuns
                + " " + prefix + "Tiles=" + tiles + " " + prefix + "MaxColourTiles=" + maxColourTiles + " " + prefix + "Deferred=" + deferredApplied
                + " " + prefix + "PhaseUs=" + phaseNanos / 1000 + " " + prefix + "TileUs=" + tileNanos / 1000 + " " + prefix + "ApplyUs="
                + applyNanos / 1000 + " " + prefix + "SerialUs=" + serialNanos / 1000 + " " + prefix + "CriticalUs=" + criticalNanos / 1000
                + " " + prefix + "LargestUs=" + largestNanos / 1000;
        }
    }

    /** True on the server thread of a running engine. */
    public static boolean onServerThread() { return Thread.currentThread() == serverThread; }

    /** The context of the tile this thread is running, or null (also for every thread outside engine mode). */
    public static TileContext current() {
        Thread thread = Thread.currentThread();
        TileContext context;
        if (thread == serverThread) context = MAIN;
        else if (EngineWorkerPool.isWorker(thread)) context = EngineWorkerPool.contextOf(thread);
        else return null;
        return context.tile != null ? context : null;
    }

    /** Engine mode entry, from the mod initializer: starts with the server and stops with it. */
    public static void install() {
        ServerLifecycleEvents.SERVER_STARTED.register(TileEngine::start);
        ServerLifecycleEvents.SERVER_STOPPING.register(TileEngine::stop);
        EngineProtections.install();
        LOGGER.info("PHOENIUM_STAGE2 entrypoint registered once; engine mode ({}) awaits server start.", EngineConfig.describe());
    }

    private static void start(MinecraftServer server) {
        // load (and so transform) the section map class now, so its transform shows on the start line
        String sectionMap = ClassInstanceMultiMap.class.getSimpleName();
        serverThread = server.getRunningThread();
        pool = EngineWorkerPool.isWorker(serverThread) || EngineConfig.WORKERS == 0 ? null
            : new EngineWorkerPool(EngineConfig.WORKERS, TileEngine::runService);
        outboxes = null;
        running = true;
        LOGGER.info("PHOENIUM_STAGE2 started lifecycle=RUNNING scheduler=ENGINE workers={} mode=engine-tiles {} threads={} parallel={} transforms={} loaded={}",
            EngineConfig.WORKERS, EngineConfig.describe(), EngineConfig.WORKERS + 1, pool == null ? "OFF" : "TILES",
            EngineConfig.transforms(), sectionMap);
    }

    private static void stop(MinecraftServer server) {
        running = false;
        if (pool != null) pool.stop();
        outboxes = null;
        EngineProtections.close();
        LOGGER.info("PHOENIUM_STAGE2 stopped lifecycle=STOPPED mode=engine-tiles {}", counters());
    }

    /** The engine's state and counters, for the test diagnostics command (server thread). */
    public static String diagnostics() {
        return "mode=engine-tiles lifecycle=" + (running ? "RUNNING" : "STOPPED") + " workers=" + EngineConfig.WORKERS
            + " parallel=" + (pool == null ? "OFF" : "TILES") + " " + counters();
    }

    public static String counters() {
        return ENTITY.describe("entity") + " parallelEntities=" + parallelEntities + " serialEntities=" + serialEntities
            + " " + CHUNK.describe("chunk") + " spawnChunks=" + spawnChunks + " tickChunks=" + tickChunks + " chunkFallbacks=" + chunkFallbacks
            + " serialFallbacks=" + serialFallbacks + " loadServices=" + LOAD_SERVICES.sum() + " storageServices=" + STORAGE_SERVICES.sum()
            + " writeServices=" + WRITE_SERVICES.sum() + " villageServices=" + VILLAGE_SERVICES.sum()
            + " structureServices=" + STRUCTURE_SERVICES.sum() + " services=" + services + " serviceUs=" + serviceNanos / 1000
            + " loadTraps=" + LOAD_TRAPS.sum() + " writeTraps=" + WRITE_TRAPS.sum() + " storageTraps=" + STORAGE_TRAPS.sum()
            + " " + EngineTracker.counters() + " " + EngineSpawnMemo.counters() + " spawnCapOvershoot=" + EngineSpawnOverlay.overshoot()
            + " parallelFors=" + parallelFors + " " + EngineTrackerSends.counters() + " " + EngineAutosave.counters()
            + " " + EngineMoveSplit.counters()
            + " " + dev.phoenium.chunk.C2meTaskRelease.counters()
            + " " + dev.phoenium.chunk.FlowSchedQueuePurge.counters()
            + " " + EngineProtections.describe()
            + " " + EngineTimers.counters() + " transforms=" + EngineConfig.transforms();
    }

    /** The wrapped EntityTickList.forEach of ServerLevel.tick. */
    public static void tickEntities(ServerLevel level, EntityTickList list, Consumer<Entity> body, Operation<Void> original) {
        if (!running || Thread.currentThread() != serverThread || MAIN.tile != null) {
            original.call(list, body);
            return;
        }
        LevelPhase phase = PHASES.computeIfAbsent(level, key -> new LevelPhase(key, false));
        original.call(list, (Consumer<Entity>) phase::classify);
        phase.run((tile, context) -> {
            ArrayList<Entity> entities = tile.entities;
            for (int i = 0, n = entities.size(); i < n; i++) body.accept(entities.get(i));
        }, body, EngineConfig.DAG);
    }

    // ---- S9-E3 chunk phase ----

    /** Head of ServerChunkCache.tickChunks(ProfilerFiller, long): this tick's chunk calls are recorded from here on. */
    public static void beginChunks(ServerChunkCache cache) {
        if (collecting != null) {           // a previous recording that never ran (its tickChunks threw)
            collecting.clear();
            collecting = null;
            colourRunning = false;
        }
        if (!running || !EngineConfig.CHUNKS || Thread.currentThread() != serverThread || MAIN.tile != null
                || Profiler.get() != InactiveProfiler.INSTANCE)
            return;
        ServerLevel level = (ServerLevel) cache.getLevel();
        LevelPhase phase = CHUNK_PHASES.computeIfAbsent(level, key -> new LevelPhase(key, true));
        phase.cache = cache;
        collecting = phase;
        // C2ME's mid-tick tasks run after each recorded call: none until the recorded chunks have run
        colourRunning = true;
    }

    /** ServerChunkCache.tickSpawningChunk while recording: the call joins its chunk's tile (false: run it now). */
    public static boolean recordSpawnChunk(ServerChunkCache cache, LevelChunk chunk, long timeDiff, List<MobCategory> categories,
                                           NaturalSpawner.SpawnState state) {
        LevelPhase phase = collecting;
        if (phase == null || phase.cache != cache || Thread.currentThread() != serverThread) return false;
        if (!phase.bindSpawning(timeDiff, categories, state)) {
            chunkFallbacks++;
            return false;
        }
        ChunkPos pos = chunk.getPos();
        phase.tileFor(pos.x(), pos.z()).spawnChunks.add(chunk);
        return true;
    }

    /** ServerLevel.tickChunk while recording: the call joins its chunk's tile (false: run it now). */
    public static boolean recordTickChunk(ServerLevel level, LevelChunk chunk, int tickSpeed) {
        LevelPhase phase = collecting;
        if (phase == null || phase.level != level || Thread.currentThread() != serverThread) return false;
        if (!phase.bindTicking(tickSpeed)) {
            chunkFallbacks++;
            return false;
        }
        ChunkPos pos = chunk.getPos();
        phase.tileFor(pos.x(), pos.z()).tickChunks.add(chunk);
        return true;
    }

    /** Before the custom spawners (or at the end) of tickChunks: the recorded chunk calls run, tile by tile. */
    public static void runChunks(ServerChunkCache cache) {
        LevelPhase phase = collecting;
        if (phase == null || phase.cache != cache) return;
        collecting = null;
        colourRunning = false;
        EngineChunkCacheAccess access = (EngineChunkCacheAccess) cache;
        ServerLevel level = phase.level;
        long timeDiff = phase.timeDiff;
        List<MobCategory> categories = phase.categories;
        NaturalSpawner.SpawnState state = phase.state;
        int tickSpeed = phase.tickSpeed;
        phase.run((tile, context) -> {
            ArrayList<LevelChunk> spawning = tile.spawnChunks;
            for (int i = 0, n = spawning.size(); i < n; i++) access.phoenium$tickSpawningChunk(spawning.get(i), timeDiff, categories, state);
            ArrayList<LevelChunk> ticking = tile.tickChunks;
            for (int i = 0, n = ticking.size(); i < n; i++) level.tickChunk(ticking.get(i), tickSpeed);
        }, null, false);
    }

    // ---- shared state a tile keeps for itself ----

    /** Level.randValue (getBlockRandomPos and Lithium's chunk-random helpers; field accesses rewritten by the plugin). */
    public static int randValue(Level level) {
        TileContext context = current();
        return context != null ? context.randValue : ((EngineLevelAccess) level).phoenium$randValue();
    }

    public static void setRandValue(Level level, int value) {
        TileContext context = current();
        if (context != null) context.randValue = value;
        else ((EngineLevelAccess) level).phoenium$setRandValue(value);
    }

    /** Lithium's one cached random-tick position per level: a tile uses its own (plugin-inserted after each read). */
    public static BlockPos.MutableBlockPos tilePos(BlockPos.MutableBlockPos shared) {
        TileContext context = current();
        return context != null ? context.randomPos : shared;
    }

    // ---- services: what a tile cannot do while other tiles run ----

    /**
     * Runs {@code work} as the server thread does outside any tile, while no other tile runs, and returns its result:
     * from a tile of a parallel job the tile pauses until the server thread has run it; from a tile the server thread
     * runs on its own (one tile at a time) it runs at once. Outside a tile it is just called.
     */
    public static <T> T service(Supplier<T> work) {
        if (current() == null) return work.get();
        EngineWorkerPool.Job job = pool == null ? null : pool.current();
        return job == null ? runService(work) : job.service(work);
    }

    /**
     * Server thread: one service, with the running tile (if any) set aside so every routed call inside takes the vanilla
     * path. Called by a tile the server thread runs on its own, else by the job (no tile running then).
     */
    private static <T> T runService(Supplier<T> work) {
        TileWork tile = MAIN.tile;
        MAIN.tile = null;
        long start = System.nanoTime();
        try {
            return work.get();
        } finally {
            MAIN.tile = tile;
            services++;
            serviceNanos += System.nanoTime() - start;
        }
    }

    /** A chunk request a tile cannot answer without loading: the server thread loads it (vanilla's blocking getChunk). */
    static ChunkAccess loadChunk(TileContext context, ServerChunkCache cache, int x, int z, ChunkStatus status) {
        if (!EngineConfig.SERVICES) return loadTrap(context, cache, x, z, status);
        LOAD_SERVICES.increment();
        return service(() -> cache.getChunk(x, z, status, true));
    }

    /** A POI section access from a tile that loads a column or creates a section: done by the server thread. */
    public static <T> T storage(TileContext context, String kind, long sectionPos, Supplier<T> work, Supplier<T> trapped) {
        if (!EngineConfig.SERVICES) {
            storageTrap(context, kind, sectionPos);
            return trapped.get();
        }
        STORAGE_SERVICES.increment();
        return service(work);
    }

    /** A block write from a tile outside its claim: done by the server thread with no tile running. */
    public static <T> T write(TileContext context, LevelChunk chunk, BlockPos pos, Supplier<T> work) {
        if (!EngineConfig.SERVICES) {
            writeTrap(context, chunk, pos);
            return work.get();
        }
        WRITE_SERVICES.increment();
        return service(work);
    }

    /**
     * S9-E4: PoiManager.sectionsToVillage first runs the village-distance tracker's pending updates (a write). From a tile
     * with updates pending, the server thread runs them; with none pending the call only reads.
     */
    public static void villageUpdates(Runnable work) {
        VILLAGE_SERVICES.increment();
        service(() -> {
            work.run();
            return null;
        });
    }

    /** S9-E4: a structure search from a tile (a cartographer's map trade) reads and fills shared caches: the server thread runs it. */
    public static <T> T structureSearch(Supplier<T> work) {
        STRUCTURE_SERVICES.increment();
        return service(work);
    }

    // ---- traps (services off) ----

    /** Services off: a chunk request a tile thread cannot answer without loading is counted, logged, answered empty. */
    static ChunkAccess loadTrap(TileContext context, ServerChunkCache cache, int x, int z, ChunkStatus status) {
        LOAD_TRAPS.increment();
        String detail = "load chunk=" + x + "," + z + " status=" + status + " tile=" + context.tile.tileX + "," + context.tile.tileZ
            + " claimed=" + context.claims(x, z) + " level=" + context.level.dimension().identifier();
        if (EngineConfig.STRICT) throw new IllegalStateException("PHOENIUM_ENGINE_TRAP " + detail);
        logTrap(detail);
        ServerLevel level = (ServerLevel) cache.getLevel();
        return new EmptyLevelChunk(level, new ChunkPos(x, z), level.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS));
    }

    /** Services off: a block write outside the running tile's claim is counted and logged (the write proceeds). */
    static void writeTrap(TileContext context, LevelChunk chunk, BlockPos pos) {
        WRITE_TRAPS.increment();
        String detail = "write pos=" + pos.toShortString() + " tile=" + context.tile.tileX + "," + context.tile.tileZ
            + " level=" + context.level.dimension().identifier();
        if (EngineConfig.STRICT) throw new IllegalStateException("PHOENIUM_ENGINE_TRAP " + detail);
        logTrap(detail);
    }

    /** Services off: a POI storage access from a tile that would write it is counted and logged. */
    static void storageTrap(TileContext context, String kind, long sectionPos) {
        STORAGE_TRAPS.increment();
        String detail = "poi " + kind + " section=" + net.minecraft.core.SectionPos.of(sectionPos) + " tile=" + context.tile.tileX + ","
            + context.tile.tileZ + " level=" + context.level.dimension().identifier();
        if (EngineConfig.STRICT) throw new IllegalStateException("PHOENIUM_ENGINE_TRAP " + detail);
        logTrap(detail);
    }

    private static void logTrap(String detail) {
        int logged = TRAP_LOGS.getAndIncrement();
        if (logged < 5) LOGGER.warn("PHOENIUM_ENGINE_TRAP {}", detail, new Throwable("trap site"));
        else if (logged < 50) LOGGER.warn("PHOENIUM_ENGINE_TRAP {}", detail);
    }

    /** The id of the phase being run (dedupes per-phase deferrals); valid on tile threads while a tile runs. */
    public static long phaseId() { return phaseSequence; }

    /**
     * Server thread: whether a colour (or a recorded chunk phase) is running or its effects are being applied. C2ME's
     * mid-tick chunk tasks are skipped then; they would mutate chunk and entity state under running tiles.
     */
    public static boolean inColour() { return colourRunning; }

    // ---- S9-E9: parallel-for outside tiles ----

    /** Threads a parallel job runs on: the tile threads and the server thread. */
    static int parallelThreads() { return pool == null ? 1 : pool.threads() + 1; }

    /** Whether {@link #runParallel} can run a job: the engine runs with tile threads; the server thread, outside a tile. */
    static boolean canRunParallel() {
        return running && pool != null && Thread.currentThread() == serverThread && MAIN.tile == null;
    }

    /**
     * Server thread: runs {@code body} for 0..count-1 on the tile threads and this thread, in blocks of {@code block},
     * outside any tile (no tile state, no services); returns the summed time of the blocks in ns, or -1 (nothing run)
     * when {@link #canRunParallel} is false. With {@code capture} each thread's packet sends are held in its outbox until
     * {@link #deliverHeld}.
     */
    static long runParallel(int count, int block, EngineWorkerPool.ForBody body, boolean capture) {
        if (!canRunParallel()) return -1L;
        if (count <= 0) return 0L;
        EngineWorkerPool.ForJob job = new EngineWorkerPool.ForJob(count, block, body, capture);
        parallelFors++;
        pool.run(job, MAIN);
        return job.workNanos.get();
    }

    /** The calling thread's context while it runs a capturing parallel-for, else null. */
    static TileContext capturing() {
        Thread thread = Thread.currentThread();
        TileContext context;
        if (thread == serverThread) context = MAIN;
        else if (EngineWorkerPool.isWorker(thread)) context = EngineWorkerPool.contextOf(thread);
        else return null;
        return context.capturing ? context : null;
    }

    /** A connection's send on a thread that runs a capturing parallel-for: held in the thread's outbox (true). */
    public static boolean holdSend(Object connection, Packet<?> packet, Object listener) {
        TileContext context = capturing();
        if (context == null || packet.isTerminal()) return false;
        context.outbox.add(connection, packet, listener);
        return true;
    }

    /** Every outbox: the server thread's, then the tile threads' in thread order. */
    private static EngineOutbox[] outboxes() {
        EngineOutbox[] boxes = outboxes;
        if (boxes == null) {
            TileContext[] workers = pool == null ? new TileContext[0] : pool.contexts();
            boxes = new EngineOutbox[workers.length + 1];
            boxes[0] = MAIN.outbox;
            for (int i = 0; i < workers.length; i++) boxes[i + 1] = workers[i].outbox;
            outboxes = boxes;
        }
        return boxes;
    }

    /**
     * Server thread, after a capturing job: delivers every held send, each connection's on one thread (this one and the
     * tile threads share the connections) in outbox order, then empties the outboxes. Adds to {@code stats}: [0] packets,
     * [1] connections, [2] summed delivery time in ns.
     */
    static void deliverHeld(EngineOutbox.Sender sender, long[] stats) {
        EngineOutbox[] boxes = outboxes();
        try {
            EngineOutbox.targets(boxes, HELD_TARGETS, HELD_SEEN);
            int count = HELD_TARGETS.size();
            for (EngineOutbox box : boxes) stats[0] += box.packets();
            stats[1] += count;
            long work = count > 1 ? runParallel(count, 1, i -> EngineOutbox.deliver(boxes, HELD_TARGETS.get(i), sender), false) : -1L;
            if (work < 0L) {
                long start = System.nanoTime();
                for (int i = 0; i < count; i++) EngineOutbox.deliver(boxes, HELD_TARGETS.get(i), sender);
                work = System.nanoTime() - start;
            }
            stats[2] += work;
        } finally {
            discardHeld();
            HELD_TARGETS.clear();
            HELD_SEEN.clear();
        }
    }

    /** Server thread: empties every outbox (after delivery, or what a failed capturing job held). */
    static void discardHeld() {
        for (EngineOutbox box : outboxes()) box.clear();
    }

    /** True on a tile thread. */
    public static boolean onTileThread() { return EngineWorkerPool.isWorker(Thread.currentThread()); }

    /**
     * A chunk request outside any tile (vanilla's path): from a tile thread that is a parallel-for item, which would block
     * on the server thread while the server thread waits for the job. Fails instead of hanging.
     */
    public static void checkChunkRequest(int x, int z) {
        if (EngineWorkerPool.isWorker(Thread.currentThread()))
            throw new IllegalStateException("PHOENIUM_ENGINE chunk request " + x + "," + z + " from a tile thread outside a tile (parallel-for)");
    }

    /**
     * Whether the serial set takes the entity: it acts across distances or shared structures a tile cannot claim.
     * S9-E4: villagers and bees run in tiles (their POIs lie within 48 blocks, inside the claim; POI dirty marks and the
     * village-distance tracker's updates are deferred or serviced, structure searches serviced).
     */
    static boolean serial(Entity entity) {
        if (entity instanceof Player || entity instanceof Raider
                || entity instanceof ThrownEnderpearl || entity instanceof EnderDragon || entity instanceof WitherBoss
                || entity instanceof MinecartHopper || entity instanceof MinecartCommandBlock || entity.portalProcess != null
                // a fed dolphin searches for structures 50 chunks away (a tamed pet's teleport is deferred instead)
                || entity instanceof Dolphin)
            return true;
        return !entity.getPassengers().isEmpty() && entity.countPlayerPassengers() > 0;
    }

    private static final Comparator<TileWork> BY_KEY = (a, b) -> Long.compare(a.key, b.key);
    /** Measured cost first (last tick's run time), then the work count, then the key (deterministic). */
    private static final Comparator<TileWork> LARGEST_FIRST = (a, b) -> {
        int byTime = Long.compare(b.nanos, a.nanos);
        if (byTime != 0) return byTime;
        int bySize = Integer.compare(b.weight(), a.weight());
        return bySize != 0 ? bySize : Long.compare(a.key, b.key);
    };

    /** How a phase runs one tile's work on the calling thread. */
    interface TileBody {
        void run(TileWork tile, TileContext context);
    }

    /** One level's reusable phase state (entity or chunk phase); touched only by the server thread outside tile runs. */
    private static final class LevelPhase {
        private final ServerLevel level;
        private final boolean chunks;
        private final PhaseStats stats;
        private final long worldSeed;
        private final long dimension;
        private final Long2ObjectOpenHashMap<TileWork> tiles = new Long2ObjectOpenHashMap<>();
        /** Tile costs of the previous run, by key: the dispatch order of the next (tiles are recycled every phase). */
        private final Long2LongOpenHashMap lastNanos = new Long2LongOpenHashMap();
        private final ArrayList<TileWork> spare = new ArrayList<>();
        @SuppressWarnings("unchecked")
        private final ArrayList<TileWork>[] colours = new ArrayList[EngineConfig.COLOURS];
        private final ArrayList<Entity> serial = new ArrayList<>();
        private TileWork[] order = new TileWork[16];
        // chunk phase: the arguments every recorded call of this tick shares
        ServerChunkCache cache;
        long timeDiff;
        List<MobCategory> categories;
        NaturalSpawner.SpawnState state;
        boolean spawningBound;
        int tickSpeed = -1;

        LevelPhase(ServerLevel level, boolean chunks) {
            this.level = level;
            this.chunks = chunks;
            this.stats = chunks ? CHUNK : ENTITY;
            this.worldSeed = level.getSeed();
            long dimensionHash = level.dimension().identifier().toString().hashCode();
            this.dimension = chunks ? dimensionHash ^ CHUNK_SALT : dimensionHash;
            for (int i = 0; i < colours.length; i++) colours[i] = new ArrayList<>();
        }

        boolean bindSpawning(long timeDiff, List<MobCategory> categories, NaturalSpawner.SpawnState state) {
            if (!spawningBound) {
                this.timeDiff = timeDiff;
                this.categories = categories;
                this.state = state;
                spawningBound = true;
                return true;
            }
            return this.timeDiff == timeDiff && this.categories == categories && this.state == state;
        }

        boolean bindTicking(int tickSpeed) {
            if (this.tickSpeed < 0) {
                this.tickSpeed = tickSpeed;
                return true;
            }
            return this.tickSpeed == tickSpeed;
        }

        TileWork tileFor(int chunkX, int chunkZ) {
            int tileX = TileGeometry.tile(chunkX, EngineConfig.TILE_SHIFT);
            int tileZ = TileGeometry.tile(chunkZ, EngineConfig.TILE_SHIFT);
            long key = TileGeometry.key(tileX, tileZ);
            TileWork tile = tiles.get(key);
            if (tile == null) {
                tile = spare.isEmpty() ? new TileWork() : spare.remove(spare.size() - 1);
                int colour = TileGeometry.colour(tileX, tileZ, EngineConfig.PERIOD);
                tile.reset(key, tileX, tileZ, colour);
                tile.nanos = lastNanos.get(key);
                tiles.put(key, tile);
                colours[colour].add(tile);
            }
            return tile;
        }

        void classify(Entity entity) {
            if (EngineProtections.PROJECTILE_CAP && entity instanceof Projectile) EngineProtections.classified(level, entity, entity.chunkPosition());
            if (serial(entity)) {
                serial.add(entity);
                return;
            }
            ChunkPos chunk = entity.chunkPosition();
            tileFor(chunk.x(), chunk.z()).entities.add(entity);
        }

        /** Runs every tile (colour by colour, or as a dag), then the serial set with {@code serialBody}; then recycles. */
        void run(TileBody body, Consumer<Entity> serialBody, boolean dag) {
            long phaseId = ++phaseSequence;
            stats.phases++;
            long gameTime = level.getGameTime();
            long started = System.nanoTime();
            // the vanilla body pushes on the profiler it captured on this thread: tiles leave it only when it is inactive
            boolean parallel = pool != null && Profiler.get() == InactiveProfiler.INSTANCE;
            if (pool != null && !parallel) serialFallbacks++;
            try {
                for (ArrayList<TileWork> work : colours) work.sort(BY_KEY);
                if (dag && parallel) runDag(body, phaseId, gameTime);
                else runColours(body, phaseId, gameTime, parallel);
                if (!serial.isEmpty()) {
                    long serialStart = System.nanoTime();
                    for (int i = 0; i < serial.size(); i++) serialBody.accept(serial.get(i));
                    serialEntities += serial.size();
                    stats.serialNanos += System.nanoTime() - serialStart;
                }
            } finally {
                stats.phaseNanos += System.nanoTime() - started;
                clear();
            }
        }

        private void runColours(TileBody body, long phaseId, long gameTime, boolean parallel) {
            long phaseLargest = 0L;
            for (ArrayList<TileWork> work : colours) {
                int count = work.size();
                if (count == 0) continue;
                if (order.length < count) order = new TileWork[Math.max(count, order.length * 2)];
                for (int i = 0; i < count; i++) order[i] = work.get(i);
                Arrays.sort(order, 0, count, LARGEST_FIRST);
                long colourId = ++colourSequence;
                stats.colourRuns++;
                stats.tiles += count;
                stats.maxColourTiles = Math.max(stats.maxColourTiles, count);
                EngineWorkerPool.Runner runner = (tile, context) -> runTile(tile, context, body, phaseId, colourId, gameTime);
                colourRunning = true;
                try {
                    if (parallel && count > 1) {
                        stats.parallelRuns++;
                        pool.run(new EngineWorkerPool.ColourJob(order, count, runner), MAIN);
                    } else {
                        for (int i = 0; i < count; i++) runner.run(order[i], MAIN);
                    }
                    Arrays.fill(order, 0, count, null);
                    long colourLargest = 0L;
                    for (int i = 0; i < count; i++) colourLargest = Math.max(colourLargest, work.get(i).nanos);
                    stats.criticalNanos += colourLargest;
                    phaseLargest = Math.max(phaseLargest, colourLargest);
                    long applyStart = System.nanoTime();
                    for (int i = 0; i < count; i++) apply(work.get(i));
                    stats.applyNanos += System.nanoTime() - applyStart;
                } finally {
                    colourRunning = false;
                }
            }
            stats.largestNanos += phaseLargest;
        }

        /** Every tile in one job; a tile waits only for its lower-colour neighbours; effects apply after the job. */
        private void runDag(TileBody body, long phaseId, long gameTime) {
            int total = tiles.size();
            if (total == 0) return;
            int span = 2 * EngineConfig.CLAIM_RADIUS;
            if (order.length < total) order = new TileWork[Math.max(total, order.length * 2)];
            for (TileWork tile : tiles.values()) tile.clearSchedule();
            for (TileWork tile : tiles.values()) {
                for (int dx = -span; dx <= span; dx++) {
                    for (int dz = -span; dz <= span; dz++) {
                        if (dx == 0 && dz == 0) continue;
                        TileWork neighbour = tiles.get(TileGeometry.key(tile.tileX + dx, tile.tileZ + dz));
                        // two tiles whose claims touch always differ in colour; the higher one waits
                        if (neighbour != null && neighbour.colour > tile.colour) tile.addSuccessor(neighbour);
                    }
                }
            }
            int roots = 0;
            for (TileWork tile : tiles.values()) {
                tile.pending.set(tile.predecessors);
                if (tile.predecessors == 0) order[roots++] = tile;
            }
            Arrays.sort(order, 0, roots, LARGEST_FIRST);
            stats.colourRuns++;
            stats.parallelRuns++;
            stats.tiles += total;
            stats.maxColourTiles = Math.max(stats.maxColourTiles, roots);
            // run id -1: every tile run starts with an empty path-type cache (neighbours may have run on other threads)
            EngineWorkerPool.Runner runner = (tile, context) -> runTile(tile, context, body, phaseId, -1L, gameTime);
            colourRunning = true;
            try {
                pool.run(new EngineWorkerPool.DagJob(order, roots, total, runner), MAIN);
                Arrays.fill(order, 0, roots, null);
                // S9-E8: the longest chain of measured tile times (colour order is a topological order of the dag)
                long critical = 0L;
                long largest = 0L;
                for (ArrayList<TileWork> work : colours) {
                    for (int i = 0, n = work.size(); i < n; i++) {
                        TileWork tile = work.get(i);
                        long finish = tile.earliest + tile.nanos;
                        critical = Math.max(critical, finish);
                        largest = Math.max(largest, tile.nanos);
                        for (int k = 0; k < tile.successorCount; k++) {
                            TileWork next = tile.successors[k];
                            if (next.earliest < finish) next.earliest = finish;
                        }
                    }
                }
                stats.criticalNanos += critical;
                stats.largestNanos += largest;
                long applyStart = System.nanoTime();
                for (ArrayList<TileWork> work : colours) {
                    for (int i = 0, n = work.size(); i < n; i++) apply(work.get(i));
                }
                stats.applyNanos += System.nanoTime() - applyStart;
            } finally {
                colourRunning = false;
            }
        }

        private void runTile(TileWork tile, TileContext context, TileBody body, long phaseId, long runId, long gameTime) {
            context.enter(level, tile, phaseId, runId, TileGeometry.seed(worldSeed, dimension, gameTime, tile.key));
            long start = System.nanoTime();
            try {
                body.run(tile, context);
            } finally {
                context.exit();
                tile.nanos = System.nanoTime() - start;
            }
        }

        private void apply(TileWork tile) {
            if (chunks) {
                spawnChunks += tile.spawnChunks.size();
                tickChunks += tile.tickChunks.size();
            } else {
                parallelEntities += tile.entities.size();
            }
            stats.tileNanos += tile.nanos;
            ArrayList<Runnable> effects = tile.deferred;
            for (int i = 0; i < effects.size(); i++) effects.get(i).run();
            stats.deferredApplied += effects.size();
            effects.clear();
        }

        void clear() {
            for (ArrayList<TileWork> work : colours) work.clear();
            lastNanos.clear();
            for (TileWork tile : tiles.values()) {
                lastNanos.put(tile.key, tile.nanos);
                tile.entities.clear();
                tile.spawnChunks.clear();
                tile.tickChunks.clear();
                tile.deferred.clear();
                tile.clearSchedule();
                spare.add(tile);
            }
            tiles.clear();
            serial.clear();
            categories = null;
            state = null;
            spawningBound = false;
            tickSpeed = -1;
        }
    }
}
