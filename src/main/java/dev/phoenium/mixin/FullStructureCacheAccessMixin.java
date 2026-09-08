package dev.phoenium.mixin;

import dev.phoenium.chunk.FullStructureInputs;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerChunkCache.class)
public interface FullStructureCacheAccessMixin extends FullStructureInputs.CacheView {
    @Override @Accessor("lastChunkPos") long[] phoenium$structureCachePositions();
    @Override @Accessor("lastChunkStatus") ChunkStatus[] phoenium$structureCacheStatuses();
    @Override @Accessor("lastChunk") ChunkAccess[] phoenium$structureCacheChunks();
}
