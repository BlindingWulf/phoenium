package dev.phoenium.stage5probe;

import com.google.gson.GsonBuilder;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.phoenium.Phoenium;
import dev.phoenium.chunk.FullFinishingServices;
import dev.phoenium.chunk.FullRegionalEnclosure;
import dev.phoenium.core.*;
import dev.phoenium.live.LiveTickReadiness;
import dev.phoenium.region.*;
import dev.phoenium.service.*;
import dev.phoenium.topology.*;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.*;

/** Disposable native furnace workload. No production instrumentation or authority
 * is installed by this probe; all placement/readback uses the existing owner gateway. */
public final class Stage5ParallelProbe implements DedicatedServerModInitializer {
    private static final int[] CENTERS = Arrays.stream(System.getProperty("phoenium.stage5.centers", "0,10,20,32")
        .split(",")).mapToInt(Integer::parseInt).toArray();
    private static final List<BlockPos> POSITIONS = new ArrayList<>();
    private static Measurement measurement;
    private static Measurement nativeSprintFinishing;
    private static long tickStart;
    private static String layout = "distributed";
    private static boolean demandPending;
    private static int churnRounds;

    @Override public void onInitializeServer() {
        if (!Boolean.getBoolean("phoenium.stage5.probe")) return;
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> dispatcher.register(
            Commands.literal("stage5")
                .then(Commands.literal("demand").executes(context -> { demand(context.getSource().getLevel()); return 1; }))
                .then(Commands.literal("dirty").executes(context -> { dirty(context.getSource().getLevel()); return 1; }))
                .then(Commands.literal("churn").executes(context -> { churn(context.getSource().getLevel()); return 1; }))
                .then(Commands.literal("adverse").executes(context -> {
                    dev.phoenium.adversestop.AdverseStopProbe.armStage5(context.getSource().getServer()); return 1;
                }))
                .then(Commands.literal("prepare").then(Commands.argument("perRegion", IntegerArgumentType.integer(1, 256))
                    .executes(context -> { prepare(context.getSource().getLevel(), IntegerArgumentType.getInteger(context, "perRegion")); return 1; })))
                .then(Commands.literal("adopt").then(Commands.argument("perRegion", IntegerArgumentType.integer(1, 256))
                    .executes(context -> { adopt(context.getSource().getLevel(), IntegerArgumentType.getInteger(context, "perRegion")); return 1; })))
                .then(Commands.literal("split").executes(context -> { split(context.getSource().getLevel(), false); return 1; }))
                .then(Commands.literal("merge").executes(context -> { split(context.getSource().getLevel(), true); return 1; }))
                .then(Commands.literal("measure").then(Commands.argument("label", StringArgumentType.word())
                    .then(Commands.argument("ticks", IntegerArgumentType.integer(1, 10000)).executes(context -> {
                        measure(context.getSource().getServer(), StringArgumentType.getString(context, "label"),
                            IntegerArgumentType.getInteger(context, "ticks")); return 1;
                    }))))
                .then(Commands.literal("snapshot").then(Commands.argument("label", StringArgumentType.word()).executes(context -> {
                    snapshot(context.getSource().getServer(), StringArgumentType.getString(context, "label"), Map.of()); return 1;
                })) )));
        ServerTickEvents.START_SERVER_TICK.register(server -> tickStart = System.nanoTime());
        ServerTickEvents.END_SERVER_TICK.register(Stage5ParallelProbe::endTick);
    }

    private static void demand(ServerLevel level) {
        level.getServer().tickRateManager().setFrozen(true);
        for (int center : CENTERS) for (int x = center - 1; x <= center + 1; x++) for (int z = -1; z <= 1; z++)
            // Publish the original native ticket without ServerLevel.setChunkForced's
            // eager getChunk wait. The event loop must produce and acknowledge FULL
            // before preparation can read or place anything in these chunks.
            level.getChunkSource().updateChunkForced(new net.minecraft.world.level.ChunkPos(x, z), true);
        demandPending = true;
        System.out.println("STAGE5_DEMANDED nativeForcedChunks=" + CENTERS.length * 9);
    }
    private static void prepare(ServerLevel level, int count) {
        if (!POSITIONS.isEmpty()) throw new IllegalStateException("workload already prepared");
        if (!LiveTickReadiness.await(level).current()) throw new IllegalStateException("native FULL readiness is not current");
        RegionalServiceDispatcher dispatcher = field(Phoenium.liveRuntime(), "regionalDispatcher", RegionalServiceDispatcher.class);
        for (int center : CENTERS) {
            if (level.getChunkSource().getChunkNow(center, 0) == null) throw new IllegalStateException("native workload chunk absent " + center);
            RoutingKey key = new RoutingKey(new WorldId("minecraft:overworld"), center, 0);
            dispatcher.ensureClosure(key, 1);
            dispatcher.executeAndDrain(key, RegionalServiceKind.BLOCK_ENTITY_TICK, ignored -> {
                for (int index = 0; index < count; index++) {
                    BlockPos pos = new BlockPos(center * 16 + index % 16, 65, index / 16);
                    level.setBlock(pos, Blocks.FURNACE.defaultBlockState(), 3);
                    BlockEntity actual = level.getBlockEntity(pos);
                    if (!(actual instanceof FurnaceBlockEntity furnace)) throw new IllegalStateException("native furnace placement failed " + pos);
                    furnace.setItem(0, new ItemStack(Items.IRON_ORE, 64));
                    furnace.setItem(1, new ItemStack(Items.COAL, 64));
                    POSITIONS.add(pos);
                }
            });
        }
        Stage5FurnaceObservations.register(POSITIONS);
        System.out.println("STAGE5_PREPARED furnaces=" + POSITIONS.size());
    }

    private static void dirty(ServerLevel level) {
        RegionalServiceDispatcher dispatcher = field(Phoenium.liveRuntime(), "regionalDispatcher", RegionalServiceDispatcher.class);
        for (int center : CENTERS) dispatcher.executeAndDrain(new RoutingKey(new WorldId("minecraft:overworld"), center, 0),
            RegionalServiceKind.CHUNK_GAMEPLAY, ignored -> {
                var chunk = level.getChunkSource().getChunkNow(center, 0);
                if (chunk == null) throw new IllegalStateException("native dirty input has no current chunk");
                chunk.markUnsaved();
            });
        System.out.println("STAGE5_DIRTY nativeChunks=" + CENTERS.length);
    }

    private static void churn(ServerLevel level) {
        if (measurement != null || POSITIONS.isEmpty()) throw new IllegalStateException("churn requires a joined native tick cut");
        if (++churnRounds > 6) throw new IllegalStateException("installed churn bound exceeded");
        var live = Phoenium.liveRuntime();
        RegionalServiceDispatcher dispatcher = field(live, "regionalDispatcher", RegionalServiceDispatcher.class);
        RegionDirectory directory = field(live, "directory", RegionDirectory.class);
        int replacements = 0, staleReads = 0;
        for (boolean concentrated : new boolean[]{true, false}) {
            List<RegionalServiceDispatcher.Handle> queued = new ArrayList<>();
            long oldGeneration = directory.snapshot().generation().value();
            java.util.concurrent.atomic.AtomicInteger applied = new java.util.concurrent.atomic.AtomicInteger();
            for (int center : CENTERS) {
                RoutingKey key = new RoutingKey(new WorldId("minecraft:overworld"), center, 0);
                BlockPos pos = new BlockPos(center * 16, 65, 0);
                queued.add(dispatcher.submit(key, RegionalServiceKind.BLOCK_ENTITY_TICK, ignored -> {
                    if (directory.snapshot().generation().value() <= oldGeneration)
                        throw new IllegalStateException("stale fixture input ran before topology publication");
                    dispatcher.requireCurrentOwner(key);
                    if (!(level.getBlockEntity(pos) instanceof FurnaceBlockEntity))
                        throw new IllegalStateException("stale delivery lost current native incarnation");
                    applied.incrementAndGet();
                }));
            }
            split(level, concentrated);
            queued.forEach(dispatcher::drain);
            if (applied.get() != CENTERS.length) throw new IllegalStateException("stale native input was lost or duplicated");
            staleReads += applied.get();
            if (concentrated) for (int center : CENTERS) {
                RoutingKey key = new RoutingKey(new WorldId("minecraft:overworld"), center, 0);
                dispatcher.executeAndDrain(key, RegionalServiceKind.BLOCK_ENTITY_TICK, ignored -> {
                    BlockPos pos = new BlockPos(center * 16, 65, 0);
                    BlockEntity previous = Objects.requireNonNull(level.getBlockEntity(pos));
                    var state = previous.getBlockState();
                    var saved = previous.saveWithFullMetadata(level.registryAccess());
                    BlockEntity replacement = BlockEntity.loadStatic(pos, state, saved, level.registryAccess());
                    if (!(replacement instanceof FurnaceBlockEntity) || replacement == previous)
                        throw new IllegalStateException("native replacement reader failed");
                    level.removeBlockEntity(pos); level.setBlockEntity(replacement);
                    if (!previous.isRemoved() || level.getBlockEntity(pos) != replacement
                            || !saved.equals(replacement.saveWithFullMetadata(level.registryAccess())))
                        throw new IllegalStateException("replacement lost incarnation or native saved state");
                });
                replacements++;
            }
        }
        snapshot(level.getServer(), "churn-" + churnRounds, Map.of("replacements", replacements, "staleReads", staleReads));
        System.out.println("STAGE5_CHURN round=" + churnRounds + " replacements=" + replacements + " staleReads=" + staleReads);
    }

    private static void adopt(ServerLevel level, int count) {
        if (!POSITIONS.isEmpty()) throw new IllegalStateException("workload already retained");
        if (!LiveTickReadiness.await(level).current()) throw new IllegalStateException("reload FULL not current");
        RegionalServiceDispatcher dispatcher = field(Phoenium.liveRuntime(), "regionalDispatcher", RegionalServiceDispatcher.class);
        for (int center : CENTERS) {
            RoutingKey key = new RoutingKey(new WorldId("minecraft:overworld"), center, 0);
            dispatcher.ensureClosure(key, 1);
            dispatcher.executeAndDrain(key, RegionalServiceKind.SAVE_CAPTURE, ignored -> {
                for (int index = 0; index < count; index++) {
                    BlockPos pos = new BlockPos(center * 16 + index % 16, 65, index / 16);
                    if (!(level.getBlockEntity(pos) instanceof FurnaceBlockEntity))
                        throw new IllegalStateException("native saved reader did not restore furnace " + pos);
                    POSITIONS.add(pos);
                }
            });
        }
        Stage5FurnaceObservations.register(POSITIONS);
        System.out.println("STAGE5_ADOPTED nativeSavedFurnaces=" + POSITIONS.size());
    }

    @SuppressWarnings("unchecked") private static void split(ServerLevel level, boolean concentrated) {
        if (measurement != null) throw new IllegalStateException("topology requested during active measurement");
        var live = Phoenium.liveRuntime();
        RegionDirectory directory = field(live, "directory", RegionDirectory.class);
        Map<RegionId, RegionRuntime> runtimes = field(live, "runtimes", Map.class);
        WorldId world = new WorldId("minecraft:overworld");
        List<RegionId> regions = new ArrayList<>();
        for (int index = 0; index < CENTERS.length; index++) {
            RegionId id = new RegionId(world, "stage5-region-" + index); regions.add(id);
            runtimes.computeIfAbsent(id, key -> {
                RegionRuntime runtime = new RegionRuntime(key, new OwnerEpoch(0), false);
                runtime.transitionTo(RegionLifecycle.READY); return runtime;
            });
        }
        Set<RegionId> participants = new HashSet<>();
        runtimes.keySet().stream().filter(id -> id.world().equals(world)).forEach(participants::add);
        Map<RoutingKey, RegionId> owners = new LinkedHashMap<>();
        directory.snapshot().routes().forEach((key, route) -> {
            if (!key.world().equals(world)) { owners.put(key, route.region()); return; }
            int nearest = 0;
            if (!concentrated) for (int index = 1; index < CENTERS.length; index++)
                if (Math.abs(key.x() - CENTERS[index]) < Math.abs(key.x() - CENTERS[nearest])) nearest = index;
            owners.put(key, regions.get(nearest));
        });
        FullRegionalEnclosure enclosure = field(live, "fullEnclosure", FullRegionalEnclosure.class);
        TopologyCoordinator coordinator = field(enclosure, "coordinator", TopologyCoordinator.class);
        coordinator.request(new TopologyCoordinator.Transition(participants, owners));
        if (!coordinator.advance()) throw new IllegalStateException("native workload topology did not reach quiescence");
        layout = concentrated ? "concentrated" : "distributed";
        System.out.println("STAGE5_TOPOLOGY layout=" + layout + " generation=" + directory.snapshot().generation().value());
    }

    private static void measure(MinecraftServer server, String label, int ticks) {
        if (measurement != null || nativeSprintFinishing != null) throw new IllegalStateException("overlapping measurements");
        if (POSITIONS.isEmpty()) throw new IllegalStateException("no actual furnace workload");
        measurement = new Measurement(label, server.overworld().getGameTime(), ticks, System.nanoTime());
        snapshot(server, label + "-before", Map.of());
        server.tickRateManager().requestGameToSprint(ticks);
        System.out.println("STAGE5_MEASURING label=" + label + " ticks=" + ticks);
    }
    private static void endTick(MinecraftServer server) {
        Measurement finishing = nativeSprintFinishing;
        if (finishing != null && !server.tickRateManager().isSprinting()) {
            if (!server.tickRateManager().isFrozen() || server.overworld().getGameTime() != finishing.lastGameTime)
                throw new IllegalStateException("native sprint changed the completed tick cut");
            nativeSprintFinishing = null;
            System.out.println("STAGE5_MEASURED label=" + finishing.label + " ticks=" + finishing.ticks + " nativeSprintFinished=true");
        }
        if (demandPending) {
            boolean ready = true;
            for (int center : CENTERS) for (int x = center - 1; x <= center + 1; x++) for (int z = -1; z <= 1; z++) {
                var chunk = server.overworld().getChunkSource().getChunkNow(x, z);
                ready &= chunk != null && chunk.getFullStatus().isOrAfter(net.minecraft.server.level.FullChunkStatus.BLOCK_TICKING)
                    && server.overworld().areEntitiesLoaded(net.minecraft.world.level.ChunkPos.pack(x, z));
            }
            if (ready) { demandPending = false; System.out.println("STAGE5_READY nativeFullAndEntityLoaded=" + CENTERS.length * 9); }
        }
        Measurement current = measurement;
        if (current == null) return;
        long now = server.overworld().getGameTime();
        if (now > current.lastGameTime) {
            if (now != current.lastGameTime + 1) throw new IllegalStateException("native measurement clock skipped");
            current.tickNanos.add(System.nanoTime() - tickStart); current.lastGameTime = now;
        }
        if (current.tickNanos.size() == current.ticks) {
            // Let the original sprint finalizer restore its saved frozen state. Freezing
            // here can prevent that finalizer from running, so a later unfreeze command
            // gets overwritten by an unfinished sprint. Publish completion only afterward.
            measurement = null; nativeSprintFinishing = current;
            try (var turn = FullFinishingServices.forServer(server).serial(FullFinishingServices.Phase.SERVER_TASK)) {
                snapshot(server, current.label, Map.of("tickNanos", current.tickNanos,
                    "startGameTime", current.startGameTime, "ticks", current.ticks,
                    "elapsedNanos", System.nanoTime() - current.started));
            }
        }
    }
    private static void snapshot(MinecraftServer server, String label, Map<String, Object> measurementData) {
        try {
            ServerLevel level = server.overworld();
            RegionalServiceDispatcher dispatcher = field(Phoenium.liveRuntime(), "regionalDispatcher", RegionalServiceDispatcher.class);
            List<Map<String, Object>> furnaces = new ArrayList<>();
            for (BlockPos pos : POSITIONS) {
                RoutingKey key = new RoutingKey(new WorldId("minecraft:overworld"), pos.getX() >> 4, pos.getZ() >> 4);
                dispatcher.executeAndDrain(key, RegionalServiceKind.SAVE_CAPTURE, ignored -> {
                    BlockEntity entity = level.getBlockEntity(pos);
                    if (!(entity instanceof FurnaceBlockEntity furnace)) throw new IllegalStateException("native readback lost furnace " + pos);
                    furnaces.add(Map.of("x", pos.getX(), "y", pos.getY(), "z", pos.getZ(),
                        "ingredient", furnace.getItem(0).getCount(), "fuel", furnace.getItem(1).getCount(),
                        "output", furnace.getItem(2).getCount(), "lit", field(furnace, "litTimeRemaining", Integer.class),
                        "cooking", field(furnace, "cookingTimer", Integer.class),
                        "total", field(furnace, "cookingTotalTime", Integer.class)));
                });
            }
            Map<String, Object> report = new LinkedHashMap<>(measurementData);
            report.put("label", label); report.put("layout", layout); report.put("gameTime", level.getGameTime());
            report.put("furnaces", furnaces); report.put("health", Phoenium.liveRuntime().diagnostics());
            report.put("resources", Stage5ResourceMeasurements.snapshot());
            if (label.equals("final")) report.put("nativeCallbacks", Stage5FurnaceObservations.snapshot());
            try { report.put("gameplay", Phoenium.liveRuntime().getClass().getMethod("gameplayMeasurements").invoke(Phoenium.liveRuntime())); }
            catch (NoSuchMethodException frozenStage4) { report.put("gameplay", "unavailable in frozen Stage 4"); }
            Path directory = Path.of(System.getProperty("phoenium.stage5.evidence")); Files.createDirectories(directory);
            Files.writeString(directory.resolve(label + ".json"), new GsonBuilder().setPrettyPrinting().create().toJson(report), StandardOpenOption.CREATE_NEW);
            System.out.println("STAGE5_SNAPSHOT label=" + label + " furnaces=" + furnaces.size());
        } catch (Exception failure) { throw new IllegalStateException("native Stage 5 snapshot failed", failure); }
    }
    private static <T> T field(Object target, String name, Class<T> type) {
        Class<?> owner = target.getClass();
        while (owner != null) {
            try { Field field = owner.getDeclaredField(name); field.setAccessible(true); return type.cast(field.get(target)); }
            catch (NoSuchFieldException absent) { owner = owner.getSuperclass(); }
            catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
        }
        throw new IllegalStateException("missing native field " + name);
    }
    private static final class Measurement {
        final String label; final long startGameTime, started; final int ticks;
        final List<Long> tickNanos = new ArrayList<>(); long lastGameTime;
        Measurement(String label, long gameTime, int ticks, long started) {
            this.label = label; startGameTime = gameTime; lastGameTime = gameTime; this.ticks = ticks; this.started = started;
        }
    }
}
