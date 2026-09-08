package dev.phoenium.stage4probe;

import dev.phoenium.Phoenium;
import dev.phoenium.stage4probe.Stage4Trace;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickPriority;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.phoenium.chunk.FullRegionalEnclosure;
import dev.phoenium.core.WorldId;
import dev.phoenium.live.PhoeniumLiveRuntime;
import dev.phoenium.live.LiveTickReadiness;
import dev.phoenium.region.RegionId;
import dev.phoenium.topology.RegionDirectory;
import dev.phoenium.topology.RoutingKey;
import dev.phoenium.topology.TopologyCoordinator;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Dedicated-server command fixture for real vanilla producers/consumers and ordered comparison. */
public final class Stage4AcceptanceProbe implements DedicatedServerModInitializer {
    public static final BlockPos REGION_A = new BlockPos(8, 65, 8); // chunk (0, 0), A interior
    public static final BlockPos REGION_B = new BlockPos(120, 65, 8); // chunk (7, 0), B interior
    /** Far B interior (chunk 32) used only by the client tracking lifecycle at 8/8. */
    public static final BlockPos REGION_B_FAR = new BlockPos(520, 65, 8);
    /** A distinct block in B keeps the long lived obligation separate from the near B tick. */
    public static final BlockPos REGION_B_FUTURE = new BlockPos(120, 65, 9);
    /** A second pending fluid in the far B cohort proves fluid migration and B-owner execution. */
    public static final BlockPos REGION_B_FAR_FLUID = REGION_B_FAR.east();
    /** A distinct pending block in the far B cohort proves block-tick migration and B execution. */
    public static final BlockPos REGION_B_FAR_BLOCK = REGION_B_FAR.south();
    /** Unloaded B-domain event: the real shouldTickBlocksAt predicate defers it while its
     * owner is moved B -> A -> B by the lifecycle transition. */
    public static final BlockPos REGION_DEFERRED_EVENT = new BlockPos(520, 65, 200);
    @Override public void onInitializeServer() {
        CommandRegistrationCallback.EVENT.register((dispatcher, access, selection) -> dispatcher.register(
            Commands.literal("stage4trace")
                .then(Commands.literal("run").executes(context -> { arm(context.getSource().getLevel()); return 1; }))
                .then(Commands.literal("lifecycle").executes(context -> {
                    runTopologyLifecycle(context.getSource().getLevel());
                    return 1;
                }))
                .then(Commands.literal("split-far").executes(context -> {
                    splitFarTopology(context.getSource().getLevel());
                    return 1;
                }))
                .then(Commands.literal("await-full-far").executes(context -> {
                    awaitFullReadiness(context.getSource().getLevel(), "far",
                        REGION_B_FAR.getX() >> 4, REGION_B_FAR.getZ() >> 4);
                    return 1;
                }))
                .then(Commands.literal("await-full-deferred").executes(context -> {
                    awaitFullReadiness(context.getSource().getLevel(), "deferred",
                        REGION_DEFERRED_EVENT.getX() >> 4, REGION_DEFERRED_EVENT.getZ() >> 4);
                    return 1;
                }))
                .then(Commands.literal("freeze").executes(context -> {
                    context.getSource().getServer().tickRateManager().setFrozen(true);
                    Stage4Trace.clockFrozen(context.getSource().getLevel());
                    return 1;
                }))
                .then(Commands.literal("step")
                    .then(Commands.argument("label", StringArgumentType.word())
                        .then(Commands.argument("ticks", IntegerArgumentType.integer(1, 2400)).executes(context -> {
                            ServerLevel level = context.getSource().getLevel();
                            int ticks = IntegerArgumentType.getInteger(context, "ticks");
                            String label = StringArgumentType.getString(context, "label");
                            Stage4Trace.clockStep(level, label, ticks);
                            if (!level.getServer().tickRateManager().stepGameIfPaused(ticks))
                                throw new IllegalStateException("native tick step rejected while not frozen");
                            return 1;
                        }))))
                .then(Commands.literal("require-block-recipient").executes(context -> {
                    Stage4Trace.requestBlockRecipient();
                    Stage4Trace.event("block.recipient.required");
                    return 1;
                }))
                .then(Commands.literal("require-tracking-lifecycle").executes(context -> {
                    Stage4Trace.requestTrackingLifecycle();
                    Stage4Trace.event("tracking.lifecycle.required");
                    return 1;
                }))
                .then(Commands.literal("block-probe-a").executes(context -> {
                    probeBlockEvent(context.getSource().getLevel(), REGION_A, 4, 21, "a");
                    return 1;
                }))
                .then(Commands.literal("block-probe-b").executes(context -> {
                    probeBlockEvent(context.getSource().getLevel(), REGION_B, 5, 22, "b");
                    return 1;
                }))
                .then(Commands.literal("block-probe-check-a").executes(context -> {
                    Stage4Trace.checkBlockProbe("a", false);
                    return 1;
                }))
                .then(Commands.literal("block-probe-check-b").executes(context -> {
                    Stage4Trace.checkBlockProbe("b", true);
                    return 1;
                }))
                .then(Commands.literal("check-deferred").executes(context -> {
                    boolean pass = Stage4Trace.checkDeferredEvent();
                    context.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                        "STAGE4_TRACE_DEFERRED_CHECK pass=" + pass), false);
                    if (!pass) throw new IllegalStateException("deferred block event was not reached exactly once");
                    return 1;
                }))
                .then(Commands.literal("check-far").executes(context -> {
                    boolean pass = Stage4Trace.checkFarEffects();
                    context.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                        "STAGE4_TRACE_FAR_CHECK pass=" + pass), false);
                    if (!pass) throw new IllegalStateException("far block/fluid obligations were not reached");
                    return 1;
                }))
                .then(Commands.literal("barrier")
                    .then(Commands.argument("label", StringArgumentType.word()).executes(context -> {
                        Stage4Trace.barrier(context.getSource().getLevel(), StringArgumentType.getString(context, "label"));
                        return 1;
                    })))
                .then(Commands.literal("await")
                    .then(Commands.argument("label", StringArgumentType.word())
                        .then(Commands.argument("ticks", IntegerArgumentType.integer(1, 2400)).executes(context -> {
                            Stage4Trace.await(context.getSource().getLevel(), StringArgumentType.getString(context, "label"),
                                IntegerArgumentType.getInteger(context, "ticks"));
                            return 1;
                        }))))
                .then(Commands.literal("reload").executes(context -> {
                    try {
                        Stage4Trace.beginReload(context.getSource().getLevel());
                    } catch (Throwable failure) {
                        System.out.println("STAGE4_TRACE_RELOAD_ERROR " + failure);
                        failure.printStackTrace(System.out);
                        throw failure;
                    }
                    return 1;
                }))
                .then(Commands.literal("finish").executes(context -> {
                    Stage4Trace.Result result = Stage4Trace.finish(context.getSource().getLevel());
                    context.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                        "STAGE4_TRACE_RESULT " + (result.pass() ? "PASS" : "PARTIAL") +
                            " events=" + result.eventCount() + " missing=" + result.missing()), false);
                    return result.pass() ? 1 : 0;
                }))
                .then(Commands.literal("verify").executes(context -> {
                    ServerLevel level = context.getSource().getLevel();
                    boolean reached = level.getBlockState(REGION_B_FUTURE).is(Blocks.REDSTONE_BLOCK);
                    Stage4Trace.event("reload.effect", "reached=" + reached + " state=" + level.getBlockState(REGION_B_FUTURE));
                    context.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                        "STAGE4_TRACE_RELOAD a=" + level.getBlockState(REGION_A) +
                            " b=" + level.getBlockState(REGION_B) + " future=" + level.getBlockState(REGION_B_FUTURE)
                            + " fluid=" + level.getFluidState(REGION_B.east())
                            + " effectReached=" + reached), false);
                    System.out.println("STAGE4_TRACE_RELOAD_EFFECT reached=" + reached + " gameTime=" + level.getGameTime());
                    return 1;
                }))));
    }

    private static void arm(ServerLevel level) {
        if (level.dimension() != Level.OVERWORLD) throw new IllegalStateException("Stage4 fixture requires overworld");
        level.getChunk(REGION_A.getX() >> 4, REGION_A.getZ() >> 4);
        level.getChunk(REGION_B.getX() >> 4, REGION_B.getZ() >> 4);
        level.setChunkForced(REGION_A.getX() >> 4, REGION_A.getZ() >> 4, true);
        level.setChunkForced(REGION_B.getX() >> 4, REGION_B.getZ() >> 4, true);
        Stage4Trace.begin(level);
        if (level.getServer().tickRateManager().isFrozen()) Stage4Trace.clockFrozen(level);
        long now = level.getGameTime();
        level.setBlock(REGION_A, Blocks.NOTE_BLOCK.defaultBlockState(), 3);
        level.setBlock(REGION_B, Blocks.NOTE_BLOCK.defaultBlockState(), 3);
        level.setBlock(REGION_B_FUTURE, Blocks.NOTE_BLOCK.defaultBlockState(), 3);
        level.getBlockTicks().schedule(new ScheduledTick<>(Blocks.NOTE_BLOCK, REGION_A, now + 2, TickPriority.HIGH, 1));
        level.getBlockTicks().schedule(new ScheduledTick<>(Blocks.NOTE_BLOCK, REGION_B, now + 3, TickPriority.NORMAL, 2));
        level.getBlockTicks().schedule(new ScheduledTick<>(Blocks.NOTE_BLOCK, REGION_A, now + 2, TickPriority.HIGH, 1));
        // This future tick must still be pending at the normal save cut and restart.
        level.getBlockTicks().schedule(new ScheduledTick<>(Blocks.NOTE_BLOCK, REGION_B_FUTURE, now + 400, TickPriority.LOW, 99));
        BlockPos fluid = REGION_B.east();
        level.setBlock(fluid, Fluids.WATER.defaultFluidState().createLegacyBlock(), 3);
        // A water placement may enqueue its own producer tick.  Clear that native
        // one-block area before adding the controlled obligation, so the recorded
        // deadline is the exact input shared by the reference and candidate runs.
        level.getFluidTicks().clearArea(new BoundingBox(fluid));
        level.getFluidTicks().schedule(new ScheduledTick<>(Fluids.WATER, fluid, now + 3, TickPriority.LOW, 3));
        level.setBlock(REGION_B_FAR_FLUID, Fluids.WATER.defaultFluidState().createLegacyBlock(), 3);
        level.getFluidTicks().clearArea(new BoundingBox(REGION_B_FAR_FLUID));
        level.getFluidTicks().schedule(new ScheduledTick<>(Fluids.WATER, REGION_B_FAR_FLUID,
            now + 320, TickPriority.LOW, 33));
        level.setBlock(REGION_B_FAR_BLOCK, Blocks.NOTE_BLOCK.defaultBlockState(), 3);
        level.getBlockTicks().schedule(new ScheduledTick<>(Blocks.NOTE_BLOCK, REGION_B_FAR_BLOCK,
            now + 320, TickPriority.NORMAL, 77));
        level.blockEvent(REGION_A, Blocks.NOTE_BLOCK, 1, 7);
        if (Boolean.getBoolean("phoenium.stage3.chunkTrace"))
            level.setBlock(REGION_DEFERRED_EVENT, Blocks.NOTE_BLOCK.defaultBlockState(), 3);
        if (Boolean.getBoolean("phoenium.stage3.chunkTrace"))
            level.blockEvent(REGION_DEFERRED_EVENT, Blocks.NOTE_BLOCK, 3, 11);
        Stage4Trace.event("trace.armed", "a=" + REGION_A + " b=" + REGION_B + " now=" + now);
        Stage4Trace.event("tick.pending.input", "fluid=" + REGION_B_FAR_FLUID + " trigger=" + (now + 320)
            + " present=" + level.getFluidTicks().hasScheduledTick(REGION_B_FAR_FLUID, Fluids.WATER));
        String routes = Boolean.getBoolean("phoenium.stage4.candidate")
            ? " routeA=" + Phoenium.liveRuntime().routeDiagnostic(level.dimension().identifier().toString(), REGION_A.getX() >> 4, REGION_A.getZ() >> 4)
                + " routeB=" + Phoenium.liveRuntime().routeDiagnostic(level.dimension().identifier().toString(), REGION_B.getX() >> 4, REGION_B.getZ() >> 4)
            : " mode=reference";
        System.out.println("STAGE4_TRACE_ARMED a=" + REGION_A + " b=" + REGION_B + " now=" + now + routes);
    }

    private static void probeBlockEvent(ServerLevel level, BlockPos pos, int paramA, int paramB, String label) {
        Stage4Trace.beginBlockProbe(label, pos);
        level.blockEvent(pos, Blocks.NOTE_BLOCK, paramA, paramB);
        Stage4Trace.event("block.probe.input", "label=" + label + " pos=" + pos + " a=" + paramA + " b=" + paramB
            + " gameTime=" + level.getGameTime());
    }

    /**
     * Drives the actual live FullRegionalEnclosure and its private coordinator through the
     * protocol used by RegionalWorldServices.  Reflection is deliberately confined to this
     * disposable probe: the production runtime exposes the enclosure only to its installed
     * dispatcher callbacks and has no public test command for a split proposal.
     */
    private static void runTopologyLifecycle(ServerLevel level) {
        Stage4Trace.requestLifecycle();
        PhoeniumLiveRuntime live = Phoenium.liveRuntime();
        String worldName = level.dimension().identifier().toString();
        WorldId world = new WorldId(worldName);
        RoutingKey aKey = new RoutingKey(world, REGION_A.getX() >> 4, REGION_A.getZ() >> 4);
        RoutingKey bKey = new RoutingKey(world, REGION_B.getX() >> 4, REGION_B.getZ() >> 4);
        Stage4Trace.event("topology.before", "gameTime=" + level.getGameTime()
            + " routeA=" + live.routeDiagnostic(worldName, aKey.x(), aKey.z())
            + " routeB=" + live.routeDiagnostic(worldName, bKey.x(), bKey.z()));
        try {
            FullRegionalEnclosure enclosure = privateField(live, "fullEnclosure", FullRegionalEnclosure.class);
            RegionDirectory directory = privateField(live, "directory", RegionDirectory.class);
            Set<RoutingKey> closure = Set.of(aKey, bKey);
            boolean merged = enclosure.enclose(closure);
            for (int attempt = 0; !merged && attempt < 8; attempt++) {
                // enclose() may publish and return false to require route recapture.  Re-entering
                // the public enclosure observes that real publication; advance() is only the
                // installed coordinator boundary, never a synthetic directory publication.
                if (!merged) merged = enclosure.enclose(closure);
                if (!merged) merged = enclosure.advance();
            }
            if (!merged || !sameOwner(directory, aKey, bKey))
                throw new IllegalStateException("coordinator merge did not converge");
            Stage4Trace.event("topology.merge", "gameTime=" + level.getGameTime()
                + " routeA=" + live.routeDiagnostic(worldName, aKey.x(), aKey.z())
                + " routeB=" + live.routeDiagnostic(worldName, bKey.x(), bKey.z())
                + " services=" + serviceState(live, level));
            // The separated split is intentionally issued by split-far after the client has
            // loaded and settled the final distant B viewport.  This nearby command proves the
            // genuine A0/B7 merge without allowing a readiness shortcut to hide a later merge.
            System.out.println("STAGE4_TRACE_TOPOLOGY_MERGE gameTime=" + level.getGameTime());
        } catch (Throwable failure) {
            Stage4Trace.event("topology.error", failure.getClass().getName() + ":" + failure.getMessage());
            throw failure instanceof RuntimeException runtime ? runtime : new IllegalStateException(failure);
        }
    }

    private static void splitFarTopology(ServerLevel level) {
        PhoeniumLiveRuntime live = Phoenium.liveRuntime();
        String worldName = level.dimension().identifier().toString();
        WorldId world = new WorldId(worldName);
        RoutingKey aKey = new RoutingKey(world, REGION_A.getX() >> 4, REGION_A.getZ() >> 4);
        RoutingKey bKey = new RoutingKey(world, REGION_B_FAR.getX() >> 4, REGION_B_FAR.getZ() >> 4);
        try {
            if (!Boolean.getBoolean("phoenium.stage3.chunkTrace"))
                throw new IllegalStateException("lifecycle fixture requires the declared chunkTrace support domain");
            FullRegionalEnclosure enclosure = privateField(live, "fullEnclosure", FullRegionalEnclosure.class);
            RegionDirectory directory = privateField(live, "directory", RegionDirectory.class);
            RegionId a = new RegionId(world, "stage3-fixture-a");
            RegionId b = new RegionId(world, "stage3-fixture-b");
            Map<RoutingKey, RegionId> split = new LinkedHashMap<>();
            // The post-merge split uses a wide, separated B interior so the far tracking/tick
            // cohort stays distinct at view/simulation 8/8, with a full one-chunk halo.
            for (var entry : directory.snapshot().routes().entrySet()) {
                RoutingKey key = entry.getKey();
                if (!key.world().equals(world)) { split.put(key, entry.getValue().region()); continue; }
                boolean inB = key.x() >= 12 && key.x() <= 54
                    && key.z() >= -22 && key.z() <= 22;
                split.put(key, inB ? b : a);
            }
            TopologyCoordinator coordinator = privateField(enclosure, "coordinator", TopologyCoordinator.class);
            coordinator.request(new TopologyCoordinator.Transition(Set.of(a, b), split));
            boolean advanced = false;
            for (int attempt = 0; attempt < 8 && !advanced; attempt++) advanced = coordinator.advance();
            if (!advanced || !directory.resolve(bKey).orElseThrow().region().equals(b))
                throw new IllegalStateException("coordinator split did not converge");
            Stage4Trace.event("topology.split", "gameTime=" + level.getGameTime()
                + " routeA=" + live.routeDiagnostic(worldName, aKey.x(), aKey.z())
                + " routeB=" + live.routeDiagnostic(worldName, bKey.x(), bKey.z())
                + " services=" + serviceState(live, level));
            System.out.println("STAGE4_TRACE_TOPOLOGY_SPLIT gameTime=" + level.getGameTime());
        } catch (Throwable failure) {
            Stage4Trace.event("topology.error", failure.getClass().getName() + ":" + failure.getMessage());
            throw failure instanceof RuntimeException runtime ? runtime : new IllegalStateException(failure);
        }
    }

    /**
     * Waits on the original server holder futures after a real distance update.  The
     * LiveTickReadiness receipt is backed by the ChunkMap's current FULL candidates; this
     * command only reads and verifies it, and fails when the requested cohort is absent or
     * still incomplete.  It does not create a ticket, future, holder, or synthetic readiness.
     */
    private static void awaitFullReadiness(ServerLevel level, String label, int centerX, int centerZ) {
        // Use the server's unchanged native simulation distance as the demanded
        // cohort.  The query includes one native dependency halo chunk around it.
        int simulationRadius = level.getServer().getPlayerList().getSimulationDistance();
        int radius = simulationRadius + 1;
        LiveTickReadiness.Receipt receipt = LiveTickReadiness.await(level);
        List<ChunkHolder> target;
        try (var holders = ((LiveTickReadiness.MapAccess) level.getChunkSource().chunkMap)
                .phoenium$tickCandidates(ChunkStatus.FULL)) {
            target = holders
                .filter(holder -> ChunkLevel.fullStatus(holder.getTicketLevel()).isOrAfter(FullChunkStatus.BLOCK_TICKING))
                .filter(holder -> Math.abs(holder.getPos().x() - centerX) <= radius
                    && Math.abs(holder.getPos().z() - centerZ) <= radius)
                .toList();
        }
        Set<Long> actual = new HashSet<>();
        target.forEach(holder -> actual.add(holder.getPos().pack()));
        Set<Long> requested = new HashSet<>();
        for (int x = centerX - simulationRadius; x <= centerX + simulationRadius; x++)
            for (int z = centerZ - simulationRadius; z <= centerZ + simulationRadius; z++)
                requested.add(net.minecraft.world.level.ChunkPos.pack(x, z));
        boolean complete = receipt.current() && actual.contains(centerKey(centerX, centerZ))
            && actual.containsAll(requested) && target.stream().allMatch(holder ->
            successful(holder.getFullChunkFuture()) && successful(holder.getTickingChunkFuture()));
        if (!complete)
            throw new IllegalStateException("FULL readiness incomplete label=" + label
                + " target=" + centerX + "," + centerZ + " radius=" + radius
                + " requested=" + requested.size() + " missing=" + requested.stream().filter(key -> !actual.contains(key)).count()
                + " candidates=" + target.size() + " allCandidates=" + receipt.count());
        Stage4Trace.fullReadiness(label, centerX, centerZ, radius, target.size(), receipt.count());
        System.out.println("STAGE4_TRACE_FULL_READY label=" + label + " center=" + centerX + "," + centerZ
            + " radius=" + radius + " target=" + target.size() + " candidates=" + receipt.count());
    }

    private static long centerKey(int x, int z) {
        return net.minecraft.world.level.ChunkPos.pack(x, z);
    }

    private static boolean successful(java.util.concurrent.CompletableFuture<? extends net.minecraft.server.level.ChunkResult<?>> future) {
        if (future == null || !future.isDone() || future.isCompletedExceptionally() || future.isCancelled()) return false;
        net.minecraft.server.level.ChunkResult<?> result = future.getNow(null);
        return result != null && result.isSuccess() && result.orElse(null) != null;
    }

    private static boolean sameOwner(RegionDirectory directory, RoutingKey first, RoutingKey second) {
        var a = directory.resolve(first).orElseThrow();
        var b = directory.resolve(second).orElseThrow();
        return a.region().equals(b.region()) && a.generation().equals(b.generation());
    }

    private static String serviceState(PhoeniumLiveRuntime live, ServerLevel level) {
        var services = live.regionalServices().world(level);
        return "blocks=" + services.blockTicks().measurements()
            + " fluids=" + services.fluidTicks().measurements()
            + " events=" + services.events().measurements();
    }

    private static <T> T privateField(Object target, String name, Class<T> type) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(target));
    }

}
