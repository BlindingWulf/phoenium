package dev.phoenium.engine;

import dev.phoenium.engine.mixin.EngineChunkMapAccess;
import java.util.IdentityHashMap;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;
import net.minecraft.world.level.pathfinder.PathTypeCache;
import net.minecraft.world.level.redstone.CollectingNeighborUpdater;

/**
 * S9-E1: one thread's tile state. While {@link #tile} is set this thread runs that tile: its level random, neighbour
 * updates, path-type cache and chunk lookups are its own, and shared effects go to the tile's deferred list. Owned and
 * read only by its thread (the server thread's context by the server thread).
 */
public final class TileContext {
    TileWork tile;
    ServerLevel level;
    int minTileX;
    int maxTileX;
    int minTileZ;
    int maxTileZ;
    final SingleThreadedRandomSource random = new SingleThreadedRandomSource(0L);
    final EnginePathTypeCache pathTypes = new EnginePathTypeCache();
    private final IdentityHashMap<Level, CollectingNeighborUpdater> updaters = new IdentityHashMap<>();
    private long phase = -1L;
    private long colour = -1L;
    private ServerChunkCache cacheOwner;
    private final long[] cacheKeys = new long[4];
    private final LevelChunk[] cacheChunks = new LevelChunk[4];
    private int cacheNext;
    long trapped;

    void enter(ServerLevel level, TileWork tile, long phase, long colourRun, long seed) {
        if (this.phase != phase) {
            this.phase = phase;
            clearChunkCache();
        }
        if (this.colour != colourRun) {
            this.colour = colourRun;
            pathTypes.reset();
        }
        this.level = level;
        int radius = EngineConfig.CLAIM_RADIUS;
        minTileX = tile.tileX - radius;
        maxTileX = tile.tileX + radius;
        minTileZ = tile.tileZ - radius;
        maxTileZ = tile.tileZ + radius;
        random.setSeed(seed);
        this.tile = tile;
    }

    void exit() {
        tile = null;
        level = null;
    }

    /** Whether chunk (x, z) is in the running tile's claim. */
    public boolean claims(int chunkX, int chunkZ) {
        int tileX = chunkX >> EngineConfig.TILE_SHIFT;
        int tileZ = chunkZ >> EngineConfig.TILE_SHIFT;
        return tileX >= minTileX && tileX <= maxTileX && tileZ >= minTileZ && tileZ <= maxTileZ;
    }

    public void defer(Runnable effect) { tile.deferred.add(effect); }

    public ServerLevel level() { return level; }

    /** The running tile's level random stream. */
    public RandomSource random() { return random; }

    /** This thread's path-type cache (ServerLevel.getPathTypeCache for a tile thread). */
    public PathTypeCache pathTypes() { return pathTypes; }

    CollectingNeighborUpdater neighborUpdater(Level owner, int maxChainedNeighborUpdates) {
        CollectingNeighborUpdater updater = updaters.get(owner);
        if (updater == null) {
            updater = new CollectingNeighborUpdater(owner, maxChainedNeighborUpdates);
            updaters.put(owner, updater);
        }
        return updater;
    }

    /** ServerChunkCache.getChunk for this thread: loaded chunks only; a miss that asks to load is a trap. */
    public ChunkAccess chunk(ServerChunkCache cache, int x, int z, ChunkStatus status, boolean load) {
        ChunkAccess found;
        if (status == ChunkStatus.FULL) found = fullChunk(cache, x, z);
        else {
            ChunkHolder holder = holder(cache, x, z);
            found = holder == null ? null : holder.getChunkIfPresent(status);
        }
        if (found != null || !load) return found;
        return TileEngine.loadTrap(this, cache, x, z, status);
    }

    /** ServerChunkCache.getChunkNow for this thread. */
    public LevelChunk fullChunk(ServerChunkCache cache, int x, int z) {
        if (cache != cacheOwner) {
            clearChunkCache();
            cacheOwner = cache;
        }
        long key = ChunkPos.pack(x, z);
        for (int i = 0; i < 4; i++) {
            LevelChunk cached = cacheChunks[i];
            if (cached != null && cacheKeys[i] == key) return cached;
        }
        ChunkHolder holder = holder(cache, x, z);
        LevelChunk chunk = holder != null && holder.getChunkIfPresent(ChunkStatus.FULL) instanceof LevelChunk full ? full : null;
        if (chunk != null) {
            cacheKeys[cacheNext] = key;
            cacheChunks[cacheNext] = chunk;
            cacheNext = (cacheNext + 1) & 3;
        }
        return chunk;
    }

    private static ChunkHolder holder(ServerChunkCache cache, int x, int z) {
        return ((EngineChunkMapAccess) cache.chunkMap).phoenium$visibleHolder(ChunkPos.pack(x, z));
    }

    private void clearChunkCache() {
        java.util.Arrays.fill(cacheChunks, null);
        cacheOwner = null;
        cacheNext = 0;
    }
}
