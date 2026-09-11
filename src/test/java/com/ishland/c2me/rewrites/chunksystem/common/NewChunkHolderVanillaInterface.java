package com.ishland.c2me.rewrites.chunksystem.common;

import com.ishland.flowsched.scheduler.ItemStatus;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

/** Test-only provider shape that deliberately churns its public wrapper like the locked C2ME holder. */
public class NewChunkHolderVanillaInterface extends ChunkHolder {
    public static final class Backing {
        private volatile CompletableFuture<Void> slot = new CompletableFuture<>();
        public synchronized CompletableFuture<Void> getFutureForStatus0(ItemStatus status) { return slot; }
        public CompletableFuture<Void> slot() { return slot; }
        public void replace(CompletableFuture<Void> replacement) { slot = replacement; }
    }

    private final Backing backing = new Backing();
    private final LevelChunk chunk;
    private int wrapperCalls;

    public NewChunkHolderVanillaInterface(LevelChunk chunk) {
        super(ChunkPos.ZERO, 33, net.minecraft.world.level.LevelHeightAccessor.create(0, 256), null,
                (position, previous, next, setter) -> setter.accept(next), (position, borderOnly) -> List.of());
        this.chunk = chunk;
    }

    public Backing getBackingHolder() { return backing; }
    public CompletableFuture<Void> directSlot() { return backing.slot(); }
    public void replaceSlot(CompletableFuture<Void> replacement) { backing.replace(replacement); }
    public int wrapperCalls() { return wrapperCalls; }

    @Override public CompletableFuture<ChunkResult<LevelChunk>> getTickingChunkFuture() {
        wrapperCalls++;
        CompletableFuture<Void> observed = backing.slot();
        return observed.thenApply(ignored -> ChunkResult.of(chunk));
    }
}
