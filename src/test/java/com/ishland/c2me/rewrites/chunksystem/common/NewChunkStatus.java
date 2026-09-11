package com.ishland.c2me.rewrites.chunksystem.common;

import com.ishland.flowsched.scheduler.ItemStatus;

/** Test-only shape matching the locked public status constant used by the readiness adapter. */
public enum NewChunkStatus implements ItemStatus {
    BLOCK_TICKING
}
