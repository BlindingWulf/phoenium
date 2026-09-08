package dev.phoenium.chunk;

import net.minecraft.server.MinecraftServer;

/** The original server stop drains generation before closing its levels. Keep the same runtime
 * available through that drain, then acknowledge it before terminating its named services. */
public final class FullServerShutdown {
    private FullServerShutdown() { }

    public static void run(MinecraftServer server, Runnable original, Runnable acknowledgeRuntime) {
        var failures = new FullInputNotifications.Failures();
        failures.run(() -> {
            try (var turn = FullFinishingServices.forServer(server).serial(FullFinishingServices.Phase.STOP)) {
                original.run();
            }
        });
        failures.run(acknowledgeRuntime::run);
        failures.run(() -> FullFinishingServices.stopped(server));
        failures.rethrow();
    }
}
