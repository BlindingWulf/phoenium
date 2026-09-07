package dev.phoenium.chunk;

import dev.phoenium.ownership.OwnershipContext;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import net.minecraft.server.level.ServerLevel;

/** The pinned WorldGenRegion difficulty reader shares the live environment tick cache.
 * Its worker holds generation borrows, but no region or service turn. Only its original
 * moon getter is delivered to the chunk event loop; no spawn body, random draw, difficulty
 * prefix or entity construction is moved, precomputed or replayed. */
public final class FullWorldgenEnvironment {
    private FullWorldgenEnvironment() { }
    public static float moonBrightness(ServerLevel world, Executor executor, Supplier<Float> original) {
        if (OwnershipContext.current().isPresent() || FullFinishingServices.finishing())
            throw ChunkSynchronousAccessGuard.breach(FullFinishingServices.failure(
                    new ChunkProtocolException("world-generation environment wait attempted inside a gameplay claim")));
        if (world.getServer().isSameThread()) {
            try (var turn = FullFinishingServices.forServer(world.getServer()).serial(FullFinishingServices.Phase.SERVER_TASK)) {
                FullFinishingServices.requireWorld(world, FullFinishingServices.Kind.ENVIRONMENT);
                return original.get();
            }
        }
        var request = FullFinishingServices.scheduleEnvironment(world, executor, original);
        try { return request.join(); }
        catch (CompletionException wrapper) {
            if (wrapper.getCause() instanceof RuntimeException runtime) throw runtime;
            if (wrapper.getCause() instanceof Error error) throw error;
            throw wrapper;
        }
    }
}
