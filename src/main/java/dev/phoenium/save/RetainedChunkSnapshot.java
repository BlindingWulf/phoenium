package dev.phoenium.save;

import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;

/** Detaches the mutable saved-tag aliases left by the pinned original copyOf.
 * Sections, light layers, heightmaps, upgrade data and post-processing arrays have
 * already been copied by that producer. Do not rerun any original serializer. */
public final class RetainedChunkSnapshot {
    private RetainedChunkSnapshot() { }

    public static SerializableChunkData detach(SerializableChunkData original) {
        return new SerializableChunkData(original.containerFactory(), original.chunkPos(),
                original.minSectionY(), original.lastUpdateTime(), original.inhabitedTime(),
                original.chunkStatus(), original.blendingData(), original.belowZeroRetrogen(),
                original.upgradeData(), original.carvingMask(), original.heightmaps(),
                new ChunkAccess.PackedTicks(List.copyOf(original.packedTicks().blocks()),
                        List.copyOf(original.packedTicks().fluids())),
                original.postProcessingSections(), original.lightCorrect(), original.sectionData(),
                detachTags(original.entities()), detachTags(original.blockEntities()),
                original.structureData().copy());
    }

    private static List<CompoundTag> detachTags(List<CompoundTag> source) {
        return source.stream().map(CompoundTag::copy).toList();
    }
}
