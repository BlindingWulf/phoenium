package dev.phoenium.chunk;

import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.*;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import dev.phoenium.topology.RoutingKey;

/** Exact original BIOMES result. Later generation stages do not rewrite biome containers;
 * the pinned FillBiomeCommand writer participates in the serial service/region turn.
 * A generation borrow protects residency and save ordering, independently of the producer.
 */
public final class FullBiomeInputs {
    private FullBiomeInputs() { }
    public record Evidence(CompletableFuture<ChunkResult<ChunkAccess>> slot,
                           ChunkResult<ChunkAccess> result, ChunkAccess actual) { }
    static Evidence capture(GenerationChunkHolder holder) {
        var slot = ((FullHolderEvidence) holder).phoenium$futures().get(ChunkStatus.BIOMES.getIndex());
        if (slot == null || !slot.isDone() || slot.isCompletedExceptionally()) return null;
        var result = slot.getNow(null);
        return result == null || result.orElse(null) == null ? null : new Evidence(slot, result, result.orElse(null));
    }
    static boolean validates(GenerationChunkHolder holder, Evidence evidence) {
        if (evidence == null) return false;
        var current = capture(holder);
        return current != null && current.slot == evidence.slot && current.result == evidence.result && current.actual == evidence.actual;
    }
    static AutoCloseable retain(FullRegistrationRuntime runtime, FullInitializedInputs.Target target) {
        runtime.requireObservationThread();
        var holder = target.holder();
        if (!validates(holder, target.biomes()) || runtime.visible(target.position()) != holder) return null;
        var services = runtime.services;
        var lifecycle = services.lifecycles().activateHolder(runtime.authority.worldInstanceSequence(),
                new RoutingKey(runtime.authority.world(), target.position().x(), target.position().z()), holder);
        services.trackHolder(lifecycle, holder);
        runtime.custody.retainLifecycle(lifecycle);
        var use = new AutoCloseable() {
            boolean closed;
            @Override public void close() {
                if (closed) return;
                closed = true;
                try { holder.decreaseGenerationRefCount(); }
                finally {
                    runtime.custody.acknowledgeLifecycle(lifecycle);
                    services.tryReleaseRetiredLifecycle(lifecycle);
                }
            }
        };
        // The original increment commits before addSaveDependency can call out or fail.
        // Establish its acknowledgement first, just as for a published FULL consumer.
        try { holder.increaseGenerationRefCount(); }
        catch (Throwable failure) {
            try { use.close(); } catch (Throwable cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
            throw failure;
        }
        if (!validates(holder, target.biomes()) || runtime.visible(target.position()) != holder) {
            try { use.close(); } catch (Exception failure) { throw new ChunkProtocolException("biome input release", failure); }
            return null;
        }
        return use;
    }
}
