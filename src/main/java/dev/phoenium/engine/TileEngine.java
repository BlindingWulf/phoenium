package dev.phoenium.engine;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.bee.Bee;
import net.minecraft.world.entity.animal.dolphin.Dolphin;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.entity.vehicle.minecart.MinecartCommandBlock;
import net.minecraft.world.entity.vehicle.minecart.MinecartHopper;
import net.minecraft.world.level.ChunkPos;
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
 */
public final class TileEngine {
    private TileEngine() { }

    private static final Logger LOGGER = LoggerFactory.getLogger("Phoenium");

    private static final TileContext MAIN = new TileContext();
    private static Thread serverThread;
    private static EngineWorkerPool pool;
    private static volatile boolean running;
    private static boolean colourRunning;
    private static final IdentityHashMap<ServerLevel, LevelPhase> PHASES = new IdentityHashMap<>();
    private static long phaseSequence;
    private static long colourSequence;
    private static long phases;
    private static long colourRuns;
    private static long parallelColourRuns;
    private static long tilesRun;
    private static long parallelEntities;
    private static long serialEntities;
    private static long deferredApplied;
    private static long serialFallbacks;
    private static long maxColourTiles;
    private static final LongAdder LOAD_TRAPS = new LongAdder();
    private static final LongAdder WRITE_TRAPS = new LongAdder();
    private static final LongAdder STORAGE_TRAPS = new LongAdder();
    private static final AtomicInteger TRAP_LOGS = new AtomicInteger();

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
        LOGGER.info("PHOENIUM_STAGE2 entrypoint registered once; engine mode ({}) awaits server start.", EngineConfig.describe());
    }

    private static void start(MinecraftServer server) {
        serverThread = server.getRunningThread();
        pool = EngineWorkerPool.isWorker(serverThread) || EngineConfig.WORKERS == 0 ? null : new EngineWorkerPool(EngineConfig.WORKERS);
        running = true;
        LOGGER.info("PHOENIUM_STAGE2 started lifecycle=RUNNING scheduler=ENGINE workers={} mode=engine-tiles {} threads={} parallel={} transforms={}",
            EngineConfig.WORKERS, EngineConfig.describe(), EngineConfig.WORKERS + 1, pool == null ? "OFF" : "TILES",
            EngineConfig.transforms());
    }

    private static void stop(MinecraftServer server) {
        running = false;
        if (pool != null) pool.stop();
        LOGGER.info("PHOENIUM_STAGE2 stopped lifecycle=STOPPED mode=engine-tiles {}", counters());
    }

    public static String counters() {
        return "phases=" + phases + " colourRuns=" + colourRuns + " parallelColourRuns=" + parallelColourRuns
            + " tiles=" + tilesRun + " maxColourTiles=" + maxColourTiles + " parallelEntities=" + parallelEntities
            + " serialEntities=" + serialEntities + " deferredApplied=" + deferredApplied + " serialFallbacks=" + serialFallbacks
            + " loadTraps=" + LOAD_TRAPS.sum() + " writeTraps=" + WRITE_TRAPS.sum() + " storageTraps=" + STORAGE_TRAPS.sum();
    }

    /** The wrapped EntityTickList.forEach of ServerLevel.tick. */
    public static void tickEntities(ServerLevel level, EntityTickList list, Consumer<Entity> body, Operation<Void> original) {
        if (!running || Thread.currentThread() != serverThread || MAIN.tile != null) {
            original.call(list, body);
            return;
        }
        LevelPhase phase = PHASES.computeIfAbsent(level, LevelPhase::new);
        original.call(list, (Consumer<Entity>) phase::classify);
        phase.run(body);
    }

    /** A chunk request a tile thread cannot answer without loading: counted, logged, answered with an empty chunk. */
    static ChunkAccess loadTrap(TileContext context, ServerChunkCache cache, int x, int z, ChunkStatus status) {
        LOAD_TRAPS.increment();
        String detail = "load chunk=" + x + "," + z + " status=" + status + " tile=" + context.tile.tileX + "," + context.tile.tileZ
            + " claimed=" + context.claims(x, z) + " level=" + context.level.dimension().identifier();
        if (EngineConfig.STRICT) throw new IllegalStateException("PHOENIUM_ENGINE_TRAP " + detail);
        logTrap(detail);
        ServerLevel level = (ServerLevel) cache.getLevel();
        return new EmptyLevelChunk(level, new ChunkPos(x, z), level.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS));
    }

    /** A block write outside the running tile's claim: counted and logged (the write proceeds). */
    public static void writeTrap(TileContext context, LevelChunk chunk, BlockPos pos) {
        WRITE_TRAPS.increment();
        String detail = "write pos=" + pos.toShortString() + " tile=" + context.tile.tileX + "," + context.tile.tileZ
            + " level=" + context.level.dimension().identifier();
        if (EngineConfig.STRICT) throw new IllegalStateException("PHOENIUM_ENGINE_TRAP " + detail);
        logTrap(detail);
    }

    /** A POI section storage access from a tile that would write it (load a column, create a section): counted, logged. */
    public static void storageTrap(TileContext context, String kind, long sectionPos) {
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
     * Server thread: whether a colour is running or its effects are being applied. C2ME's mid-tick chunk tasks (run
     * after each guarded entity tick) are skipped then; they would mutate chunk and entity state under running tiles.
     */
    public static boolean inColour() { return colourRunning; }

    /** Whether the serial set takes the entity: it acts across distances or shared structures a tile cannot claim. */
    static boolean serial(Entity entity) {
        if (entity instanceof Player || entity instanceof Raider || entity instanceof AbstractVillager || entity instanceof Bee
                || entity instanceof ThrownEnderpearl || entity instanceof EnderDragon || entity instanceof WitherBoss
                || entity instanceof MinecartHopper || entity instanceof MinecartCommandBlock || entity.portalProcess != null
                // a fed dolphin searches for structures 50 chunks away (a tamed pet's teleport is deferred instead)
                || entity instanceof Dolphin)
            return true;
        return !entity.getPassengers().isEmpty() && entity.countPlayerPassengers() > 0;
    }

    private static final Comparator<TileWork> BY_KEY = (a, b) -> Long.compare(a.key, b.key);
    private static final Comparator<TileWork> LARGEST_FIRST = (a, b) -> {
        int bySize = Integer.compare(b.entities.size(), a.entities.size());
        return bySize != 0 ? bySize : Long.compare(a.key, b.key);
    };

    /** One level's reusable phase state; touched only by the server thread outside tile runs. */
    private static final class LevelPhase {
        private final ServerLevel level;
        private final long worldSeed;
        private final long dimension;
        private final Long2ObjectOpenHashMap<TileWork> tiles = new Long2ObjectOpenHashMap<>();
        private final ArrayList<TileWork> spare = new ArrayList<>();
        @SuppressWarnings("unchecked")
        private final ArrayList<TileWork>[] colours = new ArrayList[EngineConfig.COLOURS];
        private final ArrayList<Entity> serial = new ArrayList<>();
        private TileWork[] order = new TileWork[16];

        LevelPhase(ServerLevel level) {
            this.level = level;
            this.worldSeed = level.getSeed();
            this.dimension = level.dimension().identifier().toString().hashCode();
            for (int i = 0; i < colours.length; i++) colours[i] = new ArrayList<>();
        }

        void classify(Entity entity) {
            if (serial(entity)) {
                serial.add(entity);
                return;
            }
            ChunkPos chunk = entity.chunkPosition();
            int tileX = TileGeometry.tile(chunk.x(), EngineConfig.TILE_SHIFT);
            int tileZ = TileGeometry.tile(chunk.z(), EngineConfig.TILE_SHIFT);
            long key = TileGeometry.key(tileX, tileZ);
            TileWork tile = tiles.get(key);
            if (tile == null) {
                tile = spare.isEmpty() ? new TileWork() : spare.remove(spare.size() - 1);
                int colour = TileGeometry.colour(tileX, tileZ, EngineConfig.PERIOD);
                tile.reset(key, tileX, tileZ, colour);
                tiles.put(key, tile);
                colours[colour].add(tile);
            }
            tile.entities.add(entity);
        }

        void run(Consumer<Entity> body) {
            long phaseId = ++phaseSequence;
            phases++;
            long gameTime = level.getGameTime();
            // the vanilla body pushes on the profiler it captured on this thread: tiles leave it only when it is inactive
            boolean parallel = pool != null && Profiler.get() == InactiveProfiler.INSTANCE;
            if (pool != null && !parallel) serialFallbacks++;
            try {
                for (ArrayList<TileWork> work : colours) {
                    int count = work.size();
                    if (count == 0) continue;
                    work.sort(BY_KEY);
                    if (order.length < count) order = new TileWork[Math.max(count, order.length * 2)];
                    for (int i = 0; i < count; i++) order[i] = work.get(i);
                    Arrays.sort(order, 0, count, LARGEST_FIRST);
                    long colourId = ++colourSequence;
                    colourRuns++;
                    tilesRun += count;
                    maxColourTiles = Math.max(maxColourTiles, count);
                    EngineWorkerPool.Runner runner = (tile, context) -> runTile(tile, context, body, phaseId, colourId, gameTime);
                    colourRunning = true;
                    try {
                        if (parallel && count > 1) {
                            parallelColourRuns++;
                            pool.run(new EngineWorkerPool.Job(order, count, runner), MAIN);
                        } else {
                            for (int i = 0; i < count; i++) runner.run(order[i], MAIN);
                        }
                        Arrays.fill(order, 0, count, null);
                        for (int i = 0; i < count; i++) apply(work.get(i));
                    } finally {
                        colourRunning = false;
                    }
                }
                for (int i = 0; i < serial.size(); i++) body.accept(serial.get(i));
                serialEntities += serial.size();
            } finally {
                for (ArrayList<TileWork> work : colours) work.clear();
                for (TileWork tile : tiles.values()) {
                    tile.entities.clear();
                    tile.deferred.clear();
                    spare.add(tile);
                }
                tiles.clear();
                serial.clear();
            }
        }

        private void runTile(TileWork tile, TileContext context, Consumer<Entity> body, long phaseId, long colourId, long gameTime) {
            context.enter(level, tile, phaseId, colourId, TileGeometry.seed(worldSeed, dimension, gameTime, tile.key));
            long start = System.nanoTime();
            try {
                ArrayList<Entity> entities = tile.entities;
                for (int i = 0, n = entities.size(); i < n; i++) body.accept(entities.get(i));
            } finally {
                context.exit();
                tile.nanos = System.nanoTime() - start;
            }
        }

        private void apply(TileWork tile) {
            parallelEntities += tile.entities.size();
            ArrayList<Runnable> effects = tile.deferred;
            for (int i = 0; i < effects.size(); i++) effects.get(i).run();
            deferredApplied += effects.size();
            effects.clear();
        }
    }
}
