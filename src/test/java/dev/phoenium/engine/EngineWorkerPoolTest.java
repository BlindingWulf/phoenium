package dev.phoenium.engine;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * S9-E1: a job runs every tile exactly once across the tile threads and the caller, and rethrows a tile's failure.
 * S9-E3: a dag job never runs two tiles whose claims touch at once and starts a tile only after its lower-colour
 * neighbours finished; a service runs on the caller's thread while no tile runs, and its result or failure reaches the
 * tile that asked.
 * S9-E9: a parallel-for job runs every item once, outside any tile, in any block size; a capturing job marks its threads
 * only while they run its items; a failure is rethrown and the pool goes on.
 */
class EngineWorkerPoolTest {
    private static TileWork[] tiles(int count) {
        TileWork[] tiles = new TileWork[count];
        for (int i = 0; i < count; i++) {
            tiles[i] = new TileWork();
            tiles[i].reset(TileGeometry.key(i, 0), i, 0, 0);
        }
        return tiles;
    }

    private static void spin(int iterations) {
        for (int i = 0; i < iterations; i++) Thread.onSpinWait();
    }

    /** A width x depth block of tiles scheduled as TileEngine.runDag does it. */
    private static final class Grid {
        final int width;
        final int span = 2 * EngineConfig.CLAIM_RADIUS;
        final TileWork[] tiles;
        final TileWork[] roots;
        int rootCount;

        Grid(int width, int depth) {
            this.width = width;
            tiles = new TileWork[width * depth];
            for (int z = 0; z < depth; z++) {
                for (int x = 0; x < width; x++) {
                    TileWork tile = new TileWork();
                    tile.reset(TileGeometry.key(x, z), x, z, TileGeometry.colour(x, z, EngineConfig.PERIOD));
                    tiles[z * width + x] = tile;
                }
            }
            for (TileWork tile : tiles) {
                for (TileWork other : tiles) {
                    if (neighbours(tile, other)) {
                        assertNotEquals(tile.colour, other.colour, "neighbours share a colour");
                        if (other.colour > tile.colour) tile.addSuccessor(other);
                    }
                }
            }
            roots = new TileWork[tiles.length];
            for (TileWork tile : tiles) {
                tile.pending.set(tile.predecessors);
                if (tile.predecessors == 0) roots[rootCount++] = tile;
            }
        }

        boolean neighbours(TileWork a, TileWork b) {
            return a != b && Math.abs(a.tileX - b.tileX) <= span && Math.abs(a.tileZ - b.tileZ) <= span;
        }

        int index(TileWork tile) { return tile.tileZ * width + tile.tileX; }
    }

    @Test
    void everyTileRunsOnceOverManyJobs() {
        EngineWorkerPool pool = new EngineWorkerPool(3);
        try {
            TileContext own = new TileContext();
            for (int round = 0; round < 200; round++) {
                TileWork[] tiles = tiles(1 + round % 23);
                AtomicInteger[] runs = new AtomicInteger[tiles.length];
                for (int i = 0; i < runs.length; i++) runs[i] = new AtomicInteger();
                ConcurrentHashMap<Thread, Boolean> threads = new ConcurrentHashMap<>();
                pool.run(new EngineWorkerPool.ColourJob(tiles, tiles.length, (tile, context) -> {
                    threads.put(Thread.currentThread(), Boolean.TRUE);
                    assertNotNull(context);
                    runs[tile.tileX].incrementAndGet();
                }), own);
                for (AtomicInteger count : runs) assertEquals(1, count.get());
                assertTrue(threads.size() <= 4);
            }
        } finally {
            pool.stop();
        }
    }

