package dev.phoenix.spawnprep;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Phoenix spawn preparation. At the end of vanilla's level preparation (before "Done" is logged, before the watchdog starts,
 * while no player can be in the world yet) it generates the missing chunks in a square around spawn. Finished chunks are
 * detected in storage and never loaded or rewritten. Progress goes to the log in a stable key=value form and to
 * {@code <world>/phoenix-spawnprep.json}; creating {@code phoenix-spawnprep.cancel} in the server folder cancels.
 */
public final class SpawnPrep implements ModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("PhoenixSpawnPrep");
    /** Loads to FULL without simulating (no entity or block ticking), never expires on its own: always removed by us. */
    public static TicketType ticket;
    /** Captured before vanilla initialises a new world's level data (see PrepareLevelsMixin). */
    public static volatile boolean newWorld;

    @Override
    public void onInitialize() {
        ticket = Registry.register(BuiltInRegistries.TICKET_TYPE, Identifier.fromNamespaceAndPath("phoenix", "spawn_prep"),
                                   new TicketType(TicketType.NO_TIMEOUT, TicketType.FLAG_LOADING));
    }

    /** Called on the server thread; {@code waiter} runs vanilla's own startup wait (tasks keep running meanwhile). */
    public static void run(MinecraftServer server, Runnable waiter) {
        try {
            prepare(server, waiter);
        } catch (Throwable t) {                              // never stop a server from starting because of preparation
            LOG.error("[PhoenixSpawnPrep] FAILED: spawn preparation stopped with an error; the server starts anyway", t);
        }
    }

    private static void prepare(MinecraftServer server, Runnable waiter) throws IOException {
        Path gameDir = FabricLoader.getInstance().getGameDir();
        SpawnPrepConfig config = loadConfig(FabricLoader.getInstance().getConfigDir().resolve("phoenix-spawnprep.properties"));
        config.warnings().forEach(w -> LOG.warn("[PhoenixSpawnPrep] config: {}", w));
        ServerLevel level = server.findRespawnDimension();
        BlockPos spawn = server.getWorldData().overworldData().getRespawnData().pos();
        int cx = spawn.getX() >> 4, cz = spawn.getZ() >> 4;
        String dimension = level.dimension().identifier().toString();
        Path markerFile = server.getWorldPath(LevelResource.ROOT).normalize().resolve("phoenix-spawnprep.json");
        Path cancelFile = gameDir.resolve("phoenix-spawnprep.cancel");
        SpawnPrepMarker marker = SpawnPrepMarker.read(markerFile);
        SpawnPrepDecision decision = SpawnPrepDecision.decide(marker, newWorld, config, dimension, cx, cz);
        if (!decision.run()) {
            LOG.info("[PhoenixSpawnPrep] SKIP reason={} radiusBlocks={}", decision.reason(), config.radiusBlocks());
            return;
        }
        if (Files.deleteIfExists(cancelFile)) LOG.info("[PhoenixSpawnPrep] removed a cancel file left from an earlier run");
        var plan = SpawnPrepPlan.order(cx, cz, SpawnPrepPlan.chunkRadius(config.radiusBlocks()));
        LOG.info("[PhoenixSpawnPrep] START reason={} dimension={} centerChunk={},{} radiusBlocks={} chunks={} maxInFlight={}",
                 decision.reason(), dimension, cx, cz, config.radiusBlocks(), plan.size(), config.maxInFlight());
        writeMarker(markerFile, SpawnPrepMarker.State.RUNNING, dimension, cx, cz, config.radiusBlocks(),
                    new SpawnPrepRunner.Result(SpawnPrepMarker.State.RUNNING, 0, plan.size(), 0, 0, 0, 0, 0));
        if (newWorld) {
            // A new world has no level data on disk yet: the dedicated server writes it (level.dat, world_gen_settings.dat)
            // with saveEverything(false, true, true) right after "Done". Killed during a long first preparation, the world
            // could not load again ("Overworld settings missing"). Make that same save now, after the RUNNING marker, so a
            // kill at any later point resumes instead.
            long t0 = System.nanoTime();
            server.saveEverything(true, true, true);
            LOG.info("[PhoenixSpawnPrep] SAVED new world level data before preparation seconds={}",
                     String.format(Locale.ROOT, "%.1f", (System.nanoTime() - t0) / 1e9));
        }
        SpawnPrepRunner.Result result = new SpawnPrepRunner(plan, config.maxInFlight(), new LevelBackend(level, waiter),
            new SpawnPrepRunner.Control() {
                @Override
                public boolean cancelRequested() {
                    return Files.exists(cancelFile);
                }

                @Override
                public boolean stopping() {
                    return !server.isRunning();
                }

                @Override
                public long nanoTime() {
                    return System.nanoTime();
                }
            }, LOG::info, r -> writeMarkerQuietly(markerFile, SpawnPrepMarker.State.RUNNING, dimension, cx, cz, config.radiusBlocks(), r))
            .run();
        if (result.state() == SpawnPrepMarker.State.COMPLETE) {
            level.getChunkSource().save(true);               // on disk before the marker says so
        }
        if (result.state() == SpawnPrepMarker.State.CANCELLED) Files.deleteIfExists(cancelFile);
        writeMarker(markerFile, result.state(), dimension, cx, cz, config.radiusBlocks(), result);
        LOG.info("[PhoenixSpawnPrep] READY state={} (the server finishes starting now)", result.state());
    }

    private static SpawnPrepConfig loadConfig(Path file) throws IOException {
        if (!Files.exists(file)) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, SpawnPrepConfig.defaultFileText(), StandardCharsets.UTF_8);
        }
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        }
        return SpawnPrepConfig.from(p);
    }

    private static void writeMarker(Path file, SpawnPrepMarker.State state, String dimension, int cx, int cz, int radius,
                                    SpawnPrepRunner.Result r) throws IOException {
        new SpawnPrepMarker(1, state, dimension, cx, cz, radius, r.done(), r.total(), r.generated(), r.existing(), r.failed(),
                            System.currentTimeMillis()).write(file);
    }

    private static void writeMarkerQuietly(Path file, SpawnPrepMarker.State state, String dimension, int cx, int cz, int radius,
                                           SpawnPrepRunner.Result r) {
        try {
            writeMarker(file, state, dimension, cx, cz, radius, r);
        } catch (IOException e) {
            LOG.warn("[PhoenixSpawnPrep] could not update {}: {}", file, e.toString());
        }
    }

    /** The real chunk system, used only from the server thread. */
    private static final class LevelBackend implements SpawnPrepRunner.Backend {
        private static final int PUMPS_PER_UNLOAD = 10;
        private final ServerLevel level;
        private final Runnable waiter;
        private int pumps;

        LevelBackend(ServerLevel level, Runnable waiter) {
            this.level = level;
            this.waiter = waiter;
        }

        @Override
        public CompletableFuture<Boolean> isComplete(SpawnPrepPlan.Pos pos) {
            return level.getChunkSource().chunkMap.read(new ChunkPos(pos.x(), pos.z()))
                .thenApply(tag -> tag.map(t -> SerializableChunkData.getChunkStatusFromTag(t) == ChunkStatus.FULL).orElse(false));
        }

        @Override
        public CompletableFuture<?> generate(SpawnPrepPlan.Pos pos) {
            return level.getChunkSource().addTicketAndLoadWithRadius(ticket, new ChunkPos(pos.x(), pos.z()), 0);
        }

        @Override
        public void release(SpawnPrepPlan.Pos pos) {
            level.getChunkSource().removeTicketWithRadius(ticket, new ChunkPos(pos.x(), pos.z()), 0);
        }

        @Override
        public void pump() {
            waiter.run();
            if (++pumps % PUMPS_PER_UNLOAD == 0) {
                ServerChunkCache cache = level.getChunkSource();
                cache.tick(() -> true, false);               // ticket updates + unload/save of released chunks, no simulation
            }
        }

        @Override
        public long usedHeap() {
            Runtime rt = Runtime.getRuntime();
            return rt.totalMemory() - rt.freeMemory();
        }

        @Override
        public long maxHeap() {
            return Runtime.getRuntime().maxMemory();
        }
    }
}
