package dev.phoenix.spawnprep;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * The preparation loop, run on the server thread during startup. Each slot first asks storage whether its chunk is already
 * finished (then it is skipped untouched), otherwise it holds a loading ticket until the chunk is generated, then releases
 * it. Futures are only polled here, never acted on from the threads that complete them.
 */
public final class SpawnPrepRunner {
    public interface Backend {
        /** True when the stored chunk is already fully generated. */
        CompletableFuture<Boolean> isComplete(SpawnPrepPlan.Pos pos);

        /** Adds this mod's loading ticket and completes when the chunk is fully generated. */
        CompletableFuture<?> generate(SpawnPrepPlan.Pos pos);

        void release(SpawnPrepPlan.Pos pos);

        /** Lets the server run its pending work for a moment (and unload released chunks now and then). */
        void pump();

        long usedHeap();

        long maxHeap();
    }

    public interface Control {
        boolean cancelRequested();

        boolean stopping();

        long nanoTime();
    }

    public record Result(SpawnPrepMarker.State state, int done, int total, int generated, int existing, int failed,
                         int heapPauses, double seconds) {
    }

    static final double HEAP_PAUSE_FRACTION = 0.85;
    static final long PROGRESS_NANOS = 2_000_000_000L, CHECKPOINT_NANOS = 5_000_000_000L;

    private static final class Slot {
        final SpawnPrepPlan.Pos pos;
        CompletableFuture<?> future;
        boolean generating;

        Slot(SpawnPrepPlan.Pos pos, CompletableFuture<?> future) {
            this.pos = pos;
            this.future = future;
        }
    }

    private final List<SpawnPrepPlan.Pos> plan;
    private final int maxInFlight;
    private final Backend backend;
    private final Control control;
    private final Consumer<String> log;
    private final Consumer<Result> checkpoint;
    private final List<Slot> inFlight = new ArrayList<>();
    private int done, generated, existing, failed, heapPauses;
    private long start;

    public SpawnPrepRunner(List<SpawnPrepPlan.Pos> plan, int maxInFlight, Backend backend, Control control, Consumer<String> log,
                           Consumer<Result> checkpoint) {
        this.plan = plan;
        this.maxInFlight = Math.max(1, maxInFlight);
        this.backend = backend;
        this.control = control;
        this.log = log;
        this.checkpoint = checkpoint;
    }

    public Result run() {
        start = control.nanoTime();
        long lastProgress = Long.MIN_VALUE, lastCheckpoint = start;
        int cursor = 0;
        SpawnPrepMarker.State end = null;
        try {
            while (true) {
                harvest();
                if (control.stopping()) {
                    end = SpawnPrepMarker.State.PARTIAL;
                    break;
                }
                if (control.cancelRequested()) {
                    end = SpawnPrepMarker.State.CANCELLED;
                    break;
                }
                if (cursor >= plan.size() && inFlight.isEmpty()) break;
                boolean heapHigh = heapHigh();
                if (heapHigh && cursor < plan.size() && inFlight.size() < maxInFlight) heapPauses++;
                while (!heapHigh && inFlight.size() < maxInFlight && cursor < plan.size()) {
                    SpawnPrepPlan.Pos pos = plan.get(cursor++);
                    inFlight.add(new Slot(pos, backend.isComplete(pos)));
                }
                long now = control.nanoTime();
                if (lastProgress == Long.MIN_VALUE || now - lastProgress >= PROGRESS_NANOS) {
                    log.accept(progressLine(now));
                    lastProgress = now;
                }
                if (now - lastCheckpoint >= CHECKPOINT_NANOS) {
                    checkpoint.accept(result(SpawnPrepMarker.State.PARTIAL, now));
                    lastCheckpoint = now;
                }
                backend.pump();
            }
        } finally {
            for (Slot s : inFlight) {
                if (s.generating) backend.release(s.pos);
            }
            inFlight.clear();
        }
        SpawnPrepMarker.State state = end != null ? end : failed > 0 ? SpawnPrepMarker.State.PARTIAL : SpawnPrepMarker.State.COMPLETE;
        Result r = result(state, control.nanoTime());
        log.accept(String.format(Locale.ROOT, "[PhoenixSpawnPrep] DONE state=%s done=%d total=%d generated=%d existing=%d failed=%d seconds=%.1f",
                                 state, r.done(), r.total(), r.generated(), r.existing(), r.failed(), r.seconds()));
        return r;
    }

    private void harvest() {
        for (Iterator<Slot> it = inFlight.iterator(); it.hasNext(); ) {
            Slot s = it.next();
            if (!s.future.isDone()) continue;
            boolean ok = !s.future.isCompletedExceptionally() && !s.future.isCancelled();
            if (!s.generating) {
                if (ok && Boolean.TRUE.equals(s.future.getNow(null))) {
                    existing++;
                    done++;
                    it.remove();
                } else {                                     // missing, unfinished or unreadable: let the chunk system finish it
                    s.future = backend.generate(s.pos);
                    s.generating = true;
                }
            } else {
                backend.release(s.pos);
                if (ok) {
                    generated++;
                    done++;
                } else {
                    failed++;                                // left for the next launch; the run ends PARTIAL
                }
                it.remove();
            }
        }
    }

    private boolean heapHigh() {
        long max = backend.maxHeap();
        return max > 0 && backend.usedHeap() > HEAP_PAUSE_FRACTION * max;
    }

    private Result result(SpawnPrepMarker.State state, long now) {
        return new Result(state, done, plan.size(), generated, existing, failed, heapPauses, (now - start) / 1e9);
    }

    private String progressLine(long now) {
        double seconds = (now - start) / 1e9;
        double cps = seconds > 0 ? done / seconds : 0;
        long eta = cps > 0 ? Math.round((plan.size() - done) / cps) : 0;
        double pct = plan.isEmpty() ? 100 : 100.0 * done / plan.size();
        long mib = 1024 * 1024;
        return String.format(Locale.ROOT,
            "[PhoenixSpawnPrep] PROGRESS done=%d total=%d pct=%.1f generated=%d existing=%d failed=%d cps=%.1f etaS=%d inFlight=%d heapMB=%d/%d",
            done, plan.size(), pct, generated, existing, failed, cps, eta, inFlight.size(), backend.usedHeap() / mib,
            backend.maxHeap() / mib);
    }
}
