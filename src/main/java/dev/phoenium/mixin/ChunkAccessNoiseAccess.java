package dev.phoenium.mixin;

import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** S9-M1: read/clear the proto chunk's generation-only noise chunk (dev.phoenium.mem.NoiseRelease). */
@Mixin(ChunkAccess.class)
public interface ChunkAccessNoiseAccess {
    @Accessor("noiseChunk")
    NoiseChunk phoenium$noiseChunk();

    @Accessor("noiseChunk")
    void phoenium$setNoiseChunk(NoiseChunk noiseChunk);
}
