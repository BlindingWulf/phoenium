package dev.phoenium.c2meprobe;

import com.google.gson.GsonBuilder;
import dev.phoenium.ownership.OwnershipContext;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

/** Read-only bounded receipts. Never supplies demand, results, authority or save work. */
public final class C2mePathProbe implements DedicatedServerModInitializer {
    private static final Map<String, Receipt> COUNTS = new ConcurrentHashMap<>();
    private static final class Receipt {
        final LongAdder calls = new LongAdder(), owned = new LongAdder();
        final String firstThread = Thread.currentThread().getName();
        final String firstOwner = OwnershipContext.current().map(c -> c.region().toString()).orElse("NONE");
        final List<String> firstStack = StackWalker.getInstance().walk(s -> s.limit(28).map(Object::toString).toList());
    }
    public static void hit(String key) {
        if (key.contains(".promoteChunkMap")) {
            boolean callback = StackWalker.getInstance().walk(s -> s.limit(12)
                .anyMatch(f -> f.getMethodName().endsWith("$updateHolderMapAfterTick")));
            if (callback) key += ".fromC2meTickCallback";
        }
        var receipt = COUNTS.computeIfAbsent(key, ignored -> new Receipt());
        receipt.calls.increment();
        if (OwnershipContext.current().isPresent()) receipt.owned.increment();
    }
    public static void result(boolean value) { hit("distance.result." + value); }
    private static void snapshot(Path output) {
        try {
            Map<String, Object> values = new TreeMap<>();
            COUNTS.forEach((key, r) -> values.put(key, Map.of("calls", r.calls.sum(), "owned", r.owned.sum(),
                "firstThread", r.firstThread, "firstOwner", r.firstOwner, "firstStack", r.firstStack)));
            Path pending = output.resolveSibling(output.getFileName() + ".pending");
            Files.writeString(pending, new GsonBuilder().setPrettyPrinting().create().toJson(values));
            Files.move(pending, output, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception failure) { System.err.println("C2ME_PROBE_WRITE_FAILED " + failure); }
    }
    @Override public void onInitializeServer() {
        Path output = Path.of(System.getProperty("phoenium.c2me.observations"));
        var executor = Executors.newSingleThreadScheduledExecutor(r -> {
            var thread = new Thread(r, "phoenium-c2me-observer"); thread.setDaemon(true); return thread;
        });
        executor.scheduleAtFixedRate(() -> snapshot(output), 0, 1, TimeUnit.SECONDS);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> { executor.shutdown(); snapshot(output); });
    }
}
