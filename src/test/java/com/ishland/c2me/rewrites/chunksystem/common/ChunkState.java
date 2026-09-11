package com.ishland.c2me.rewrites.chunksystem.common;

import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Test-only record matching the locked C2ME provider state shape. */
public record ChunkState(ChunkAccess chunk, ProtoChunk protoChunk, ChunkStatus reachedStatus, boolean wasFullChunk) { }
