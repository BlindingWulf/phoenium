package dev.phoenium.stage4probe;

import dev.phoenium.Phoenium;
import dev.phoenium.stage4probe.Stage4TeleportTopology;
import dev.phoenium.stage4probe.Stage4Trace;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Dedicated-server command fixture for real vanilla producers/consumers and ordered comparison. */
public final class Stage4AcceptanceProbe implements DedicatedServerModInitializer {
    /** One retained server-side receipt per player; completion removes it only after success. */
    private static final Map<UUID, Stage4TeleportTopology.Prepared> TELEPORT_RECEIPTS = new HashMap<>();
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
        // The restart runner may opt into this test-only native clock boundary.  It is set
        // during SERVER_STARTED, before the first ordinary world tick can consume the saved
        // future obligation; no production runtime code observes this property.
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            if (Boolean.getBoolean("phoenium.stage4.restartFreeze")) {
                server.tickRateManager().setFrozen(true);
                System.out.println("STAGE4_TRACE_STARTUP_CLOCK_FROZEN gameTime="
                    + server.overworld().getGameTime());
            }
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, access, selection) -> dispatcher.register(
            Commands.literal("stage4trace")
                .then(Commands.literal("run").executes(context -> { arm(context.getSource().getLevel()); return 1; }))
                .then(Commands.literal("prepare").executes(context -> {
                    prepareChunks(context.getSource().getLevel());
                    return 1;
                }))
                .then(Commands.literal("prepare-a").executes(context -> {
                    prepareChunk(context.getSource().getLevel(), REGION_A, "A");
                    return 1;
                }))
                .then(Commands.literal("prepare-b").executes(context -> {
                    prepareChunk(context.getSource().getLevel(), REGION_B, "B");
                    return 1;
                }))
                .then(Commands.literal("prepare-far").executes(context -> {
                    prepareChunk(context.getSource().getLevel(), REGION_B_FAR, "far");
                    return 1;
                }))
                .then(Commands.literal("inspect-far").executes(context -> {
                    inspectFarDemand(context.getSource().getLevel(), "");
                    return 1;
                }))
                .then(Commands.literal("inspect-far-late").executes(context -> {
                    inspectFarDemand(context.getSource().getLevel(), "_LATE");
                    return 1;
                }))
                .then(Commands.literal("inspect-demand")
                    .then(Commands.argument("chunkX", IntegerArgumentType.integer())
                        .then(Commands.argument("chunkZ", IntegerArgumentType.integer()).executes(context -> {
                            inspectDemand(context.getSource().getLevel(),
                                IntegerArgumentType.getInteger(context, "chunkX"),
                                IntegerArgumentType.getInteger(context, "chunkZ"));
                            return 1;
                        }))))
                .then(Commands.literal("prepare-deferred").executes(context -> {
                    prepareChunk(context.getSource().getLevel(), REGION_DEFERRED_EVENT, "deferred");
                    return 1;
                }))
                .then(Commands.literal("prepare-teleport")
                    .then(Commands.argument("x", IntegerArgumentType.integer())
                        .then(Commands.argument("y", IntegerArgumentType.integer())
                            .then(Commands.argument("z", IntegerArgumentType.integer()).executes(context -> {
                                prepareTeleport(context.getSource().getLevel(),
                                    IntegerArgumentType.getInteger(context, "x"),
                                    IntegerArgumentType.getInteger(context, "y"),
                                    IntegerArgumentType.getInteger(context, "z"));
                                return 1;
                            })))))
                .then(Commands.literal("complete-teleport").executes(context -> {
                    completeTeleport(context.getSource().getLevel());
                    return 1;
                }))
                .then(Commands.literal("release-temporary").executes(context -> {
                    releaseTemporaryChunks(context.getSource().getLevel());
                    return 1;
                }))
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
                .then(Commands.literal("check-deferred-ineligible").executes(context -> {
                    Stage4Trace.observeDeferredEligibility(context.getSource().getLevel(), "before-viewport", false);
                    return 1;
                }))
                .then(Commands.literal("check-deferred-eligible").executes(context -> {
                    Stage4Trace.observeDeferredEligibility(context.getSource().getLevel(), "after-viewport", true);
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
                .then(Commands.literal("step-far-effects").executes(context -> {
                    scheduleFarEffects(context.getSource().getLevel());
                    return 1;
                }))
                .then(Commands.literal("step-restored").then(Commands.argument("maximumTicks",
                    IntegerArgumentType.integer(1, 2400)).executes(context -> {
                        scheduleRestoredBefore(context.getSource().getLevel(),
                            IntegerArgumentType.getInteger(context, "maximumTicks"));
                        return 1;
                    })))
                .then(Commands.literal("check-restored-before").executes(context -> {
                    checkRestoredPhase(context.getSource().getLevel(), "before", true, 0, -1);
                    return 1;
                }))
                .then(Commands.literal("check-restored-deadline").executes(context -> {
                    checkRestoredPhase(context.getSource().getLevel(), "deadline", false, 1, 0);
                    return 1;
                }))
                .then(Commands.literal("check-restored-after").executes(context -> {
                    checkRestoredPhase(context.getSource().getLevel(), "after", false, 1, 1);
                    return 1;
                }))
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
                .then(Commands.literal("clear-block-probe-support").executes(context -> {
                    clearBlockProbeSupport(context.getSource().getLevel());
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
                    if (!pass) {
                        ServerLevel level = context.getSource().getLevel();
                        var chunk = level.getChunkSource().getChunkNow(REGION_B_FAR.getX() >> 4, REGION_B_FAR.getZ() >> 4);
                        System.out.println("STAGE4_TRACE_FAR_PENDING now=" + level.getGameTime() + " chunk=" + chunk
                            + " blocks=" + (chunk == null ? "absent" : ((net.minecraft.world.ticks.LevelChunkTicks<?>) chunk.getBlockTicks()).getAll().toList())
                            + " fluids=" + (chunk == null ? "absent" : ((net.minecraft.world.ticks.LevelChunkTicks<?>) chunk.getFluidTicks()).getAll().toList()));
                        Stage4Trace.finish(level);
                        throw new IllegalStateException("far block/fluid obligations were not reached");
                    }
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
                        if (Boolean.getBoolean("phoenium.stage4.restartFreeze")) {
                            context.getSource().getServer().tickRateManager().setFrozen(true);
                        }
                        // Force the real saved target chunk through the normal ServerChunkCache
                        // load path before arming the observer.  The LevelChunkTicks mixin records
                        // the native unpack currentTick and restored trigger; it does not inject
                        // or retime a pending obligation.
                        context.getSource().getLevel().getChunkSource().getChunk(
                            REGION_B_FUTURE.getX() >> 4, REGION_B_FUTURE.getZ() >> 4,
                            ChunkStatus.FULL, false);
                        Stage4Trace.beginReload(context.getSource().getLevel());
                        if (context.getSource().getServer().tickRateManager().isFrozen())
                            Stage4Trace.reloadClockFrozen(context.getSource().getLevel());
                        Stage4Trace.reloadEligibility(context.getSource().getLevel(),
                            context.getSource().getLevel().shouldTickBlocksAt(REGION_B_FUTURE));
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
                    var futureState = level.getBlockState(REGION_B_FUTURE);
                    boolean reached = futureState.is(Blocks.STONE_BUTTON)
                        && !futureState.getValue(ButtonBlock.POWERED);
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

    /**
     * Advances the frozen native clock from the restored unpacked deadline.  The deadline is
     * read from the actual ScheduledTick produced by LevelChunkTicks.unpack, so save latency or
     * restart timing cannot turn a fixed wall-clock wait into a false callback assertion.
     */
    private static void scheduleRestoredBefore(ServerLevel level, int maximumTicks) {
        if (!level.getServer().tickRateManager().isFrozen())
            throw new IllegalStateException("restored deadline requires the native clock to be frozen");
        long current = level.getGameTime();
        long trigger = Stage4Trace.restoredUnpackTrigger();
        int matches = Stage4Trace.restoredUnpackMatches();
        if (trigger < 0 || matches != 1)
            throw new IllegalStateException("restored target unpack was not exact: current="
                + Stage4Trace.restoredUnpackCurrent() + " trigger=" + trigger + " matches=" + matches);
        long before = trigger - current - 1;
        if (before < 0 || before + 2 > maximumTicks)
            throw new IllegalStateException("restored deadline outside bounded step: now=" + current
                + " trigger=" + trigger + " before=" + before + " maximum=" + maximumTicks);
        if (before < 1)
            throw new IllegalStateException("restored deadline has no observable pre-deadline step: now=" + current
                + " trigger=" + trigger);
        stepNative(level, "restored-before-deadline", before);
        System.out.println("STAGE4_TRACE_RELOAD_STEP_SCHEDULED phase=before ticks=" + before
            + " currentTick=" + current + " trigger=" + trigger);
    }

    private static void checkRestoredPhase(ServerLevel level, String phase,
                                            boolean powered, long callbackCount, long triggerOffset) {
        var state = level.getBlockState(REGION_B_FUTURE);
        Boolean actualPowered = state.hasProperty(ButtonBlock.POWERED)
            ? state.getValue(ButtonBlock.POWERED) : null;
        long observed = Stage4Trace.restoredCallbackCount();
        long trigger = Stage4Trace.restoredUnpackTrigger();
        long expectedGameTime = trigger + triggerOffset;
        boolean pass = state.is(Blocks.STONE_BUTTON)
            && Boolean.valueOf(powered).equals(actualPowered) && observed == callbackCount
            && trigger >= 0 && level.getGameTime() == expectedGameTime;
        Stage4Trace.event("reload.effect.phase", "phase=" + phase + " gameTime=" + level.getGameTime()
            + " powered=" + actualPowered + " callbacks=" + observed
            + " expectedPowered=" + powered + " expectedCallbacks=" + callbackCount
            + " expectedGameTime=" + expectedGameTime + " pass=" + pass);
        System.out.println("STAGE4_TRACE_RELOAD_PHASE phase=" + phase + " gameTime=" + level.getGameTime()
            + " powered=" + actualPowered + " callbacks=" + observed
            + " expectedGameTime=" + expectedGameTime + " pass=" + pass);
        if (!pass) throw new IllegalStateException("restored phase mismatch phase=" + phase
            + " state=" + state + " callbacks=" + observed);
    }

    private static void scheduleFarEffects(ServerLevel level) {
        if (!level.getServer().tickRateManager().isFrozen())
            throw new IllegalStateException("far deadline requires the native clock to be frozen");
        var chunk = level.getChunkSource().getChunkNow(REGION_B_FAR.getX() >> 4, REGION_B_FAR.getZ() >> 4);
        if (chunk == null) throw new IllegalStateException("far deadline has no current FULL chunk");
        var blocks = ((net.minecraft.world.ticks.LevelChunkTicks<?>) chunk.getBlockTicks()).getAll()
            .filter(tick -> tick.pos().equals(REGION_B_FAR_BLOCK) && tick.type() == Blocks.NOTE_BLOCK).toList();
        var fluids = ((net.minecraft.world.ticks.LevelChunkTicks<?>) chunk.getFluidTicks()).getAll()
            .filter(tick -> tick.pos().equals(REGION_B_FAR_FLUID) && tick.type() == Fluids.WATER).toList();
        if (blocks.size() != 1 || fluids.size() != 1 || blocks.getFirst().triggerTick() != fluids.getFirst().triggerTick())
            throw new IllegalStateException("far native obligation identity/deadline changed: " + blocks + " / " + fluids);
        long trigger = blocks.getFirst().triggerTick();
        long steps = trigger - level.getGameTime() + 1;
        if (steps < 1 || steps > 600) throw new IllegalStateException("far native deadline outside bounded step");
        Stage4Trace.event("clock.far.deadline", "now=" + level.getGameTime() + " trigger=" + trigger + " steps=" + steps);
        stepNative(level, "far-effects", steps);
    }

    private static void stepNative(ServerLevel level, String label, long ticks) {
        if (ticks < 1 || ticks > 2400) throw new IllegalArgumentException("invalid native step " + ticks);
        if (ticks > Integer.MAX_VALUE) throw new IllegalArgumentException("native step overflow");
        Stage4Trace.clockStep(level, label, (int) ticks);
        if (!level.getServer().tickRateManager().stepGameIfPaused((int) ticks))
            throw new IllegalStateException("native restored step rejected while frozen: " + label);
    }

    /**
     * Establishes the real coordinator-backed ownership scope used around a server teleport.
     * This command is deliberately separate from the vanilla {@code /tp}: the client harness
     * sends the latter unchanged, waits for its native ACK and exact FULL payload, then invokes
     * {@link #completeTeleport} at a later owner-free command boundary.
     */
    private static void prepareTeleport(ServerLevel level, int x, int y, int z) {
        ServerPlayer player = level.players().stream().findFirst()
            .orElseThrow(() -> new IllegalStateException("teleport fixture player is not connected"));
        UUID id = player.getUUID();
        if (TELEPORT_RECEIPTS.containsKey(id))
            throw new IllegalStateException("teleport fixture already has an unsettled receipt for " + id);
        try {
            Stage4TeleportTopology.Prepared prepared = Stage4TeleportTopology.prepare(level, player,
                new BlockPos(x, y, z));
            TELEPORT_RECEIPTS.put(id, prepared);
        } catch (RuntimeException failure) {
            failure.printStackTrace();
            throw failure;
        }
    }

    private static boolean reportedFailure;
    public static void observeTeleportReadiness() {
        var live = Phoenium.liveRuntime();
        if (!reportedFailure && live != null && !live.failureDescription().isEmpty()) {
            reportedFailure = true;
            System.out.println("STAGE4_TRACE_PRODUCTION_FAILURE " + live.failureDescription()
                + " diagnostics=" + live.diagnostics().toLogLine());
            Object barrier = fieldValue(live, "barrier");
            if (barrier instanceof dev.phoenium.failure.FailureBarrier failureBarrier)
                failureBarrier.record().ifPresent(record -> record.cause().printStackTrace());
        }
        for (var entry : List.copyOf(TELEPORT_RECEIPTS.entrySet())) {
            var prepared = entry.getValue();
            prepared.observeDestinationFull();
            if (prepared.observeCompletion()) TELEPORT_RECEIPTS.remove(entry.getKey());
        }
    }

    /** Completes and only then retires the matching prepared teleport receipt. */
    private static void completeTeleport(ServerLevel level) {
        ServerPlayer player = level.players().stream().findFirst()
            .orElseThrow(() -> new IllegalStateException("teleport fixture player is not connected"));
        UUID id = player.getUUID();
        Stage4TeleportTopology.Prepared prepared = TELEPORT_RECEIPTS.get(id);
        if (prepared == null)
            throw new IllegalStateException("teleport fixture has no prepared receipt for " + id);
        try {
            prepared.confirmClientFull(player);
            if (prepared.observeCompletion()) TELEPORT_RECEIPTS.remove(id);
        } catch (RuntimeException failure) {
            failure.printStackTrace();
            throw failure;
        }
    }

    private static void arm(ServerLevel level) {
        if (level.dimension() != Level.OVERWORLD) throw new IllegalStateException("Stage4 fixture requires overworld");
        if (level.getChunkSource().getChunk(REGION_A.getX() >> 4, REGION_A.getZ() >> 4,
                ChunkStatus.FULL, false) == null
            || level.getChunkSource().getChunk(REGION_B.getX() >> 4, REGION_B.getZ() >> 4,
                ChunkStatus.FULL, false) == null
            || level.getChunkSource().getChunk(REGION_B_FAR.getX() >> 4, REGION_B_FAR.getZ() >> 4,
                ChunkStatus.FULL, false) == null
            || level.getChunkSource().getChunk(REGION_DEFERRED_EVENT.getX() >> 4,
                REGION_DEFERRED_EVENT.getZ() >> 4, ChunkStatus.FULL, false) == null)
            throw new IllegalStateException("Stage4 fixture chunks were not prepared before clock freeze");
        Stage4Trace.begin(level);
        if (level.getServer().tickRateManager().isFrozen()) Stage4Trace.clockFrozen(level);
        preparePlayerPads(level);
        long now = level.getGameTime();
        level.setBlock(REGION_A, Blocks.NOTE_BLOCK.defaultBlockState(), 3);
        level.setBlock(REGION_B, Blocks.NOTE_BLOCK.defaultBlockState(), 3);
        // A directly powered floor button has no placement producer tick.  Its explicit native
        // deadline is persisted, and ButtonBlock.tick turns POWERED off through the original
        // callback after reload; the probe only observes that resulting state.
        var futureButton = Blocks.STONE_BUTTON.defaultBlockState()
            .setValue(BlockStateProperties.ATTACH_FACE, AttachFace.FLOOR)
            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
            .setValue(ButtonBlock.POWERED, true);
        level.setBlock(REGION_B_FUTURE, futureButton, 3);
        level.getBlockTicks().schedule(new ScheduledTick<>(Blocks.NOTE_BLOCK, REGION_A, now + 2, TickPriority.HIGH, 1));
        level.getBlockTicks().schedule(new ScheduledTick<>(Blocks.NOTE_BLOCK, REGION_B, now + 3, TickPriority.NORMAL, 2));
        level.getBlockTicks().schedule(new ScheduledTick<>(Blocks.NOTE_BLOCK, REGION_A, now + 2, TickPriority.HIGH, 1));
        // This future tick must still be pending at the normal save cut and restart.
        level.getBlockTicks().schedule(new ScheduledTick<>(Blocks.STONE_BUTTON, REGION_B_FUTURE, now + 700, TickPriority.LOW, 99));
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

    /** Keep the real client at the commanded y=65 during explicit native steps. */
    private static void preparePlayerPads(ServerLevel level) {
        for (BlockPos center : List.of(REGION_A, REGION_B, REGION_B_FAR, REGION_DEFERRED_EVENT)) {
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++)
                level.setBlock(new BlockPos(center.getX() + dx, 64, center.getZ() + dz),
                    Blocks.STONE.defaultBlockState(), 3);
        }
        Stage4Trace.event("fixture.player.pads", "y=64 centers=" + REGION_A + ";" + REGION_B + ";"
            + REGION_B_FAR + ";" + REGION_DEFERRED_EVENT);
    }

    private static void prepareChunks(ServerLevel level) {
        prepareChunk(level, REGION_A, "A");
        prepareChunk(level, REGION_B, "B");
    }

    /**
     * The client drives each point into the native distance path before issuing this command.
     * Consequently this command only retains the already completed holder and never calls a
     * synchronous getChunk/FULL wait from the server command thread.
     */
    private static void prepareChunk(ServerLevel level, BlockPos pos, String label) {
        if (level.dimension() != Level.OVERWORLD) throw new IllegalStateException("Stage4 fixture requires overworld");
        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        level.setChunkForced(chunkX, chunkZ, true);
        System.out.println("STAGE4_TRACE_PREPARED_POINT label=" + label + " route=(" + chunkX + "," + chunkZ
            + ") gameTime=" + level.getGameTime());
        if (label.equals("B"))
            System.out.println("STAGE4_TRACE_PREPARED routes=(0,0),(7,0) gameTime=" + level.getGameTime());
        if (label.equals("deferred"))
            System.out.println("STAGE4_TRACE_PREPARED_FAR routes=(32,0),(32,12) gameTime=" + level.getGameTime());
    }

    /**
     * Bounded evidence for a real player-distance demand.  This command only reads the native
     * ChunkMap/DistanceManager state immediately after the teleport; it does not create tickets,
     * request generation, or substitute a server-side readiness assertion for the client receipt.
     */
    private static void inspectFarDemand(ServerLevel level, String markerSuffix) {
        final int centerX = REGION_B_FAR.getX() >> 4;
        final int centerZ = REGION_B_FAR.getZ() >> 4;
        var map = level.getChunkSource().chunkMap;
        var distance = map.getDistanceManager();
        int simulation = level.getServer().getPlayerList().getSimulationDistance();
        var player = level.players().stream().findFirst().orElse(null);
        String playerState = player == null ? "player=none"
            : "player=" + player.getX() + "," + player.getY() + "," + player.getZ()
                + " lastSection=" + player.getLastSectionPos()
                + " distanceToCenter=" + Math.sqrt(player.distanceToSqr(centerX * 16 + 8, 65, centerZ * 16 + 8));
        System.out.println("STAGE4_TRACE_FAR_DEMAND" + markerSuffix + " center=" + centerX + "," + centerZ
            + " simulationDistance=" + simulation + " " + playerState);
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            int x = centerX + dx;
            int z = centerZ + dz;
            long key = net.minecraft.world.level.ChunkPos.pack(x, z);
            ChunkHolder holder = map.getUpdatingChunkIfPresent(key);
            String holderState = holder == null ? "holder=absent" : "holder=present"
                + " ticket=" + holder.getTicketLevel()
                + " fullStatus=" + holder.getFullStatus()
                + " latest=" + holder.getLatestStatus()
                + " persisted=" + holder.getPersistedStatus()
                + " fullFuture=" + futureState(holder.getFullChunkFuture())
                + " tickingFuture=" + futureState(holder.getTickingChunkFuture())
                + " futures=" + holder.getAllFutures().stream()
                    .map(pair -> pair.getFirst() + ":" + futureState(pair.getSecond()))
                    .collect(java.util.stream.Collectors.joining(","));
            String demand = "chunk=" + x + "," + z
                + " tracked=" + (player != null && map.isChunkTracked(player, x, z))
                + " chunkLevel=" + distance.getChunkLevel(key, true)
                + " blockRange=" + distance.inBlockTickingRange(key)
                + " entityRange=" + distance.inEntityTickingRange(key)
                + " nearby=" + distance.hasPlayersNearby(key);
            System.out.println("STAGE4_TRACE_FAR_DEMAND_ROW" + markerSuffix + " " + demand + " " + holderState);
        }
        System.out.println("STAGE4_TRACE_FAR_DEMAND_DONE" + markerSuffix + " center=" + centerX + "," + centerZ);
    }

    /**
     * Capture the native distance prerequisite at the first client FULL miss.  This is
     * deliberately observational: it does not add a ticket or request a chunk.  The command
     * accepts arbitrary chunk coordinates so the same diagnostic can be reused for A/B/far
     * points without encoding a fixture-specific assumption in the probe.
     */
    static void inspectDemand(ServerLevel level, int chunkX, int chunkZ) {
        var map = level.getChunkSource().chunkMap;
        var distance = map.getDistanceManager();
        var player = level.players().stream().findFirst().orElse(null);
        String playerState = player == null ? "player=none" : "player=" + player.getX() + "," + player.getY()
            + "," + player.getZ() + " lastSection=" + player.getLastSectionPos()
            + " chunkTrackingView=" + player.getChunkTrackingView();
        String connectionState = "connection=none";
        if (player != null && player.connection != null) {
            Object awaitingTeleport = fieldValue(player.connection, "awaitingTeleport");
            Object awaitingPosition = fieldValue(player.connection, "awaitingPositionFromClient");
            Object awaitingTime = fieldValue(player.connection, "awaitingTeleportTime");
            connectionState = "connection=present awaitingTeleport=" + awaitingTeleport
                + " awaitingPositionFromClient=" + awaitingPosition + " awaitingTeleportTime=" + awaitingTime
                + " lastGood=" + fieldValue(player.connection, "lastGoodX") + ","
                + fieldValue(player.connection, "lastGoodY") + "," + fieldValue(player.connection, "lastGoodZ")
                + " packets=" + Phoenium.liveRuntime().packetDiagnostics();
        }
        long key = net.minecraft.world.level.ChunkPos.pack(chunkX, chunkZ);
        ChunkHolder holder = map.getUpdatingChunkIfPresent(key);
        String holderState = holder == null ? "holder=absent" : "holder=present ticket=" + holder.getTicketLevel()
            + " fullStatus=" + holder.getFullStatus() + " latest=" + holder.getLatestStatus()
            + " persisted=" + holder.getPersistedStatus() + " fullFuture=" + futureState(holder.getFullChunkFuture())
            + " tickingFuture=" + futureState(holder.getTickingChunkFuture())
            + " entityTickingFuture=" + futureState(holder.getEntityTickingChunkFuture());
        String ticketState = "tickets=" + ticketSummary(map, key);
        String distanceSources = "playersPerChunk=" + playersPerChunk(distance, key)
            + " loadingTracker=" + trackerLevel(distance, "loadingChunkTracker", key)
            + " simulationTracker=" + trackerLevel(distance, "simulationChunkTracker", key)
            + " postTeleport=" + ticketState;
        System.out.println("STAGE4_TRACE_DEMAND_DIAGNOSTIC chunk=" + chunkX + "," + chunkZ + " " + playerState
            + " " + connectionState + " tracked=" + (player != null && map.isChunkTracked(player, chunkX, chunkZ))
            + " chunkLevel=" + distance.getChunkLevel(key, true)
            + " blockRange=" + distance.inBlockTickingRange(key)
            + " entityRange=" + distance.inEntityTickingRange(key)
            + " nearby=" + distance.hasPlayersNearby(key) + " " + distanceSources + " " + holderState);
    }

    private static String playersPerChunk(Object distance, long key) {
        try {
            Object value = ((it.unimi.dsi.fastutil.longs.Long2ObjectMap<?>)
                fieldValue(distance, "playersPerChunk")).get(key);
            return value == null ? "[]" : value.toString();
        } catch (Throwable failure) {
            return "<unavailable:" + failure.getClass().getSimpleName() + ">";
        }
    }

    private static String trackerLevel(Object distance, String fieldName, long key) {
        try {
            Object tracker = fieldValue(distance, fieldName);
            Object chunks = fieldValue(tracker, "chunks");
            if (chunks instanceof it.unimi.dsi.fastutil.longs.Long2ByteMap levels)
                return levels.containsKey(key) ? Byte.toString(levels.get(key)) : "absent";
            return "<unexpected:" + chunks.getClass().getSimpleName() + ">";
        } catch (Throwable failure) {
            return "<unavailable:" + failure.getClass().getSimpleName() + ">";
        }
    }

    private static String ticketSummary(net.minecraft.server.level.ChunkMap map, long key) {
        try {
            var storage = privateField(map, "ticketStorage", net.minecraft.world.level.TicketStorage.class);
            return storage.getTickets(key).stream()
                .map(ticket -> ticket.getType() + "@" + ticket.getTicketLevel())
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        } catch (Throwable failure) {
            return "<unavailable:" + failure.getClass().getSimpleName() + ">";
        }
    }

    private static Object fieldValue(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (Throwable failure) {
            return "<unavailable:" + failure.getClass().getSimpleName() + ">";
        }
    }

    private static String futureState(java.util.concurrent.CompletableFuture<?> future) {
        if (future == null) return "null";
        if (!future.isDone()) return "pending";
        if (future.isCancelled()) return "cancelled";
        if (future.isCompletedExceptionally()) return "exceptional";
        Object result = future.getNow(null);
        if (result instanceof net.minecraft.server.level.ChunkResult<?> chunkResult) {
            if (chunkResult.isSuccess() && chunkResult.orElse(null) != null) return "success";
            return "failure[" + chunkResult.getError() + "]";
        }
        return result == null ? "done-null" : "done";
    }

    /**
     * Retain the actual completed FULL data long enough to arm the fixture, then remove the
     * temporary force tickets.  The far deferred event must remain ineligible until the player
     * later reaches its real viewport; a force ticket here would make that gate vacuous.
     */
    private static void releaseTemporaryChunks(ServerLevel level) {
        level.setChunkForced(REGION_B_FAR.getX() >> 4, REGION_B_FAR.getZ() >> 4, false);
        level.setChunkForced(REGION_DEFERRED_EVENT.getX() >> 4, REGION_DEFERRED_EVENT.getZ() >> 4, false);
        Stage4Trace.event("fixture.temporary.released", "far=(32,0) deferred=(32,12) gameTime="
            + level.getGameTime());
        System.out.println("STAGE4_TRACE_TEMPORARY_RELEASED gameTime=" + level.getGameTime());
    }

    private static void probeBlockEvent(ServerLevel level, BlockPos pos, int paramA, int paramB, String label) {
        Stage4Trace.beginBlockProbe(label, pos, paramA, paramB);
        level.blockEvent(pos, Blocks.NOTE_BLOCK, paramA, paramB);
        Stage4Trace.event("block.probe.input", "label=" + label + " pos=" + pos + " a=" + paramA + " b=" + paramB
            + " gameTime=" + level.getGameTime());
    }

    /** Clear the nested fixture's temporary above-block before the positive recipient probes. */
    private static void clearBlockProbeSupport(ServerLevel level) {
        level.setBlock(REGION_A.above(), Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(REGION_B.above(), Blocks.AIR.defaultBlockState(), 3);
        Stage4Trace.event("block.probe.support", "a=" + REGION_A.above() + " b=" + REGION_B.above()
            + " states=" + level.getBlockState(REGION_A.above()) + "," + level.getBlockState(REGION_B.above()));
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
            // Split midway between the old B center (7) and far center (32). The previous
            // x=12 seam cut the native block-entity halo at x=11, forcing immediate enclosure.
            // Keep the entire finite far column together, including deferred support at z=12.
            for (var entry : directory.snapshot().routes().entrySet()) {
                RoutingKey key = entry.getKey();
                if (!key.world().equals(world)) { split.put(key, entry.getValue().region()); continue; }
                boolean inB = key.x() > ((REGION_B.getX() >> 4) + (REGION_B_FAR.getX() >> 4)) / 2;
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
    private record PendingFull(ServerLevel level, String label, int x, int z, long deadline) { }
    private static PendingFull pendingFull;
    private static void awaitFullReadiness(ServerLevel level, String label, int centerX, int centerZ) {
        if (pendingFull != null) throw new IllegalStateException("overlapping native FULL readiness waits");
        pendingFull = new PendingFull(level, label, centerX, centerZ, System.nanoTime() + 60_000_000_000L);
    }
    public static void observeFullReadiness() {
        PendingFull pending = pendingFull;
        if (pending == null) return;
        if (checkFullReadiness(pending.level, pending.label, pending.x, pending.z)) pendingFull = null;
        else if (System.nanoTime() > pending.deadline) {
            pendingFull = null;
            throw new IllegalStateException("native demanded FULL cohort did not converge: " + pending.label);
        }
    }
    private static boolean checkFullReadiness(ServerLevel level, String label, int centerX, int centerZ) {
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
        if (!complete) return false;
        Stage4Trace.fullReadiness(label, centerX, centerZ, radius, target.size(), receipt.count());
        System.out.println("STAGE4_TRACE_FULL_READY label=" + label + " center=" + centerX + "," + centerZ
            + " radius=" + radius + " target=" + target.size() + " candidates=" + receipt.count());
        return true;
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
