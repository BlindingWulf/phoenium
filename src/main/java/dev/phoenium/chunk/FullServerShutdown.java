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
    private static final ThreadLocal<FullInputNotifications.Failures> FAILED_STOP = new ThreadLocal<>();
    private FullServerShutdown() { }

    /** Only the failed native stop invocation may omit new world captures and unload
     * mutations. Its outer settlement already retains an exception and cannot return
     * success. This is not permission to resume a failed owner or run queued work. */
    public static boolean failedStop() { return FAILED_STOP.get() != null; }

    /** Independent native resource closes keep running after a prior close fails.
     * Outside the failed stop invocation the original exception behavior is unchanged. */
    public static void cleanup(Runnable original) {
        FullInputNotifications.Failures failures = FAILED_STOP.get();
        if (failures == null) { original.run(); return; }
        if (OwnershipContext.current().isPresent())
            throw new ChunkProtocolException("failed server cleanup retained regional authority");
        if (Thread.holdsLock(METADATA))
            throw new ChunkProtocolException("failed server cleanup entered under shutdown metadata");
        failures.run(original::run);
    }

    public static void run(MinecraftServer server, Runnable original, Runnable acknowledgeRuntime) {
        run(server, () -> { }, original, acknowledgeRuntime);
    }

    /** A nested stop is a request to the already active settlement, not a second executor.
     * Admission closes before vanilla drains its retained generation and save work. The
     * metadata section contains no callback, service entry, or future completion. */
    public static void run(MinecraftServer server, Runnable closeAdmission, Runnable original,
                           Runnable acknowledgeRuntime) {
        run(server, closeAdmission, () -> { }, original, acknowledgeRuntime);
    }

    public static void run(MinecraftServer server, Runnable closeAdmission, Runnable drainAdmitted,
                           Runnable original, Runnable acknowledgeRuntime) {
        if (OwnershipContext.current().isPresent())
            throw new ChunkProtocolException("server stop requested from an active regional mutation");
        synchronized (METADATA) {
            if (SETTLED.contains(server) || !SETTLING.add(server)) return;
        }
        var failures = new FullInputNotifications.Failures();
        boolean[] admissionClosed = {false}, drained = {false}, acknowledged = {false};
        try {
            failures.run(() -> { closeAdmission.run(); admissionClosed[0] = true; });
            failures.run(() -> {
                try (var turn = FullFinishingServices.forServer(server).serial(FullFinishingServices.Phase.STOP)) {
                    failures.run(() -> { drainAdmitted.run(); drained[0] = true; });
                    if (admissionClosed[0] && drained[0]) {
                        failures.run(original::run);
                    } else {
                        // The barrier cannot be bypassed to empty native unload queues.
                        // Wait for real retained I/O and release protocol custody before
                        // original resource closure. Named STOP services remain available.
                        var parent = FAILED_STOP.get();
                        FAILED_STOP.set(failures);
                        try {
                            acknowledged[0] = true;
                            failures.run(acknowledgeRuntime::run);
                            failures.run(original::run);
                        } finally {
                            if (parent == null) FAILED_STOP.remove(); else FAILED_STOP.set(parent);
                        }
                    }
                }
            });
            if (!acknowledged[0]) failures.run(acknowledgeRuntime::run);
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