    @Test
    void aTileFailureIsRethrownAfterTheJobDrains() {
        EngineWorkerPool pool = new EngineWorkerPool(2);
        try {
            TileWork[] tiles = tiles(16);
            AtomicInteger finished = new AtomicInteger();
            IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                pool.run(new EngineWorkerPool.ColourJob(tiles, tiles.length, (tile, context) -> {
                    if (tile.tileX == 5) throw new IllegalStateException("tile 5");
                    finished.incrementAndGet();
                }), new TileContext()));
            assertEquals("tile 5", thrown.getMessage());
            assertTrue(finished.get() <= 15);
            // the pool still runs later jobs
            AtomicInteger later = new AtomicInteger();
            TileWork[] next = tiles(8);
            pool.run(new EngineWorkerPool.ColourJob(next, next.length, (tile, context) -> later.incrementAndGet()), new TileContext());
            assertEquals(8, later.get());
        } finally {
            pool.stop();
        }
    }

    @Test
    void aDagJobKeepsNeighboursApartAndPredecessorsFirst() {
        EngineWorkerPool pool = new EngineWorkerPool(3);
        try {
            TileContext own = new TileContext();
            for (int round = 0; round < 60; round++) {
                Grid grid = new Grid(3 + round % 10, 2 + round % 7);
                int n = grid.tiles.length;
                AtomicIntegerArray running = new AtomicIntegerArray(n);
                AtomicIntegerArray runs = new AtomicIntegerArray(n);
                long[] started = new long[n];
                long[] finished = new long[n];
                AtomicLong clock = new AtomicLong();
                ConcurrentLinkedQueue<String> violations = new ConcurrentLinkedQueue<>();
                pool.run(new EngineWorkerPool.DagJob(grid.roots, grid.rootCount, n, (tile, context) -> {
                    int self = grid.index(tile);
                    running.set(self, 1);
                    started[self] = clock.incrementAndGet();
                    for (int other = 0; other < n; other++) {
                        if (running.get(other) == 1 && grid.neighbours(tile, grid.tiles[other]))
                            violations.add(tile.tileX + "," + tile.tileZ + " ran beside " + grid.tiles[other].tileX + "," + grid.tiles[other].tileZ);
                    }
                    spin(100 + (self * 37) % 600);
                    runs.incrementAndGet(self);
                    finished[self] = clock.incrementAndGet();
                    running.set(self, 0);
                }), own);
                assertTrue(violations.isEmpty(), violations.toString());
                for (int i = 0; i < n; i++) assertEquals(1, runs.get(i), "runs of tile " + i);
                for (TileWork tile : grid.tiles) {
                    for (int s = 0; s < tile.successorCount; s++) {
                        TileWork next = tile.successors[s];
                        assertTrue(finished[grid.index(tile)] < started[grid.index(next)], "successor started before its predecessor finished");
                    }
                }
            }
        } finally {
            pool.stop();
        }
    }

    /** S9-E11: with dagWake, no spinning and long parks, every dag job still finishes at once and in order. */
    @Test
    void aDagJobWithWakeAndLongParksStillFinishesPromptly() {
        long longPark = 2_000_000_000L;
        EngineWorkerPool pool = new EngineWorkerPool(3, java.util.function.Supplier::get,
            new EngineWorkerPool.Waiting(0, longPark, true, longPark));
        try {
            TileContext own = new TileContext();
            long start = System.nanoTime();
            for (int round = 0; round < 60; round++) {
                Grid grid = new Grid(3 + round % 10, 2 + round % 7);
                int n = grid.tiles.length;
                AtomicIntegerArray runs = new AtomicIntegerArray(n);
                long[] started = new long[n];
                long[] finished = new long[n];
                AtomicLong clock = new AtomicLong();
                pool.run(new EngineWorkerPool.DagJob(grid.roots, grid.rootCount, n, (tile, context) -> {
                    int self = grid.index(tile);
                    started[self] = clock.incrementAndGet();
                    spin(50 + (self * 37) % 300);
                    runs.incrementAndGet(self);
                    finished[self] = clock.incrementAndGet();
                }), own);
                for (int i = 0; i < n; i++) assertEquals(1, runs.get(i), "runs of tile " + i);
                for (TileWork tile : grid.tiles) {
                    for (int s = 0; s < tile.successorCount; s++) {
                        TileWork next = tile.successors[s];
                        assertTrue(finished[grid.index(tile)] < started[grid.index(next)], "successor started before its predecessor finished");
                    }
                }
            }
            // a lost wake-up would cost a 2 s park; 60 rounds of a few ms of work each must take far less
            long millis = (System.nanoTime() - start) / 1_000_000L;
            assertTrue(millis < 1_500L, "60 dag rounds took " + millis + " ms");
        } finally {
            pool.stop();
        }
    }

    @Test
    void aDagJobDrainsAfterAFailure() {
        EngineWorkerPool pool = new EngineWorkerPool(2);
        try {
            Grid grid = new Grid(7, 5);
            AtomicInteger ran = new AtomicInteger();
            IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                pool.run(new EngineWorkerPool.DagJob(grid.roots, grid.rootCount, grid.tiles.length, (tile, context) -> {
                    if (tile.tileX == 3 && tile.tileZ == 2) throw new IllegalStateException("tile 3,2");
                    ran.incrementAndGet();
                }), new TileContext()));
            assertEquals("tile 3,2", thrown.getMessage());
            assertTrue(ran.get() < grid.tiles.length);
            Grid next = new Grid(4, 4);
            AtomicInteger later = new AtomicInteger();
            pool.run(new EngineWorkerPool.DagJob(next.roots, next.rootCount, next.tiles.length, (tile, context) -> later.incrementAndGet()),
                new TileContext());
            assertEquals(16, later.get());
        } finally {
            pool.stop();
        }
    }

    /** Asks for a service from a running tile; the counter holds the tiles that run (not paused) at any moment. */
    private static int ask(EngineWorkerPool pool, AtomicInteger runningTiles, Thread caller, ConcurrentLinkedQueue<String> violations, int value) {
        runningTiles.decrementAndGet();             // this tile pauses while its service runs
        try {
            return pool.current().service(() -> {
                if (Thread.currentThread() != caller) violations.add("service ran on " + Thread.currentThread().getName());
                int now = runningTiles.get();
                if (now != 0) violations.add("service ran while " + now + " tiles ran");
                spin(200);
                return value * 2;
            });
        } finally {
            runningTiles.incrementAndGet();
        }
    }

    @Test
    void servicesRunOnTheCallerWhileNoTileRuns() {
        Thread caller = Thread.currentThread();
        AtomicInteger served = new AtomicInteger();
        EngineWorkerPool pool = new EngineWorkerPool(3, work -> {
            served.incrementAndGet();
            return work.get();
        });
        try {
            TileContext own = new TileContext();
            AtomicInteger runningTiles = new AtomicInteger();
            int asked = 0;
            for (int round = 0; round < 150; round++) {
                ConcurrentLinkedQueue<String> violations = new ConcurrentLinkedQueue<>();
                AtomicInteger answered = new AtomicInteger();
                EngineWorkerPool.Runner runner = (tile, context) -> {
                    runningTiles.incrementAndGet();
                    try {
                        int self = (int) (tile.key ^ (tile.key >>> 32));
                        spin(50 + (self * 53) % 400);
                        if ((tile.tileX + tile.tileZ) % 3 == 0 && ask(pool, runningTiles, caller, violations, self) == self * 2) answered.incrementAndGet();
                        spin(50 + (self * 29) % 300);
                        if (tile.tileX % 5 == 1 && ask(pool, runningTiles, caller, violations, -self) == -self * 2) answered.incrementAndGet();
                    } finally {
                        runningTiles.decrementAndGet();
                    }
                };
                int expected = 0;
                if (round % 2 == 0) {
                    TileWork[] tiles = tiles(4 + round % 21);
                    for (TileWork tile : tiles) expected += (tile.tileX % 3 == 0 ? 1 : 0) + (tile.tileX % 5 == 1 ? 1 : 0);
                    pool.run(new EngineWorkerPool.ColourJob(tiles, tiles.length, runner), own);
                } else {
                    Grid grid = new Grid(3 + round % 8, 2 + round % 5);
                    for (TileWork tile : grid.tiles) expected += ((tile.tileX + tile.tileZ) % 3 == 0 ? 1 : 0) + (tile.tileX % 5 == 1 ? 1 : 0);
                    pool.run(new EngineWorkerPool.DagJob(grid.roots, grid.rootCount, grid.tiles.length, runner), own);
                }
                assertTrue(violations.isEmpty(), violations.toString());
                assertEquals(expected, answered.get());
                assertEquals(0, runningTiles.get());
                asked += expected;
            }
            assertEquals(asked, served.get());
        } finally {
            pool.stop();
        }
    }

    @Test
    void aServiceFailureReachesTheTileThatAsked() {
        EngineWorkerPool pool = new EngineWorkerPool(2);
        try {
            TileWork[] tiles = tiles(12);
            ConcurrentLinkedQueue<Integer> caught = new ConcurrentLinkedQueue<>();
            pool.run(new EngineWorkerPool.ColourJob(tiles, tiles.length, (tile, context) -> {
                if (tile.tileX % 4 == 1) {
                    try {
                        pool.current().service(() -> {
                            throw new IllegalArgumentException("load " + tile.tileX);
                        });
                    } catch (IllegalArgumentException expected) {
                        caught.add(tile.tileX);
                    }
                }
            }), new TileContext());
            assertEquals(Set.of(1, 5, 9), new HashSet<>(caught));
            // a failure the tile does not catch fails the job like any tile failure
            TileWork[] next = tiles(6);
            IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                pool.run(new EngineWorkerPool.ColourJob(next, next.length, (tile, context) -> {
                    if (tile.tileX == 2) pool.current().service(() -> {
                        throw new IllegalStateException("load 2");
                    });
                }), new TileContext()));
            assertEquals("load 2", thrown.getMessage());
        } finally {
            pool.stop();
        }
    }

    // ---- S9-E9 ----

    /** The context of the calling thread: a tile thread's own, else {@code own}. */
    private static TileContext contextOf(TileContext own) {
        Thread thread = Thread.currentThread();
        return EngineWorkerPool.isWorker(thread) ? EngineWorkerPool.contextOf(thread) : own;
    }

    /**
     * A tile thread leaves a job's work() (clearing its capture mark) only after the job's last item counted down, so the
     * caller may return first: no thread is capturing once each has left.
     */
    private static void assertNoneCapturing(EngineWorkerPool pool, TileContext own) {
        assertFalse(own.capturing);
        long deadline = System.nanoTime() + 2_000_000_000L;
        for (TileContext context : pool.contexts()) {
            // the mark is a plain field (each thread reads only its own): fence so every read here is a fresh one
            while (context.capturing && System.nanoTime() < deadline) java.lang.invoke.VarHandle.fullFence();
            assertFalse(context.capturing);
        }
    }

    @Test
    void aForJobRunsEveryItemOnceInAnyBlockSize() {
        EngineWorkerPool pool = new EngineWorkerPool(3);
        try {
            TileContext own = new TileContext();
            for (int round = 0; round < 300; round++) {
                int count = round % 7 == 0 ? round / 7 : 1 + (round * 131) % 2_000;
                int block = 1 + round % 64;
                AtomicIntegerArray runs = new AtomicIntegerArray(Math.max(1, count));
                ConcurrentHashMap<Thread, Boolean> threads = new ConcurrentHashMap<>();
                EngineWorkerPool.ForJob job = new EngineWorkerPool.ForJob(count, block, index -> {
                    threads.put(Thread.currentThread(), Boolean.TRUE);
                    assertNull(contextOf(own).tile, "a parallel-for item runs outside any tile");
                    runs.incrementAndGet(index);
                }, false);
                pool.run(job, own);
                for (int i = 0; i < count; i++) assertEquals(1, runs.get(i), "runs of item " + i + " of " + count);
                assertTrue(threads.size() <= 4);
                assertTrue(job.workNanos.get() >= 0L);
            }
        } finally {
            pool.stop();
        }
    }

    @Test
    void aCapturingForJobMarksOnlyItsOwnRun() {
        EngineWorkerPool pool = new EngineWorkerPool(3);
        try {
            TileContext own = new TileContext();
            ConcurrentLinkedQueue<String> violations = new ConcurrentLinkedQueue<>();
            pool.run(new EngineWorkerPool.ForJob(500, 4, index -> {
                if (!contextOf(own).capturing) violations.add("item " + index + " ran without capturing");
                spin(50);
            }, true), own);
            pool.run(new EngineWorkerPool.ForJob(500, 4, index -> {
                if (contextOf(own).capturing) violations.add("item " + index + " of a plain job ran capturing");
            }, false), own);
            assertTrue(violations.isEmpty(), violations.toString());
            assertNoneCapturing(pool, own);
        } finally {
            pool.stop();
        }
    }

    @Test
    void aForJobFailureIsRethrownAndThePoolGoesOn() {
        EngineWorkerPool pool = new EngineWorkerPool(2);
        try {
            TileContext own = new TileContext();
            AtomicInteger ran = new AtomicInteger();
            IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                pool.run(new EngineWorkerPool.ForJob(1_000, 8, index -> {
                    if (index == 437) throw new IllegalStateException("item 437");
                    ran.incrementAndGet();
                }, true), own));
            assertEquals("item 437", thrown.getMessage());
            assertTrue(ran.get() < 1_000);
            assertNoneCapturing(pool, own);
            AtomicInteger later = new AtomicInteger();
            pool.run(new EngineWorkerPool.ForJob(300, 16, index -> later.incrementAndGet(), false), own);
            assertEquals(300, later.get());
        } finally {
            pool.stop();
        }
    }
}
