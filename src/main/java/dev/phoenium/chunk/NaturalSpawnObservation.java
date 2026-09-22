package dev.phoenium.chunk;

import com.mojang.logging.LogUtils;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Bounded per-world observations; never used as readiness or query authority. */
public final class NaturalSpawnObservation {
    private final Set<String> summaries = new HashSet<>();
    private final AtomicBoolean failed = new AtomicBoolean();

    public void summary(String marker, Supplier<String> detail) {
        if (!Boolean.getBoolean("phoenium.test.diagnostics")) return;
        try {
            if (summaries.size() < 32 && summaries.add(marker))
                LogUtils.getLogger().info("PHOENIUM_NATURAL_SPAWN {} {}", marker, detail.get());
        } catch (Throwable ignored) { /* Telemetry cannot change the native result. */ }
    }
    public void failure(Throwable original, Supplier<String> detail) {
        if (!failed.compareAndSet(false, true)) return;
        try { LogUtils.getLogger().error("PHOENIUM_NATURAL_SPAWN FIRST_FAILURE {}", detail.get(), original); }
        catch (Throwable diagnostic) { if (diagnostic != original) original.addSuppressed(diagnostic); }
    }
}
