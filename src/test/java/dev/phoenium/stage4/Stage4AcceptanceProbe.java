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

/** Dedicated-server command fixture for real vanilla producers/consumers and ordered comparison. */
public final class Stage4AcceptanceProbe implements DedicatedServerModInitializer {
    public static final BlockPos REGION_A = new BlockPos(70, 65, 8); // chunk (4, 0)
    public static final BlockPos REGION_B = new BlockPos(85, 65, 8); // chunk (5, 0)
    @Override public void onInitializeServer() {
        CommandRegistrationCallback.EVENT.register((dispatcher, access, selection) -> dispatcher.register(
            Commands.literal("stage4trace")
                .then(Commands.literal("run").executes(context -> { arm(context.getSource().getLevel()); return 1; }))
                .then(Commands.literal("finish").executes(context -> {
                    Stage4Trace.Result result = Stage4Trace.finish(context.getSource().getLevel());
                    context.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                        "STAGE4_TRACE_RESULT " + (result.pass() ? "PASS" : "PARTIAL") +
                            " events=" + result.eventCount() + " missing=" + result.missing()), false);
                    return result.pass() ? 1 : 0;
                }))
                .then(Commands.literal("verify").executes(context -> {
                    ServerLevel level = context.getSource().getLevel();
                    context.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                        "STAGE4_TRACE_RELOAD a=" + level.getBlockState(REGION_A) +
                            " b=" + level.getBlockState(REGION_B) + " fluid=" + level.getFluidState(REGION_B.east())), false);
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
        long now = level.getGameTime();
        level.setBlock(REGION_A, Blocks.NOTE_BLOCK.defaultBlockState(), 3);
        level.setBlock(REGION_B, Blocks.NOTE_BLOCK.defaultBlockState(), 3);
        level.getBlockTicks().schedule(new ScheduledTick<>(Blocks.NOTE_BLOCK, REGION_A, now + 2, TickPriority.HIGH, 1));
        level.getBlockTicks().schedule(new ScheduledTick<>(Blocks.NOTE_BLOCK, REGION_B, now + 3, TickPriority.NORMAL, 2));
        level.getBlockTicks().schedule(new ScheduledTick<>(Blocks.NOTE_BLOCK, REGION_A, now + 2, TickPriority.HIGH, 1));
        // This future tick must still be pending at the normal save cut and restart.
        level.getBlockTicks().schedule(new ScheduledTick<>(Blocks.NOTE_BLOCK, REGION_B, now + 400, TickPriority.LOW, 99));
        BlockPos fluid = REGION_B.east();
        level.setBlock(fluid, Fluids.WATER.defaultFluidState().createLegacyBlock(), 3);
        level.getFluidTicks().schedule(new ScheduledTick<>(Fluids.WATER, fluid, now + 3, TickPriority.LOW, 3));
        level.blockEvent(REGION_A, Blocks.NOTE_BLOCK, 1, 7);
        Stage4Trace.event("trace.armed", "a=" + REGION_A + " b=" + REGION_B + " now=" + now);
        System.out.println("STAGE4_TRACE_ARMED a=" + REGION_A + " b=" + REGION_B + " now=" + now +
            " routeA=" + Phoenium.liveRuntime().routeDiagnostic(level.dimension().identifier().toString(), 4, 0) +
            " routeB=" + Phoenium.liveRuntime().routeDiagnostic(level.dimension().identifier().toString(), 5, 0));
    }

}
