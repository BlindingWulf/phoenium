package dev.phoenium.engine;

import java.util.ArrayList;
import net.minecraft.world.entity.Entity;

/**
 * S9-E1: one tile's work in one phase: its entities in tick-list order, and the effects its run deferred (applied by the
 * server thread after the tile's colour, in order). Reused across ticks by its level's phase.
 */
final class TileWork {
    long key;
    int tileX;
    int tileZ;
    int colour;
    final ArrayList<Entity> entities = new ArrayList<>();
    final ArrayList<Runnable> deferred = new ArrayList<>();
    /** Wall time of this tile's last run, for the next largest-first dispatch order. */
    long nanos;

    void reset(long key, int tileX, int tileZ, int colour) {
        this.key = key;
        this.tileX = tileX;
        this.tileZ = tileZ;
        this.colour = colour;
        entities.clear();
        deferred.clear();
        nanos = 0L;
    }
}
