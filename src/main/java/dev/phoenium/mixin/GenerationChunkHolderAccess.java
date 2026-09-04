package dev.phoenium.mixin;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(GenerationChunkHolder.class)
public interface GenerationChunkHolderAccess {
    @Accessor("startedWork") AtomicReference<ChunkStatus> phoenium$startedWork();
    @Accessor("futures") AtomicReferenceArray<CompletableFuture<ChunkResult<ChunkAccess>>> phoenium$futures();
    @Accessor("highestAllowedStatus") ChunkStatus phoenium$highestAllowed();
    @Invoker("lambda$applyStep$0") ChunkResult<ChunkAccess> phoenium$complete(ChunkStep step,ChunkAccess value,Throwable failure);
    @Invoker("failAndClearPendingFuture") void phoenium$failAndClear(int index,
            CompletableFuture<ChunkResult<ChunkAccess>> future);
}
