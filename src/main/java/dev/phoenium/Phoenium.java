package dev.phoenium;

import com.mojang.brigadier.CommandDispatcher;
import dev.phoenium.live.PhoeniumLiveRuntime;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
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
                })));
    }

    public static PhoeniumLiveRuntime liveRuntime() { return LIVE; }
}
