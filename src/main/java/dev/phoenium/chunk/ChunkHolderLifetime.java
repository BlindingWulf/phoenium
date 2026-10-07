package dev.phoenium.chunk;

import net.minecraft.server.level.GenerationChunkHolder;

/** Exact original holder lifecycle attachment, independent of any one producing execution. */
public record ChunkHolderLifetime(ChunkPrerequisiteServices services, ChunkCustodyModel.LifecycleId lifecycle,
                                  GenerationChunkHolder holder) {
    /** Losing provider resident visibility does not replace the provider's holder lifecycle. */
    public static void residentSourceRetired(GenerationChunkHolder holder) {
        var attachment = ((FullHolderEvidence) holder).phoenium$lifetime();
        if (attachment != null) attachment.services.retireResidentPublication(attachment);
    }
    /**
     * C2ME's final holder removal (flowsched {@code onItemRemoval}) is the provider twin of vanilla's pendingUnloads
     * removal. It runs under the provider's holder monitor on a scheduler thread, so retirement is handed to the
     * map's main executor and never runs inline.
     */
    public static void providerHolderRemoved(GenerationChunkHolder holder, java.util.function.Consumer<Runnable> mainExecutor) {
        java.util.Objects.requireNonNull(holder, "holder"); java.util.Objects.requireNonNull(mainExecutor, "mainExecutor");
    }
    public static void retired(GenerationChunkHolder holder) {
        var attachment = ((FullHolderEvidence) holder).phoenium$lifetime();
        if (attachment != null) attachment.services.retireResidentHolder(attachment);
    }
}
