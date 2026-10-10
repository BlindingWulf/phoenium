package dev.phoenium.engine;

import static org.junit.jupiter.api.Assertions.*;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;

/**
 * S9-E12: the spread autosave's queue. Every recorded key gets its save call exactly once and in recorded order, a drain
 * stops at its save count or its time budget but always makes progress, a new recording reports an unfinished queue
 * (autosaveOverlap) and replaces it, and a full save drops it.
 */
class EngineAutosaveTest {
    private static LongLinkedOpenHashSet keys(int n) {
        LongLinkedOpenHashSet keys = new LongLinkedOpenHashSet();
        for (int i = 0; i < n; i++) keys.add(i * 31L - 500L);
        return keys;
    }

    @Test
    void everyKeyOnceInOrderWithinTheSaveCount() {
        EngineAutosave.Spread spread = new EngineAutosave.Spread();
        LongLinkedOpenHashSet recorded = keys(1000);
        assertFalse(spread.begin(recorded));
        LongArrayList seen = new LongArrayList();
        int drains = 0;
        while (spread.busy()) {
            int before = seen.size();
            // every third key is not unsaved (saved meanwhile): it is visited but does not count against the budget
            int saved = spread.drain(key -> { seen.add(key); return Math.floorMod(key, 3) != 0; }, 64, Long.MAX_VALUE, () -> 0L);
            assertTrue(saved <= 64);
            assertTrue(seen.size() > before, "progress");
            drains++;
        }
        assertEquals(new LongArrayList(recorded), seen);
        assertTrue(drains >= 1000 * 2 / 3 / 64, "spread over several ticks: " + drains);
        assertEquals(0, spread.backlog());
    }

    @Test
    void stopsAtTheTimeBudgetButAlwaysSavesOne() {
        EngineAutosave.Spread spread = new EngineAutosave.Spread();
        spread.begin(keys(10));
        long[] now = {0L};
        LongSupplier clock = () -> now[0];
        // each save takes 5 ms of the 2 ms budget: one key per drain
        int saved = spread.drain(key -> { now[0] += 5_000_000L; return true; }, 64, 2_000_000L, clock);
        assertEquals(1, saved);
        assertEquals(9, spread.backlog());
        // each save takes 0.5 ms: four fit in 2 ms
        saved = spread.drain(key -> { now[0] += 500_000L; return true; }, 64, 2_000_000L, clock);
        assertEquals(4, saved);
        assertEquals(5, spread.backlog());
    }

    @Test
    void anUnfinishedQueueIsReportedAndReplacedAndAFullSaveDropsIt() {
        EngineAutosave.Spread spread = new EngineAutosave.Spread();
        spread.begin(keys(100));
        spread.drain(key -> true, 10, Long.MAX_VALUE, () -> 0L);
        assertTrue(spread.begin(keys(20)), "overlap reported");
        assertEquals(20, spread.backlog());
        assertTrue(spread.drop());
        assertFalse(spread.busy());
        assertFalse(spread.drop());
        assertEquals(0, spread.drain(key -> true, 10, Long.MAX_VALUE, () -> 0L));
    }
}
