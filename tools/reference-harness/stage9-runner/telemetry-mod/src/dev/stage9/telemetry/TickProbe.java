package dev.stage9.telemetry;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-tick server telemetry for Stage 9. The server thread only stores two longs per tick into a
 * single-producer ring; a daemon thread drains it once per second to {@code ticks.csv} and
 * {@code samples.jsonl} and logs a 10-second summary line ("S9T ...").
 */
public final class TickProbe {
    public static final String VERSION = "1.1.0";
    private static final Logger LOG = LoggerFactory.getLogger("Stage9Telemetry");
    private static final boolean DISABLED = Boolean.getBoolean("stage9.telemetry.disable");
    private static final int CAP = 1 << 16;
    private static final long[] STARTS = new long[CAP];
    private static final long[] DURATIONS = new long[CAP];
    /** 1.1.0: each tick's frame (processPacketsAndTick, which encloses it); 0 when no frame enclosed the tick. */
    private static final long[] FRAMES = new long[CAP];
    private static long frameStart;
    private static volatile long completed;
    private static volatile int players;
    private static volatile long loadedChunks;
    private static long pendingStart;
    private static long tickNo;
    private static Thread writer;

    private TickProbe() {}

    public static void start() {
        if (DISABLED) return;
        if (writer == null) startWriter();
        pendingStart = System.nanoTime();
    }

    public static void end(MinecraftServer server) {
        if (DISABLED || pendingStart == 0L) return;
        long end = System.nanoTime();
        int slot = (int) (tickNo & (CAP - 1));
        STARTS[slot] = pendingStart;
        DURATIONS[slot] = end - pendingStart;
        FRAMES[slot] = 0L;
        pendingStart = 0L;
        tickNo++;
        if (tickNo % 20 == 0) {
            players = server.getPlayerCount();
            long chunks = 0;
            for (ServerLevel level : server.getAllLevels()) chunks += level.getChunkSource().getLoadedChunksCount();
            loadedChunks = chunks;
        }
        // inside a frame the tick is published with its frame (frameEnd), so the writer never reads a partial frame
        if (frameStart == 0L) completed = tickNo;
    }

    public static void frameStart() {
        if (DISABLED) return;
        frameStart = System.nanoTime();
    }

    public static void frameEnd() {
        if (DISABLED || frameStart == 0L) return;
        long end = System.nanoTime();
        if (tickNo > 0 && completed < tickNo) FRAMES[(int) ((tickNo - 1) & (CAP - 1))] = end - frameStart;
        frameStart = 0L;
        completed = tickNo;
    }

    private static void startWriter() {
        Path dir = Path.of(System.getProperty("stage9.telemetry.dir", "stage9-telemetry"));
        Thread thread = new Thread(() -> drainLoop(dir), "stage9-telemetry-writer");
        thread.setDaemon(true);
        writer = thread;
        thread.start();
    }

    private static void drainLoop(Path dir) {
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("meta.json"), String.format(Locale.ROOT,
                "{\"version\":\"%s\",\"startWallMs\":%d,\"startNano\":%d,\"pid\":%d}%n", VERSION,
                System.currentTimeMillis(), System.nanoTime(), ProcessHandle.current().pid()), StandardCharsets.UTF_8);
            try (BufferedWriter ticks = Files.newBufferedWriter(dir.resolve("ticks.csv"), StandardCharsets.UTF_8);
                 BufferedWriter samples = Files.newBufferedWriter(dir.resolve("samples.jsonl"), StandardCharsets.UTF_8)) {
                ticks.write("tick,startNano,durNano,frameNano\n");
                long drained = 0;
                long nextSummary = System.nanoTime() + 10_000_000_000L;
                while (true) {
                    Thread.sleep(1000);
                    long upTo = completed;
                    if (upTo - drained > CAP) drained = upTo - CAP; // overrun: keep the newest ring contents
                    for (long t = drained; t < upTo; t++) {
                        int slot = (int) (t & (CAP - 1));
                        ticks.write(Long.toString(t));
                        ticks.write(',');
                        ticks.write(Long.toString(STARTS[slot]));
                        ticks.write(',');
                        ticks.write(Long.toString(DURATIONS[slot]));
                        ticks.write(',');
                        ticks.write(Long.toString(FRAMES[slot]));
                        ticks.write('\n');
                    }
                    drained = upTo;
                    ticks.flush();
                    MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
                    samples.write(String.format(Locale.ROOT,
                        "{\"wallMs\":%d,\"nano\":%d,\"ticks\":%d,\"players\":%d,\"loadedChunks\":%d,"
                            + "\"heapUsed\":%d,\"heapCommitted\":%d,\"heapMax\":%d}%n",
                        System.currentTimeMillis(), System.nanoTime(), upTo, players, loadedChunks,
                        heap.getUsed(), heap.getCommitted(), heap.getMax()));
                    samples.flush();
                    long now = System.nanoTime();
                    if (now >= nextSummary) {
                        logSummary(upTo, now);
                        nextSummary = now + 10_000_000_000L;
                    }
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException failure) {
            LOG.warn("S9T telemetry writer stopped: {}", failure.toString());
        }
    }

    /** TPS and MSPT percentiles over ticks that started in the last 10 s (at most the ring). */
    private static void logSummary(long upTo, long now) {
        long windowStart = now - 10_000_000_000L;
        long[] window = new long[(int) Math.min(upTo, CAP)];
        int n = 0;
        for (long t = upTo - 1; t >= 0 && t >= upTo - CAP; t--) {
            int slot = (int) (t & (CAP - 1));
            if (STARTS[slot] < windowStart) break;
            window[n++] = DURATIONS[slot];
        }
        if (n == 0) {
            LOG.info("S9T window=10s tps=0.00 ticks=0 players={} chunks={}", players, loadedChunks);
            return;
        }
        long[] sorted = Arrays.copyOf(window, n);
        Arrays.sort(sorted);
        LOG.info(String.format(Locale.ROOT,
            "S9T window=10s tps=%.2f mspt_p50=%.2f mspt_p95=%.2f mspt_p99=%.2f mspt_max=%.2f players=%d chunks=%d",
            n / 10.0, ms(sorted, 0.50), ms(sorted, 0.95), ms(sorted, 0.99), sorted[n - 1] / 1e6, players, loadedChunks));
    }

    private static double ms(long[] sorted, double q) {
        int index = (int) Math.ceil(q * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))] / 1e6;
    }
}
