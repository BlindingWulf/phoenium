package dev.phoenium.engine;

import java.util.ArrayList;

/**
 * S9-E19 (D43): whether a phase's tiles can overlap enough to be worth the tile threads. A phase whose tiles all touch
 * (players together: the phone's still runs, X1 cluster-v2) forms a chain in which every tile waits for the one before,
 * so the tile threads add hand-offs and run tiles on whatever core they sit on (a phone's slow cores) without any
 * overlap. Such a phase runs on the server thread alone, in the same tile order and with the same deferred-effect
 * order as its parallel run; spread tiles keep the threads.
 *
 * <p>The width is structural (tile weights from entity and chunk counts, not measured times), so the choice is the
 * same on every run of the same world state.
 */
final class EngineSchedule {
    private EngineSchedule() { }

    private static long weight(TileWork tile) { return Math.max(1, tile.weight()); }

    /**
     * Total weight over the heaviest chain of successors, for a dag whose successors are built ({@code colours} in colour
     * order is a topological order: a successor always has a higher colour). Uses {@link TileWork#chainWeight} as scratch.
     */
    static double dagWidth(ArrayList<TileWork>[] colours) {
        long total = 0L;
        for (ArrayList<TileWork> work : colours) for (int i = 0, n = work.size(); i < n; i++) work.get(i).chainWeight = 0L;
        long critical = 0L;
        for (ArrayList<TileWork> work : colours) {
            for (int i = 0, n = work.size(); i < n; i++) {
                TileWork tile = work.get(i);
                long w = weight(tile);
                total += w;
                long finish = tile.chainWeight + w;
                if (finish > critical) critical = finish;
                for (int k = 0; k < tile.successorCount; k++) {
                    TileWork next = tile.successors[k];
                    if (next.chainWeight < finish) next.chainWeight = finish;
                }
            }
        }
        return critical == 0L ? 0.0 : (double) total / critical;
    }

    /** Total weight over the sum of each colour's heaviest tile (colour by colour, a barrier after each colour). */
    static double colourWidth(ArrayList<TileWork>[] colours) {
        long total = 0L;
        long critical = 0L;
        for (ArrayList<TileWork> work : colours) {
            long largest = 0L;
            for (int i = 0, n = work.size(); i < n; i++) {
                long w = weight(work.get(i));
                total += w;
                if (w > largest) largest = w;
            }
            critical += largest;
        }
        return critical == 0L ? 0.0 : (double) total / critical;
    }

    /** Whether a phase of {@code tiles} tiles and this width runs on the server thread alone; {@code minWidth} 0 = never (D42). */
    static boolean serial(double width, int tiles, double minWidth) {
        if (minWidth <= 0.0) return false;
        return tiles < 2 || width < minWidth;
    }
}
