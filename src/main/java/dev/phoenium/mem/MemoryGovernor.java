package dev.phoenium.mem;

import com.sun.management.GarbageCollectionNotificationInfo;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.openmbean.CompositeData;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

/**
 * S9-M2 (MEM-2): heap-pressure governor. Off unless {@code -Dphoenium.mem.governor=true}.
 *
 * <p>When the Java heap cannot hold the loaded world (mixed-v2 t50 at 4 GB: live ~3.9 GB), G1 falls into hundreds of
 * full GCs until the watchdog kills the server. The governor turns that into a measured, reversible throttle: it watches
 * the heap left after each collection (JVM GC notifications, heap pools only) and, while the lowest post-GC occupancy of
 * the last 30 s stays above {@code high} (0.80), steps the server view distance down by one every 15 s (by two every 5 s
 * above {@code critical} 0.90, or after two full GCs in the window), never below {@code minView} (4). Chunks beyond the
 * new distance unload exactly as when an operator lowers view-distance at runtime (PlayerList.setViewDistance; C2ME's
 * no-tick loader follows it). After 60 s below {@code low} (0.60) it restores one step every 30 s up to the configured
 * distance. Simulation distance, tickets and gameplay rules are untouched. Every change is logged (PHOENIUM_MEM governor).
 */
public final class MemoryGovernor {
    private static final double HIGH = Double.parseDouble(System.getProperty("phoenium.mem.governor.high", "0.80"));
    private static final double CRITICAL = Double.parseDouble(System.getProperty("phoenium.mem.governor.critical", "0.90"));
    private static final double LOW = Double.parseDouble(System.getProperty("phoenium.mem.governor.low", "0.60"));
    private static final int MIN_VIEW = Math.max(2, Integer.getInteger("phoenium.mem.governor.minView", 4));
    private static final long WINDOW_MS = 30_000L;

    private static final ArrayDeque<long[]> SAMPLES = new ArrayDeque<>();   // {time ms, heap used after GC, full ? 1 : 0}
    private static Set<String> heapPools = Set.of();
    private static Logger logger;
    private static int configured = -1;
    private static long lastChange;
    private static long calmSince;
    private static long ticks;
    private static int steps;
    private static int lowestView = Integer.MAX_VALUE;
    private static long lastUsed = -1;   // heap left after the most recent collection (judged when the window is empty)

    private MemoryGovernor() {
    }

    public static void install(Logger log) {
        if (!MemSwitches.GOVERNOR) return;
        logger = log;
        try {
            heapPools = ManagementFactory.getMemoryPoolMXBeans().stream().filter(p -> p.getType() == MemoryType.HEAP)
                .map(MemoryPoolMXBean::getName).collect(Collectors.toUnmodifiableSet());
            for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
                if (bean instanceof NotificationEmitter emitter) emitter.addNotificationListener(MemoryGovernor::onGc, null, null);
            }
        } catch (Throwable unavailable) {   // e.g. a runtime without jdk.management: stay off, never block startup
            log.warn("PHOENIUM_MEM governor unavailable ({}); staying off", unavailable.toString());
            return;
        }
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            configured = server.getPlayerList().getViewDistance();
            log.info("PHOENIUM_MEM governor on view={} minView={} high={} critical={} low={} heapPools={}",
                configured, MIN_VIEW, HIGH, CRITICAL, LOW, heapPools);
        });
        ServerTickEvents.END_SERVER_TICK.register(MemoryGovernor::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> log.info(
            "PHOENIUM_MEM governor stopped steps={} lowestView={} view={} configured={}", steps,
            lowestView == Integer.MAX_VALUE ? configured : lowestView, server.getPlayerList().getViewDistance(), configured));
    }

    /** JMX notification thread: record the heap left after a collection (cycle beans only; pause beans repeat them). */
    private static void onGc(Notification notification, Object handback) {
        if (!GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION.equals(notification.getType())) return;
        GarbageCollectionNotificationInfo info = GarbageCollectionNotificationInfo.from((CompositeData) notification.getUserData());
        String name = info.getGcName();
        if (name.contains("Pauses")) return;
        long used = 0;
        for (Map.Entry<String, MemoryUsage> pool : info.getGcInfo().getMemoryUsageAfterGc().entrySet()) {
            if (heapPools.contains(pool.getKey())) used += pool.getValue().getUsed();
        }
        boolean full = name.contains("Old Generation") || name.contains("MarkSweep");
        synchronized (SAMPLES) {
            SAMPLES.addLast(new long[] {System.currentTimeMillis(), used, full ? 1 : 0});
            lastUsed = used;
        }
    }

    /** Server thread, end of every tick; evaluates once per second. */
    private static void tick(MinecraftServer server) {
        if (configured < 0 || ++ticks % 20 != 0) return;
        long now = System.currentTimeMillis();
        long floor = Long.MAX_VALUE;
        int fulls = 0;
        synchronized (SAMPLES) {
            while (!SAMPLES.isEmpty() && now - SAMPLES.peekFirst()[0] > WINDOW_MS) SAMPLES.pollFirst();
            for (long[] s : SAMPLES) {
                floor = Math.min(floor, s[1]);
                fulls += (int) s[2];
            }
            // no collection in the window (a quiet heap): judge the most recent post-GC heap, so recovery still happens
            if (floor == Long.MAX_VALUE) floor = lastUsed;
        }
        if (floor < 0) return;   // no collection yet since start
        double occupancy = (double) floor / Runtime.getRuntime().maxMemory();
        int view = server.getPlayerList().getViewDistance();
        boolean critical = occupancy >= CRITICAL || (fulls >= 2 && occupancy >= HIGH);
        if ((critical || occupancy >= HIGH) && view > MIN_VIEW) {
            calmSince = 0;
            if (now - lastChange >= (critical ? 5_000L : 15_000L)) {
                apply(server, Math.max(MIN_VIEW, view - (critical ? 2 : 1)), occupancy, fulls, critical ? "critical" : "high");
            }
        } else if (occupancy < LOW && view < configured) {
            if (calmSince == 0) calmSince = now;
            if (now - calmSince >= 60_000L && now - lastChange >= 30_000L) apply(server, view + 1, occupancy, fulls, "recover");
        } else {
            calmSince = 0;
        }
    }

    private static void apply(MinecraftServer server, int view, double occupancy, int fulls, String reason) {
        int from = server.getPlayerList().getViewDistance();
        server.getPlayerList().setViewDistance(view);
        lastChange = System.currentTimeMillis();
        steps++;
        lowestView = Math.min(lowestView, view);
        logger.info("PHOENIUM_MEM governor {} view {}->{} (configured {}) heapAfterGcFloor={} fullGcs30s={}", reason, from, view,
            configured, String.format("%.3f", occupancy), fulls);
    }
}
