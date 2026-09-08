package dev.phoenium.chunk;

import net.minecraft.server.MinecraftServer;
import dev.phoenium.ownership.OwnershipContext;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.WeakHashMap;

/** The original server stop drains generation before closing its levels. Keep the same runtime
 * available through that drain, then acknowledge it before terminating its named services. */
public final class FullServerShutdown {
    private static final Object METADATA = new Object();
    private static final Set<MinecraftServer> SETTLING = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Set<MinecraftServer> SETTLED = Collections.newSetFromMap(new WeakHashMap<>());
    private FullServerShutdown() { }

    public static void run(MinecraftServer server, Runnable original, Runnable acknowledgeRuntime) {
        run(server, () -> { }, original, acknowledgeRuntime);
    }

    /** A nested stop is a request to the already active settlement, not a second executor.
     * Admission closes before vanilla drains its retained generation and save work. The
     * metadata section contains no callback, service entry, or future completion. */
    public static void run(MinecraftServer server, Runnable closeAdmission, Runnable original,
                           Runnable acknowledgeRuntime) {
        if (OwnershipContext.current().isPresent())
            throw new ChunkProtocolException("server stop requested from an active regional mutation");
        synchronized (METADATA) {
            if (SETTLED.contains(server) || !SETTLING.add(server)) return;
        }
        var failures = new FullInputNotifications.Failures();
        try {
            failures.run(closeAdmission::run);
            failures.run(() -> {
                try (var turn = FullFinishingServices.forServer(server).serial(FullFinishingServices.Phase.STOP)) {
                    original.run();
                }
            });
            failures.run(acknowledgeRuntime::run);
            failures.run(() -> FullFinishingServices.stopped(server));
        } finally {
            synchronized (METADATA) {
                SETTLING.remove(server);
                SETTLED.add(server);
            }
        }
        failures.rethrow();
    }
}
