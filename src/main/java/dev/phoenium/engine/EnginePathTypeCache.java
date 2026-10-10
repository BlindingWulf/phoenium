package dev.phoenium.engine;

import dev.phoenium.engine.mixin.EngineWalkNodeEvaluatorAccess;
import it.unimi.dsi.fastutil.HashCommon;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathTypeCache;

/**
 * S9-E1: a tile thread's ServerLevel path-type cache. Vanilla's single cache writes a position and its type as two
 * separate stores, so two threads can pair one position with the other's type. Each tile thread has its own, emptied
 * in O(1) at every colour (block changes of earlier colours are applied between colours and would otherwise be stale).
 */
final class EnginePathTypeCache extends PathTypeCache {
    private static final int SIZE = 4096;
    private static final int MASK = SIZE - 1;
    private final long[] positions = new long[SIZE];
    private final PathType[] types = new PathType[SIZE];
    private final int[] generations = new int[SIZE];
    private int generation = 1;

    void reset() {
        if (++generation == 0) {
            java.util.Arrays.fill(generations, 0);
            generation = 1;
        }
    }

    @Override
    public PathType getOrCompute(BlockGetter level, BlockPos pos) {
        long key = pos.asLong();
        int index = (int) HashCommon.mix(key) & MASK;
        if (generations[index] == generation && positions[index] == key) {
            PathType cached = types[index];
            if (cached != null) return cached;
        }
        PathType type = EngineWalkNodeEvaluatorAccess.phoenium$pathTypeFromState(level, pos);
        positions[index] = key;
        types[index] = type;
        generations[index] = generation;
        return type;
    }

    @Override
    public void invalidate(BlockPos pos) {
        long key = pos.asLong();
        int index = (int) HashCommon.mix(key) & MASK;
        if (generations[index] == generation && positions[index] == key) types[index] = null;
    }
}
