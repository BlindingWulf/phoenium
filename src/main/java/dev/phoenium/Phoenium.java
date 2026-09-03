package dev.phoenium;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
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
    private static final PhoeniumLiveRuntime LIVE = new PhoeniumLiveRuntime(LOGGER);

    @Override
    public void onInitializeServer() {
        ServerLifecycleEvents.SERVER_STARTED.register(LIVE::start);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> LIVE.stop());
        if (Boolean.getBoolean("phoenium.test.diagnostics")) {
            CommandRegistrationCallback.EVENT.register(Phoenium::registerDiagnosticsCommand);
        }
        LOGGER.info("PHOENIUM_STAGE2 entrypoint registered once; live integration awaits server start.");
    }

    private static void registerDiagnosticsCommand(CommandDispatcher<CommandSourceStack> dispatcher,
                                                   net.minecraft.commands.CommandBuildContext registryAccess,
                                                   Commands.CommandSelection selection) {
        dispatcher.register(Commands.literal("phoenium")
            .then(Commands.literal("diagnostics")
                .executes(context -> {
                    String line = LIVE.diagnostics().toLogLine() + " packets=" + LIVE.packetDiagnostics().toLogLine();
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
                                }))))));
        }
    }

    public static PhoeniumLiveRuntime liveRuntime() { return LIVE; }
}
