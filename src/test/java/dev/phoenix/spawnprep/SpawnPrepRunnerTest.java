package dev.phoenix.spawnprep;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/** The generation loop against a fake chunk system: bounded, leak-free, never touches finished chunks. */
class SpawnPrepRunnerTest {
    /** Futures complete one pump after they are issued, like the server finishing work between waits. */
    static class FakeBackend implements SpawnPrepRunner.Backend {
        final Set<SpawnPrepPlan.Pos> complete = new HashSet<>(), failing = new HashSet<>();
        final List<SpawnPrepPlan.Pos> generated = new ArrayList<>();
        final Map<SpawnPrepPlan.Pos, Integer> tickets = new HashMap<>();
        final List<Runnable> pending = new ArrayList<>();
        int outstanding, maxOutstanding, pumps;
        long used = 100, max = 1000;

        @Override
        public CompletableFuture<Boolean> isComplete(SpawnPrepPlan.Pos p) {
            return later(complete.contains(p), false);
        }

        @Override
        public CompletableFuture<?> generate(SpawnPrepPlan.Pos p) {
            generated.add(p);
            tickets.merge(p, 1, Integer::sum);
            return later(Boolean.TRUE, failing.contains(p));
        }

        @Override
        public void release(SpawnPrepPlan.Pos p) {
            tickets.merge(p, -1, Integer::sum);
        }

        @Override
        public void pump() {
            pumps++;
            List<Runnable> now = new ArrayList<>(pending);
            pending.clear();
            now.forEach(Runnable::run);
        }

        @Override
        public long usedHeap() {
            return used;
        }

        @Override
        public long maxHeap() {
            return max;
        }

        private <T> CompletableFuture<T> later(T value, boolean fail) {
            CompletableFuture<T> f = new CompletableFuture<>();
            outstanding++;
            maxOutstanding = Math.max(maxOutstanding, outstanding);
            pending.add(() -> {
                outstanding--;
                if (fail) f.completeExceptionally(new IllegalStateException("worldgen failed"));
                else f.complete(value);
            });
            return f;
        }

        boolean noTicketLeft() {
            return tickets.values().stream().allMatch(n -> n == 0);
        }
    }

    static final class FakeControl implements SpawnPrepRunner.Control {
        boolean cancel, stop;
        long nanos;
        int cancelAfterPumps = -1;
        FakeBackend backend;

        @Override
        public boolean cancelRequested() {
            return cancel || (cancelAfterPumps >= 0 && backend.pumps >= cancelAfterPumps);
        }

        @Override
        public boolean stopping() {
            return stop;
        }

        @Override
        public long nanoTime() {
            return nanos += 100_000_000L;          // 0.1 s per call
        }
    }

    private static SpawnPrepRunner.Result run(List<SpawnPrepPlan.Pos> plan, int maxInFlight, FakeBackend b, FakeControl c,
                                              List<String> log) {
        c.backend = b;
        return new SpawnPrepRunner(plan, maxInFlight, b, c, log::add, r -> { }).run();
    }

    @Test
    void finishedChunksAreNeverRequested() {
        FakeBackend b = new FakeBackend();
        b.complete.add(new SpawnPrepPlan.Pos(0, 0));
        SpawnPrepRunner.Result r = run(SpawnPrepPlan.order(0, 0, 1), 4, b, new FakeControl(), new ArrayList<>());
        assertEquals(SpawnPrepMarker.State.COMPLETE, r.state());
        assertEquals(9, r.done());
        assertEquals(1, r.existing());
        assertEquals(8, r.generated());
        assertFalse(b.generated.contains(new SpawnPrepPlan.Pos(0, 0)));
        assertTrue(b.noTicketLeft());
    }

    @Test
    void workInFlightStaysBounded() {
        FakeBackend b = new FakeBackend();
        SpawnPrepRunner.Result r = run(SpawnPrepPlan.order(0, 0, 4), 3, b, new FakeControl(), new ArrayList<>());
        assertEquals(81, r.generated());
        assertTrue(b.maxOutstanding <= 3, "max outstanding " + b.maxOutstanding);
        assertTrue(b.noTicketLeft());
    }

    @Test
    void cancelStopsEarlyAndReleasesEveryTicket() {
        FakeBackend b = new FakeBackend();
        FakeControl c = new FakeControl();
        c.cancelAfterPumps = 3;
        SpawnPrepRunner.Result r = run(SpawnPrepPlan.order(0, 0, 4), 2, b, c, new ArrayList<>());
        assertEquals(SpawnPrepMarker.State.CANCELLED, r.state());
        assertTrue(r.done() < 81);
        assertTrue(b.noTicketLeft());
    }

    @Test
    void serverStopLeavesAResumablePartialRun() {
        FakeBackend b = new FakeBackend();
        FakeControl c = new FakeControl();
        c.stop = true;
        SpawnPrepRunner.Result r = run(SpawnPrepPlan.order(0, 0, 2), 2, b, c, new ArrayList<>());
        assertEquals(SpawnPrepMarker.State.PARTIAL, r.state());
        assertTrue(b.noTicketLeft());
    }

    @Test
    void aFailedChunkIsRetriedNextLaunchNotMarkedDone() {
        FakeBackend b = new FakeBackend();
        b.failing.add(new SpawnPrepPlan.Pos(1, 0));
        SpawnPrepRunner.Result r = run(SpawnPrepPlan.order(0, 0, 1), 4, b, new FakeControl(), new ArrayList<>());
        assertEquals(SpawnPrepMarker.State.PARTIAL, r.state());
        assertEquals(1, r.failed());
        assertEquals(8, r.done());
        assertTrue(b.noTicketLeft());
    }

    @Test
    void lowHeapPausesNewRequests() {
        FakeBackend b = new FakeBackend() {
            @Override
            public void pump() {
                super.pump();
                if (pumps >= 5) used = 100;
            }
        };
        b.used = 900;                                // 90 % of max
        SpawnPrepRunner.Result r = run(SpawnPrepPlan.order(0, 0, 1), 4, b, new FakeControl(), new ArrayList<>());
        assertEquals(SpawnPrepMarker.State.COMPLETE, r.state());
        assertTrue(r.heapPauses() >= 4, "heap pauses " + r.heapPauses());
    }

    @Test
    void progressAndDoneLinesUseTheStableKeyValueFormat() {
        List<String> log = new ArrayList<>();
        FakeBackend b = new FakeBackend();
        b.complete.add(new SpawnPrepPlan.Pos(0, 0));
        run(SpawnPrepPlan.order(0, 0, 1), 4, b, new FakeControl(), log);
        assertTrue(log.stream().anyMatch(l -> l.matches(
            "\\[PhoenixSpawnPrep] PROGRESS done=\\d+ total=9 pct=\\d+\\.\\d generated=\\d+ existing=\\d+ failed=0 cps=\\d+\\.\\d etaS=\\d+ inFlight=\\d+ heapMB=\\d+/\\d+")),
            String.join("\n", log));
        String last = log.get(log.size() - 1);
        assertTrue(last.matches("\\[PhoenixSpawnPrep] DONE state=COMPLETE done=9 total=9 generated=8 existing=1 failed=0 seconds=\\d+\\.\\d"), last);
    }
}
