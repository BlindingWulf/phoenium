package dev.phoenium.engine;

import static org.junit.jupiter.api.Assertions.*;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * S9-E6: the tracker index's structure against brute force. Its answer for a player must be exactly the entries the
 * tracker filter passes (chunk within reach on both axes, or seen by the player's connection), each once; every entry
 * must sit in its chunk's tile bucket and the reverse map must equal the union of the seenBy sets, through random adds,
 * removals, moves (across tile edges and negative coordinates) and seenBy changes.
 */
class EngineTrackerIndexTest {
    private static final class FakeEntry extends EngineTrackerIndex.Entry {
        int x;
        int z;
        final Set<Object> seenBy = Collections.newSetFromMap(new IdentityHashMap<>());

        FakeEntry(int id, int x, int z) {
            super("entity-" + id);
            this.x = x;
            this.z = z;
        }

        @Override int chunkX() { return this.x; }

        @Override int chunkZ() { return this.z; }

        @Override Set<?> seenBy() { return this.seenBy; }
    }

    private static EngineTrackerIndex index(int shift) {
        return new EngineTrackerIndex(Thread.currentThread(), shift, new Int2ObjectOpenHashMap<>(), null);
    }

    @Test
    void answersExactlyTheFilteredSetThroughRandomChanges() {
        for (int shift = 0; shift <= 3; shift++) {
            Random random = new Random(0x5EED0000L + shift);
            EngineTrackerIndex index = index(shift);
            List<FakeEntry> live = new ArrayList<>();
            Object[] connections = new Object[6];
            for (int i = 0; i < connections.length; i++) connections[i] = new Object();
            int ids = 0;
            for (int step = 0; step < 6000; step++) {
                int op = random.nextInt(100);
                if (op < 25 || live.isEmpty()) {
                    FakeEntry entry = new FakeEntry(ids++, random.nextInt(81) - 40, random.nextInt(81) - 40);
                    // an entry may join with members already in seenBy (a rebuild indexes them)
                    if (random.nextInt(4) == 0) entry.seenBy.add(connections[random.nextInt(connections.length)]);
                    index.add(entry);
                    live.add(entry);
                } else if (op < 35) {
                    FakeEntry entry = live.remove(random.nextInt(live.size()));
                    index.remove(entry);
                    assertTrue(entry.removed);
                } else if (op < 65) {
                    FakeEntry entry = live.get(random.nextInt(live.size()));
                    if (random.nextInt(10) == 0) {
                        entry.x = random.nextInt(81) - 40;
                        entry.z = random.nextInt(81) - 40;
                    } else {
                        entry.x += random.nextInt(5) - 2;
                        entry.z += random.nextInt(5) - 2;
                    }
                    index.relocate(entry);
                } else if (op < 85) {
                    FakeEntry entry = live.get(random.nextInt(live.size()));
                    Object connection = connections[random.nextInt(connections.length)];
                    if (entry.seenBy.add(connection)) index.seen(entry, connection);
                } else {
                    FakeEntry entry = live.get(random.nextInt(live.size()));
                    if (entry.seenBy.isEmpty()) continue;
                    Object connection = entry.seenBy.iterator().next();
                    if (entry.seenBy.remove(connection)) index.unseen(entry, connection);
                }
                if (step % 25 == 0) verify(index, live, connections, random);
            }
            verify(index, live, connections, random);
        }
    }

    @Test
    void stampWrapKeepsAnswersExact() {
        EngineTrackerIndex index = index(2);
        Random random = new Random(42);
        List<FakeEntry> live = new ArrayList<>();
        Object[] connections = {new Object(), new Object()};
        for (int i = 0; i < 200; i++) {
            FakeEntry entry = new FakeEntry(i, random.nextInt(41) - 20, random.nextInt(41) - 20);
            if (i % 3 == 0) entry.seenBy.add(connections[i % 2]);
            index.add(entry);
            live.add(entry);
        }
        index.stampForTest(-3);   // the next stamps run through -2, -1, then wrap past 0
        for (int i = 0; i < 6; i++) verify(index, live, connections, random);
    }

    @Test
    void candidatesAreReusableOnceTheLoopEnds() {
        EngineTrackerIndex index = index(2);
        FakeEntry a = new FakeEntry(1, 0, 0);
        FakeEntry b = new FakeEntry(2, 1, 1);
        index.add(a);
        index.add(b);
        EngineTrackerIndex.Candidates out = new EngineTrackerIndex.Candidates();
        out.busy = true;
        index.collect(0, 0, 2, new Object(), index.nextStamp(), out);
        assertEquals(2, out.size);
        int seen = 0;
        while (out.hasNext()) {
            assertTrue(out.busy);
            out.next();
            seen++;
        }
        assertEquals(2, seen);
        assertFalse(out.busy, "released after the end");
    }

    private static void verify(EngineTrackerIndex index, List<FakeEntry> live, Object[] connections, Random random) {
        assertEquals(live.size(), index.size(), "entries");
        long members = 0;
        for (FakeEntry entry : live) {
            assertNull(index.placement(entry), "placement of " + entry.item);
            assertEquals(0, index.reverseMisses(entry), "reverse misses of " + entry.item);
            members += entry.seenBy.size();
        }
        assertEquals(members, index.seenTotal(), "reverse members");
        assertEquals(0, index.reverseExtras(), "reverse extras");
        for (int query = 0; query < 8; query++) {
            int px = random.nextInt(101) - 50;
            int pz = random.nextInt(101) - 50;
            int reach = 1 + random.nextInt(14);
            Object connection = connections[random.nextInt(connections.length)];
            EngineTrackerIndex.Candidates out = new EngineTrackerIndex.Candidates();
            index.collect(px, pz, reach, connection, index.nextStamp(), out);
            Set<Object> expected = new HashSet<>();
            for (FakeEntry entry : live) {
                boolean near = Math.abs(entry.x - px) <= reach && Math.abs(entry.z - pz) <= reach;
                if (near || entry.seenBy.contains(connection)) expected.add(entry.item);
            }
            Set<Object> actual = new HashSet<>();
            for (int i = 0; i < out.size; i++) assertTrue(actual.add(out.items[i]), "visited twice: " + out.items[i]);
            assertEquals(expected, actual, "query " + px + "," + pz + " reach " + reach);
            // S9-E13: the stamp-free (parallel-safe) walk visits the same set, each once
            Set<Object> quiet = new HashSet<>();
            index.collectQuiet(px, pz, reach, connection, item -> assertTrue(quiet.add(item), "quiet visited twice: " + item));
            assertEquals(expected, quiet, "quiet query " + px + "," + pz + " reach " + reach);
        }
    }
}
