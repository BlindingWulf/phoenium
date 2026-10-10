package dev.phoenium;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.phoenium.engine.EngineConfig;
import dev.phoenium.engine.EngineTestReseed;
import dev.phoenium.engine.TileEngine;
import dev.phoenium.live.ChunkRuntimeTrace;
import dev.phoenium.live.PhoeniumLiveRuntime;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Server-only Stage 2 lifecycle root. */
public final class Phoenium implements DedicatedServerModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("Phoenium");
    /** S9-E1: null in engine mode, where the ownership machinery is bypassed and its mixins are not applied. */
    private static final PhoeniumLiveRuntime LIVE = EngineConfig.ENABLED ? null : new PhoeniumLiveRuntime(LOGGER);

    @Override
    public void onInitializeServer() {
        SupportedStack.require(LOGGER);   // S9-E16 (S8-F1): refuse an unsupported stack before any world loads
        if (EngineConfig.ENABLED) {
            TileEngine.install();
            if (Boolean.getBoolean("phoenium.test.diagnostics")) {
                CommandRegistrationCallback.EVENT.register(Phoenium::registerEngineDiagnosticsCommand);
            }
            return;
        }
        ServerLifecycleEvents.SERVER_STARTED.register(LIVE::start);
        // The stopServer wrapper acknowledges LIVE after vanilla drains retained chunk work.
        if (Boolean.getBoolean("phoenium.test.diagnostics")) {
            CommandRegistrationCallback.EVENT.register(Phoenium::registerDiagnosticsCommand);
        }
        LOGGER.info("PHOENIUM_STAGE2 entrypoint registered once; live integration awaits server start.");
    }

    /**
     * S9-E4: engine mode's test diagnostics: the engine's counters (phases, services, traps, tracker) on demand. S9-E8:
     * {@code phoenium mark <label>} logs them as {@code PHOENIUM_ENGINE mark <label> ...} and answers briefly (an RCON
     * reply is one packet), so a harness can diff two marks (the measure window) from the server log.
     */
    private static void registerEngineDiagnosticsCommand(CommandDispatcher<CommandSourceStack> dispatcher,
                                                         net.minecraft.commands.CommandBuildContext registryAccess,
                                                         Commands.CommandSelection selection) {
        dispatcher.register(Commands.literal("phoenium")
            .then(Commands.literal("diagnostics")
                .executes(context -> {
                    String response = TileEngine.diagnostics();
                    context.getSource().sendSuccess(() -> Component.literal(response), false);
                    return 1;
                }))
            .then(Commands.literal("mark")
                .then(Commands.argument("label", StringArgumentType.word())
                    .executes(context -> {
                        String label = StringArgumentType.getString(context, "label");
                        LOGGER.info("PHOENIUM_ENGINE mark {} {}", label, TileEngine.diagnostics());
                        context.getSource().sendSuccess(() -> Component.literal("PHOENIUM_MARK " + label), false);
                        return 1;
                    })))
            .then(Commands.literal("test")
                .then(Commands.literal("reseed")
                    .then(Commands.argument("seed", LongArgumentType.longArg())
                        .executes(context -> {
                            String response = EngineTestReseed.reseed(context.getSource().getServer(),
                                LongArgumentType.getLong(context, "seed"));
                            LOGGER.info("PHOENIUM_ENGINE {}", response);
                            context.getSource().sendSuccess(() -> Component.literal(response), false);
                            return 1;
                        })))));
    }

    private static void registerDiagnosticsCommand(CommandDispatcher<CommandSourceStack> dispatcher,
                                                   net.minecraft.commands.CommandBuildContext registryAccess,
                                                   Commands.CommandSelection selection) {
        dispatcher.register(Commands.literal("phoenium")
            .then(Commands.literal("diagnostics")
                .executes(context -> {
                    String line = LIVE.diagnostics().toLogLine() + " packets=" + LIVE.packetDiagnostics().toLogLine()
                            + " full=" + LIVE.fullDiagnostics();
                    if (!LIVE.failureDescription().isEmpty()) line += " failure=" + LIVE.failureDescription();
                    final String response = line;
                    context.getSource().sendSuccess(() -> Component.literal(response), false);
                    return 1;
                }))
            .then(Commands.literal("route")
                .then(Commands.argument("world", StringArgumentType.string())
                    .then(Commands.argument("x", IntegerArgumentType.integer())
                        .then(Commands.argument("z", IntegerArgumentType.integer())
                            .executes(context -> {
                                String world = StringArgumentType.getString(context, "world");
                                int x = IntegerArgumentType.getInteger(context, "x");
                                int z = IntegerArgumentType.getInteger(context, "z");
                                String response = LIVE.routeDiagnostic(world, x, z);
                                context.getSource().sendSuccess(() -> Component.literal(response), false);
                                return 1;
                            }))))));
        if (Boolean.getBoolean("phoenium.test.missingRouteCleanup")) {
            dispatcher.register(Commands.literal("phoenium")
                .then(Commands.literal("test-empty-load")
                    .then(Commands.argument("x", IntegerArgumentType.integer())
                        .then(Commands.argument("z", IntegerArgumentType.integer())
                            .executes(context -> {
                                int x = IntegerArgumentType.getInteger(context, "x");
                                int z = IntegerArgumentType.getInteger(context, "z");
                                // Pinned vanilla getChunkFuture pumps on the server thread.
                                // Request only the bound EMPTY edge; forceload waits for FULL.
                                var future = context.getSource().getLevel().getChunkSource()
                                        .getChunkFuture(x, z, ChunkStatus.EMPTY, true);
                                String response = "missingRouteEmpty chunk=" + x + "," + z
                                        + " done=" + future.isDone()
                                        + " unloaded=" + (future.getNow(null) == net.minecraft.server.level.GenerationChunkHolder.UNLOADED_CHUNK);
                                context.getSource().sendSuccess(() -> Component.literal(response), false);
                                return 1;
                            })))));
        }
        if (ChunkRuntimeTrace.enabled()) {
            dispatcher.register(Commands.literal("phoenium")
                .then(Commands.literal("chunktrace")
                    .then(Commands.literal("load")
                        .then(Commands.argument("x", IntegerArgumentType.integer())
                            .then(Commands.argument("z", IntegerArgumentType.integer())
                                .executes(context -> {
                                    ServerLevel level = context.getSource().getLevel();
                                    int x = IntegerArgumentType.getInteger(context, "x");
                                    int z = IntegerArgumentType.getInteger(context, "z");
                                    // Test-only request: do not wait, replace, wrap, or alter the
                                    // genuine vanilla future. The trace observes its normal path.
                                    level.getChunkSource().getChunkFuture(x, z, ChunkStatus.FULL, true);
                                    String response = "chunkTrace request=QUEUED world="
                                        + level.dimension().identifier() + " chunk=" + x + "," + z;
                                    context.getSource().sendSuccess(() -> Component.literal(response), false);
                                    return 1;
                                }))))
                    .then(Commands.literal("status")
                        .then(Commands.argument("x", IntegerArgumentType.integer())
                            .then(Commands.argument("z", IntegerArgumentType.integer())
                                .executes(context -> {
                                    ServerLevel level = context.getSource().getLevel();
                                    int x = IntegerArgumentType.getInteger(context, "x");
                                    int z = IntegerArgumentType.getInteger(context, "z");
                                    String response = ChunkRuntimeTrace.status(
                                        level.dimension().identifier().toString(), x, z)
                                        + " vanillaPath=observed-future-and-trace-only";
                                    context.getSource().sendSuccess(() -> Component.literal(response), false);
                                    return 1;
                                }))))
                    .then(Commands.literal("inspect")
                        .then(Commands.argument("x", IntegerArgumentType.integer())
                            .then(Commands.argument("z", IntegerArgumentType.integer())
                                .executes(context -> {
                                    ServerLevel level = context.getSource().getLevel();
                                    int x = IntegerArgumentType.getInteger(context, "x");
                                    int z = IntegerArgumentType.getInteger(context, "z");
                                    String response = ChunkRuntimeTrace.inspect(level, x, z)
                                        + " vanillaPath=read-only-inspection";
                                    context.getSource().sendSuccess(() -> Component.literal(response), false);
                                    return 1;
                                }))))));
        }
    }

    public static PhoeniumLiveRuntime liveRuntime() { return LIVE; }
}
