package dev.phoenium.chunk;

import net.minecraft.server.level.GenerationChunkHolder;

/** Exact original holder lifecycle attachment, independent of any one producing execution. */
public record ChunkHolderLifetime(ChunkPrerequisiteServices services, ChunkCustodyModel.LifecycleId lifecycle,
                                  GenerationChunkHolder holder) {
    public static void retired(GenerationChunkHolder holder) {
        var attachment = ((FullHolderEvidence) holder).phoenium$lifetime();
        if (attachment != null) attachment.services.retireResidentHolder(attachment);
    }
}
