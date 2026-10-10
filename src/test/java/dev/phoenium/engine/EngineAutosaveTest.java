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
    void playerQueueSavesEachOnceAndSkipsTheOnesThatLeft() {
        EngineAutosave.Queue<String> queue = new EngineAutosave.Queue<>();
        assertFalse(queue.begin(new String[] {"a", "b", "gone", "c"}));
        java.util.List<String> saved = new java.util.ArrayList<>();
        long[] now = {0L};
        int drains = 0;
        while (queue.busy()) {
            // each save takes 6 ms of a 2 ms budget: one player per tick; "gone" left meanwhile and is skipped
            queue.drain(name -> {
                if (name.equals("gone")) return false;
                now[0] += 6_000_000L;
                return saved.add(name);
            }, 64, 2_000_000L, () -> now[0]);
            drains++;
        }
        assertEquals(java.util.List.of("a", "b", "c"), saved);
        assertEquals(3, drains, "the skipped player does not cost a tick of its own");
        assertTrue(queue.begin(new String[] {"x"}) == false);
        assertTrue(queue.drop());
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

    @Test
    void theLeadingKindTakesTurnsAmongTheBusyKinds() {
        int[] all = {1, 1, 1};
        assertEquals(EngineAutosave.CHUNKS, EngineAutosave.nextLead(-1, all));
        assertEquals(EngineAutosave.ENTITIES, EngineAutosave.nextLead(EngineAutosave.CHUNKS, all));
        assertEquals(EngineAutosave.PLAYERS, EngineAutosave.nextLead(EngineAutosave.ENTITIES, all));
        assertEquals(EngineAutosave.CHUNKS, EngineAutosave.nextLead(EngineAutosave.PLAYERS, all));
        int[] players = {0, 0, 1};
        assertEquals(EngineAutosave.PLAYERS, EngineAutosave.nextLead(EngineAutosave.PLAYERS, players), "the only busy kind leads again");
        assertEquals(EngineAutosave.PLAYERS, EngineAutosave.nextLead(-1, players));
        assertEquals(-1, EngineAutosave.nextLead(EngineAutosave.CHUNKS, new int[] {0, 0, 0}));
    }

    @Test
    void theTickBudgetIsSharedAndOnlyTheLeadDrainsWhileItHasWork() {
        // defaults: 2000 us per queue and 2000 us per tick for all queues together (S9-E15)
        assertEquals(2_000, EngineConfig.AUTOSAVE_TICK_BUDGET_US);
        int[] busy = {2, 1, 1};
        int lead = EngineAutosave.CHUNKS;
        assertEquals(2_000_000L, EngineAutosave.budgetNanos(EngineAutosave.CHUNKS, lead, busy, 0L));
        assertEquals(500_000L, EngineAutosave.budgetNanos(EngineAutosave.CHUNKS, lead, busy, 1_500_000L), "the second chunk queue gets what is left");
        assertEquals(-1L, EngineAutosave.budgetNanos(EngineAutosave.CHUNKS, lead, busy, 2_000_000L), "spent: wait for the next tick");
        assertEquals(-1L, EngineAutosave.budgetNanos(EngineAutosave.ENTITIES, lead, busy, 0L), "not the lead: wait");
        assertEquals(-1L, EngineAutosave.budgetNanos(EngineAutosave.PLAYERS, lead, busy, 0L));
        int[] leadDone = {0, 1, 1};
        assertEquals(1_200_000L, EngineAutosave.budgetNanos(EngineAutosave.ENTITIES, lead, leadDone, 800_000L),
            "the lead kind finished this tick: the others may use the rest");
        assertEquals(2_000_000L, EngineAutosave.budgetNanos(EngineAutosave.PLAYERS, -1, busy, 0L), "no lead (no tick hook): own budget");
    }
}
