package com.ishland.c2me.rewrites.chunksystem.common.statuses;

import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Test-only delegate shape; production links to C2ME only through reflection and @Pseudo Mixin. */
public final class VanillaWorldGenerationDelegate {
    private final ChunkStatus status;
    public VanillaWorldGenerationDelegate(ChunkStatus status) { this.status = status; }
}
