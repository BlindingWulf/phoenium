package dev.phoenium.engine;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

/**
 * S9-E20 (D44): a tile thread without a job parks longer the longer it stays idle (doubling from idleParkUs up to
 * idleParkMaxUs), so an idle or serial-only server stops waking its tile threads ~1000 times a second each. A published
 * job still unparks every tile thread at once, so the first job after a long idle starts as fast as before.
 */
class EngineIdleBackoffTest {
    private static final long MS = 1_000_000L;

    @Test
    void idleParksDoubleUpToTheMaximum() {
        assertEquals(2 * MS, EngineWorkerPool.Waiting.nextIdlePark(1 * MS, 1 * MS, 16 * MS));
        assertEquals(16 * MS, EngineWorkerPool.Waiting.nextIdlePark(8 * MS, 1 * MS, 16 * MS));
        assertEquals(16 * MS, EngineWorkerPool.Waiting.nextIdlePark(16 * MS, 1 * MS, 16 * MS));
        assertEquals(16 * MS, EngineWorkerPool.Waiting.nextIdlePark(12 * MS, 1 * MS, 16 * MS));
    }

    @Test
    void aMaximumAtOrBelowTheBaseMeansNoBackoff() {
        assertEquals(1 * MS, EngineWorkerPool.Waiting.nextIdlePark(1 * MS, 1 * MS, 1 * MS));
        assertEquals(1 * MS, EngineWorkerPool.Waiting.nextIdlePark(1 * MS, 1 * MS, 0L));
    }

    @Test
    void theFourPartWaitingKeepsD29IdleParks() {
        EngineWorkerPool.Waiting w = new EngineWorkerPool.Waiting(8_192, MS, false, 20_000L);
        assertEquals(MS, w.idleParkMaxNanos());
        assertEquals(MS, EngineWorkerPool.Waiting.D29.idleParkMaxNanos());
    }

    private static long idleParksOver(EngineWorkerPool.Waiting waiting, long sleepMs) throws InterruptedException {
        EngineWorkerPool pool = new EngineWorkerPool(2, java.util.function.Supplier::get, waiting);
        try {
            long before = pool.idleParks();               // the counter covers every pool of the JVM: count this window only
            Thread.sleep(sleepMs);
            return pool.idleParks() - before;
        } finally {
            pool.stop();
        }
    }

    @Test
    void backoffCutsIdleWakeups() throws InterruptedException {
        long flat = idleParksOver(new EngineWorkerPool.Waiting(0, MS, false, 20_000L, MS), 400);
        long backoff = idleParksOver(new EngineWorkerPool.Waiting(0, MS, false, 20_000L, 64 * MS), 400);
        assertTrue(flat > 0, "idle parks are counted");
        assertTrue(backoff <= flat * 3 / 4, "backoff " + backoff + " vs flat " + flat);
    }

    @Test
    void theFirstJobAfterALongIdleStillReachesTheTileThreads() throws InterruptedException {
        EngineWorkerPool pool = new EngineWorkerPool(2, java.util.function.Supplier::get,
            new EngineWorkerPool.Waiting(0, MS, false, 20_000L, 500 * MS));
        try {
            Thread.sleep(1_200);                       // every tile thread is deep in its longest park
            TileWork[] tiles = new TileWork[64];
            for (int i = 0; i < tiles.length; i++) {
                tiles[i] = new TileWork();
                tiles[i].reset(TileGeometry.key(i, 0), i, 0, 0);
            }
            Set<Thread> ran = ConcurrentHashMap.newKeySet();
            long start = System.nanoTime();
            pool.run(new EngineWorkerPool.ColourJob(tiles, tiles.length, (tile, context) -> {
                ran.add(Thread.currentThread());
                long until = System.nanoTime() + 300_000L;
                while (System.nanoTime() < until) Thread.onSpinWait();
            }), new TileContext());
            long tookMs = (System.nanoTime() - start) / MS;
            assertTrue(ran.stream().anyMatch(EngineWorkerPool::isWorker), "a tile thread took part: " + ran);
            assertTrue(tookMs < 250, "job took " + tookMs + " ms after the idle");
        } finally {
            pool.stop();
        }
    }
}
