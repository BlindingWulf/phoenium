package dev.phoenium.chunk;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.*;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** References to the actual pinned holder's reservation, slots and generation retention. */
public interface FullHolderEvidence {
    AtomicReference<ChunkStatus> phoenium$startedWork();
    ChunkStatus phoenium$highestAllowed();
    AtomicReferenceArray<CompletableFuture<ChunkResult<ChunkAccess>>> phoenium$futures();
    AtomicInteger phoenium$generationRefs();
    CompletableFuture<Void> phoenium$generationSaveSync();
    void phoenium$failAndClear(int index, CompletableFuture<ChunkResult<ChunkAccess>> future);
    FullPublishedInputs.Entry phoenium$publication();
    void phoenium$publication(FullPublishedInputs.Entry entry);
    ChunkHolderLifetime phoenium$lifetime();
    void phoenium$lifetime(ChunkHolderLifetime lifetime);
}
