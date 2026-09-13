package com.ishland.c2me.rewrites.chunksystem.common;

import com.ishland.flowsched.scheduler.ItemStatus;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Test-only shape matching the locked public status constant used by the readiness adapter. */
public enum NewChunkStatus implements ItemStatus {
    DISK,
    GENERATION,
    SERVER_ACCESSIBLE,
    SERVER_ACCESSIBLE_CHUNK_SENDING,
    BLOCK_TICKING;

    public static NewChunkStatus fromVanillaStatus(ChunkStatus status) {
        if (status == ChunkStatus.EMPTY) return DISK;
        return status == ChunkStatus.FULL ? SERVER_ACCESSIBLE : GENERATION;
    }
}
