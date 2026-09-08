package dev.phoenium.clienttest;

import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.platform.win32.Kernel32;
import java.nio.file.Files;
import java.nio.file.Path;

/** Read-only Windows process counters for the actual disposable child server. */
final class Stage4ProcessMetrics implements AutoCloseable {
    private final Process process;
    private final long start = System.nanoTime();
    private final Thread sampler;
    private volatile boolean closed;
    private volatile long peakWorkingSet, sampledWorkingSet;
    private volatile int samples;
    private volatile Throwable failure;

    Stage4ProcessMetrics(Process process) {
        this.process = process;
        sampler = Thread.ofPlatform().daemon().name("stage4-child-process-metrics").start(() -> {
            var handle = Kernel32.INSTANCE.OpenProcess(0x0400 | 0x0010, false, Math.toIntExact(process.pid()));
            if (handle == null) { failure = new IllegalStateException("cannot read child process counters"); return; }
            try {
                var function = NativeLibrary.getInstance("psapi").getFunction("GetProcessMemoryInfo", Function.ALT_CONVENTION);
                // PROCESS_MEMORY_COUNTERS: two DWORDs followed by eight SIZE_T values.
                int size = 8 + 8 * Native.POINTER_SIZE;
                try (Memory counters = new Memory(size)) {
                    while (!closed && process.isAlive()) {
                        counters.clear(); counters.setInt(0, size);
                        if (function.invokeInt(new Object[] {handle, counters, size}) != 0) {
                            long peak = Native.POINTER_SIZE == 8 ? counters.getLong(8) : Integer.toUnsignedLong(counters.getInt(8));
                            long rss = Native.POINTER_SIZE == 8 ? counters.getLong(16) : Integer.toUnsignedLong(counters.getInt(12));
                            peakWorkingSet = Math.max(peakWorkingSet, peak);
                            sampledWorkingSet = Math.max(sampledWorkingSet, rss);
                            samples++;
                        }
                        Thread.sleep(250);
                    }
                }
            } catch (InterruptedException stopping) { Thread.currentThread().interrupt(); }
            catch (Throwable error) { failure = error; }
            finally { Kernel32.INSTANCE.CloseHandle(handle); }
        });
    }

    void finish(Path output) throws Exception {
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        close();
        if (process.isAlive() || failure != null || samples == 0 || peakWorkingSet <= 0)
            throw new IllegalStateException("child server metrics incomplete; samples=" + samples, failure);
        Files.writeString(output, "{\"kind\":\"stage4-child-server-process-metrics\",\"pid\":" + process.pid()
            + ",\"elapsedMillis\":" + elapsedMillis + ",\"peakWorkingSetBytes\":" + peakWorkingSet
            + ",\"sampledWorkingSetBytes\":" + sampledWorkingSet + ",\"samples\":" + samples
            + ",\"sampleIntervalMillis\":250,\"exitCode\":" + process.exitValue() + "}\n");
    }
    @Override public void close() {
        closed = true;
        sampler.interrupt();
        try { sampler.join(2000); }
        catch (InterruptedException stopping) { Thread.currentThread().interrupt(); }
    }
}
