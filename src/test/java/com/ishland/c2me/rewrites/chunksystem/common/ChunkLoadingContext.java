package com.ishland.c2me.rewrites.chunksystem.common;

import com.ishland.flowsched.scheduler.ItemHolder;
import net.minecraft.server.level.ChunkMap;

/** Test-only record carrying the exact generation-lineage accessors used by the locked provider. */
public record ChunkLoadingContext(ItemHolder holder, ChunkMap tacs, TheChunkSystem theChunkSystem) { }
