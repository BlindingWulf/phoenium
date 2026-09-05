package dev.phoenium.chunk;

import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import net.minecraft.server.level.*;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStep;

/** Test-agent equivalents of only the installed Mixin call-site plumbing. */
public final class FullRegistrationTestHooks {
    public static CompletableFuture<ChunkAccess> applyWinning(GeneratingChunkMap map, GenerationChunkHolder holder,
            ChunkStep step, StaticCache2D<GenerationChunkHolder> cache) {
        return FullRegistrationRuntime.applyWinning(map, holder, step, cache, () -> map.applyStep(holder, step, cache));
    }
    public static CompletableFuture<ChunkResult<ChunkAccess>> handle(CompletableFuture<ChunkAccess> returned,
            BiFunction<ChunkAccess, Throwable, ChunkResult<ChunkAccess>> handler, GenerationChunkHolder holder,
            ChunkStep step, GeneratingChunkMap map) {
        var bound = FullRegistrationRuntime.attach(map, returned, holder, step, handler);
        return bound == null ? returned.handle(handler) : bound;
    }
}
