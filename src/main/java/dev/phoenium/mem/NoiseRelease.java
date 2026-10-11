package dev.phoenium.mem;

import dev.phoenium.mixin.ChunkAccessNoiseAccess;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.slf4j.Logger;

/**
 * S9-M1 (MEM-2): drops a proto chunk's {@code NoiseChunk} once the CARVERS step has finished.
 *
 * <p>Vanilla creates the noise chunk at BIOMES ({@code getOrCreateNoiseChunk}) and reads it only in BIOMES, NOISE,
 * SURFACE and CARVERS, but keeps it until the proto chunk is replaced by a level chunk at FULL. Chunks parked below FULL
 * at the edge of the generation pyramid (FEATURES, INITIALIZE_LIGHT, LIGHT, SPAWN) therefore hold their noise chunk -
 * with C2ME's density-function compiler that is the compiled per-chunk function tree, interpolators, aquifer and cache
 * arrays (~44 KB each, MEM-1 / MEM-2) - for as long as a player stays nearby. Nothing reads it after CARVERS, and if
 * anything ever asked again {@code getOrCreateNoiseChunk} would rebuild it, so releasing it changes no output.
 *
 * <p>{@code -Dphoenium.mem.releaseNoise=false} keeps vanilla behaviour (MemSwitches; the mixins are then not applied).
 */
public final class NoiseRelease {
    public static final boolean ENABLED = MemSwitches.RELEASE_NOISE;
    private static final AtomicLong RELEASED = new AtomicLong();
    private static final AtomicLong DEFERRED = new AtomicLong();

    private NoiseRelease() {
    }

    /** Server init (both modes): logs the setting once and the counters at stop. */
    public static void install(Logger logger) {
        logger.info("PHOENIUM_MEM releaseNoise={}", ENABLED);
        ServerLifecycleEvents.SERVER_STOPPED.register(server ->
            logger.info("PHOENIUM_MEM stopped releaseNoise={} noiseReleased={} noiseReleaseDeferred={}", ENABLED, RELEASED.get(), DEFERRED.get()));
    }

    /** At the return of {@code ChunkStatusTasks.generateCarvers}: release now, or when an asynchronous result completes. */
    public static void afterCarvers(ChunkAccess chunk, CompletableFuture<ChunkAccess> result) {
        if (!ENABLED || !(chunk instanceof ProtoChunk) || chunk instanceof ImposterProtoChunk) return;
        if (result == null || result.isDone()) {
            release(chunk);
        } else {
            DEFERRED.incrementAndGet();
            result.whenComplete((ignored, failure) -> release(chunk));
        }
    }

    private static void release(ChunkAccess chunk) {
        ChunkAccessNoiseAccess access = (ChunkAccessNoiseAccess) chunk;
        if (access.phoenium$noiseChunk() != null) {
            access.phoenium$setNoiseChunk(null);
            RELEASED.incrementAndGet();
        }
    }

    public static long released() {
        return RELEASED.get();
    }
}
