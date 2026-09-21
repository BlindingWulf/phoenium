package dev.phoenium.mixin;

import java.util.function.Consumer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ServerChunkCache.class)
public interface RegionalServerChunkCacheAccess {
    @Accessor("level") ServerLevel phoenium$getLevel();
    @Invoker("tickChunks") void phoenium$invokeTickChunks();
    @Invoker("getFullChunk") void phoenium$invokeGetFullChunk(long chunkKey, Consumer<LevelChunk> output);
    /** Select the native future without getChunkFuture's implicit managedBlock. */
    @Invoker("getChunkFutureMainThread") java.util.concurrent.CompletableFuture<net.minecraft.server.level.ChunkResult<net.minecraft.world.level.chunk.ChunkAccess>>
        phoenium$naturalSpawnFuture(int x, int z, net.minecraft.world.level.chunk.status.ChunkStatus status, boolean create);
}
