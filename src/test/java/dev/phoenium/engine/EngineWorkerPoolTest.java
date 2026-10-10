package dev.phoenium.engine;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** S9-E1: a job runs every tile exactly once across the tile threads and the caller, and rethrows a tile's failure. */
class EngineWorkerPoolTest {
    private static TileWork[] tiles(int count) {
        TileWork[] tiles = new TileWork[count];
        for (int i = 0; i < count; i++) {
            tiles[i] = new TileWork();
            tiles[i].reset(TileGeometry.key(i, 0), i, 0, 0);
        }
        return tiles;
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
                pool.run(new EngineWorkerPool.Job(tiles, tiles.length, (tile, context) -> {
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
                pool.run(new EngineWorkerPool.Job(tiles, tiles.length, (tile, context) -> {
                    if (tile.tileX == 5) throw new IllegalStateException("tile 5");
                    finished.incrementAndGet();
                }), new TileContext()));
            assertEquals("tile 5", thrown.getMessage());
            assertTrue(finished.get() <= 15);
            // the pool still runs later jobs
            AtomicInteger later = new AtomicInteger();
            TileWork[] next = tiles(8);
            pool.run(new EngineWorkerPool.Job(next, next.length, (tile, context) -> later.incrementAndGet()), new TileContext());
            assertEquals(8, later.get());
        } finally {
            pool.stop();
        }
    }
}
