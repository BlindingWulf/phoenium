package dev.phoenium.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * S9-E1: one tile's work in one phase: its entities in tick-list order (entity phase) or its chunks in loop order
 * (S9-E3 chunk phase), and the effects its run deferred (applied by the server thread in order). Reused across ticks by
 * its level's phase.
 */
final class TileWork {
    long key;
    int tileX;
    int tileZ;
    int colour;
    final ArrayList<Entity> entities = new ArrayList<>();
    /** S9-E3: chunks whose ServerChunkCache.tickSpawningChunk call this tile runs, in the vanilla loop's order. */
    final ArrayList<LevelChunk> spawnChunks = new ArrayList<>();
    /** S9-E3: chunks whose ServerLevel.tickChunk call this tile runs, in the vanilla loop's order. */
    final ArrayList<LevelChunk> tickChunks = new ArrayList<>();
    final ArrayList<Runnable> deferred = new ArrayList<>();
    /** Wall time of this tile's last run, for the next largest-first dispatch order. */
    long nanos;

    // S9-E3 dag schedule: written by the server thread before the job is published, then read by the tile threads
    /** Neighbours (claims that touch) of a higher colour: they start only after this tile is done. */
    TileWork[] successors = new TileWork[8];
    int successorCount;
    /** Neighbours of a lower colour while the schedule is built; the job counts them down. */
    int predecessors;
    final AtomicInteger pending = new AtomicInteger();

    void reset(long key, int tileX, int tileZ, int colour) {
        this.key = key;
        this.tileX = tileX;
        this.tileZ = tileZ;
        this.colour = colour;
        entities.clear();
        spawnChunks.clear();
        tickChunks.clear();
        deferred.clear();
        nanos = 0L;
        clearSchedule();
    }

    void clearSchedule() {
        if (successorCount > 0) Arrays.fill(successors, 0, successorCount, null);
        successorCount = 0;
        predecessors = 0;
        pending.set(0);
    }

    void addSuccessor(TileWork next) {
        if (successorCount == successors.length) successors = Arrays.copyOf(successors, successorCount * 2);
        successors[successorCount++] = next;
        next.predecessors++;
    }

    /** A rough cost for dispatch order before the tile has a measured run time. */
    int weight() {
        return entities.size() + tickChunks.size() + 4 * spawnChunks.size();
    }
}
