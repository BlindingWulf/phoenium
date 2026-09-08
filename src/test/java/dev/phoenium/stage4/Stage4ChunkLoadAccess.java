package dev.phoenium.stage4;

import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Test-only entry to the original native request producer, before its public blocking wait. */
@Mixin(ServerChunkCache.class)
public interface Stage4ChunkLoadAccess {
    @Invoker("getChunkFutureMainThread")
    CompletableFuture<ChunkResult<ChunkAccess>> phoenium$requestChunk(int x, int z, ChunkStatus status, boolean create);
}
