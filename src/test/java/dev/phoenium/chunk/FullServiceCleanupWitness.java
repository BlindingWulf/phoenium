package dev.phoenium.chunk;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;

/** Test-harness-only observation of the real finishing service after the server stop path. */
final class FullServiceCleanupWitness {
    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final AtomicReference<FullFinishingServices> SERVICE = new AtomicReference<>();
    private static final AtomicBoolean STOP_EVENT = new AtomicBoolean();

    private FullServiceCleanupWitness() { }

    static void install() {
        if (!INSTALLED.compareAndSet(false, true)) return;
        ServerLifecycleEvents.SERVER_STARTED.register(server -> SERVICE.set(FullFinishingServices.forServer(server)));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> STOP_EVENT.set(true));
        Runtime.getRuntime().addShutdownHook(new Thread(FullServiceCleanupWitness::report, "phoenium-full-service-cleanup-witness"));
    }

    private static void report() {
        FullFinishingServices service = SERVICE.get();
        if (service == null) {
            System.err.println("PHOENIUM_FULL_SERVICES STOPPED FAIL stopEvent=" + STOP_EVENT.get()
                    + " reason=service-not-retained-at-server-start");
            return;
        }
        // FullServerShutdown closes the service synchronously before the process exits. Allow
        // only the legacy producer's ordinary executor teardown to settle before observing it.
        long deadline = System.nanoTime() + 5_000_000_000L;
        Map<String, Long> serviceMetrics;
        Map<String, Long> entityMetrics;
        Map<String, Long> scoreboardMetrics;
        Map<String, Long> legacyMetrics;
        do {
            serviceMetrics = service.measurements();
            entityMetrics = service.entityFacts.measurements();
            scoreboardMetrics = service.scoreboard.measurements();
            legacyMetrics = service.legacyOwners.measurements();
            if (clean(serviceMetrics, entityMetrics, scoreboardMetrics, legacyMetrics)) break;
            try { Thread.sleep(10L); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
        } while (System.nanoTime() < deadline);
        boolean clean = clean(serviceMetrics, entityMetrics, scoreboardMetrics, legacyMetrics);
        String line = "PHOENIUM_FULL_SERVICES STOPPED " + (clean && STOP_EVENT.get() ? "PASS" : "FAIL")
                + " stopEvent=" + STOP_EVENT.get()
                + " serviceReservations=" + value(serviceMetrics, "reservations")
                + " serviceActive=" + value(serviceMetrics, "active")
                + " serviceWorlds=" + value(serviceMetrics, "worlds")
                + " serviceEnvironmentRequests=" + value(serviceMetrics, "environmentRequests")
                + " entityFactsPublished=" + value(entityMetrics, "factsPublished")
                + " entityFactsInsertions=" + value(entityMetrics, "insertions")
                + " entityFactsRemovals=" + value(entityMetrics, "removals")
                + " entityFactsAddressable=" + value(entityMetrics, "addressable")
                + " scoreboardSessions=" + value(scoreboardMetrics, "sessions")
                + " scoreboardCatchup=" + value(scoreboardMetrics, "catchup")
                + " scoreboardAccepted=" + value(scoreboardMetrics, "accepted")
                + " scoreboardUncertain=" + value(scoreboardMetrics, "uncertain")
                + " scoreboardCommitted=" + value(scoreboardMetrics, "committed")
                + " scoreboardCompleted=" + value(scoreboardMetrics, "completed")
                + " legacyPlans=" + value(legacyMetrics, "plans")
                + " legacyCompleted=" + value(legacyMetrics, "completed")
                + " legacyAcknowledged=" + value(legacyMetrics, "acknowledged")
                + " legacyQueued=" + value(legacyMetrics, "queued")
                + " legacyRunning=" + value(legacyMetrics, "running")
                + " legacyProducerTerminated=" + value(legacyMetrics, "producerTerminated")
                + " legacyCacheRoots=" + value(legacyMetrics, "cacheRoots");
        if (clean && STOP_EVENT.get()) System.out.println(line);
        else System.err.println(line);
    }

    private static boolean clean(Map<String, Long> service, Map<String, Long> entity,
                                 Map<String, Long> scoreboard, Map<String, Long> legacy) {
        return value(service, "reservations") == 0 && value(service, "active") == 0
                && value(service, "worlds") == 0 && value(service, "environmentRequests") == 0
                && value(entity, "addressable") == 0
                && value(scoreboard, "sessions") == 0 && value(scoreboard, "catchup") == 0
                && value(legacy, "plans") == 0 && value(legacy, "queued") == 0
                && value(legacy, "running") == 0 && value(legacy, "cacheRoots") == 0
                && value(legacy, "producerTerminated") == 1;
    }

    private static long value(Map<String, Long> values, String key) {
        return values.getOrDefault(key, Long.MIN_VALUE);
    }
}
