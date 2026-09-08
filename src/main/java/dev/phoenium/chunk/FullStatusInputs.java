package dev.phoenium.chunk;

import dev.phoenium.topology.RoutingKey;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** A settled original lower-status slot. Retention protects residency and save ordering;
 * mutable structure data additionally requires an exact FullStructureMutationTurns.Read. */
final class FullStatusInputs {
    record Evidence(ChunkStatus status, CompletableFuture<ChunkResult<ChunkAccess>> slot,
                    ChunkResult<ChunkAccess> result, ChunkAccess actual) { }
    private FullStatusInputs() { }
    static Evidence capture(GenerationChunkHolder holder, ChunkStatus status) {
        var slot = ((FullHolderEvidence) holder).phoenium$futures().get(status.getIndex());
        if (slot == null || !slot.isDone() || slot.isCompletedExceptionally()) return null;
        var result = slot.getNow(null);
        return result == null || result.orElse(null) == null ? null : new Evidence(status, slot, result, result.orElse(null));
    }
    static boolean validates(GenerationChunkHolder holder, Evidence expected) {
        if (holder == null || expected == null) return false;
        var current = capture(holder, expected.status);
        return current != null && current.slot == expected.slot && current.result == expected.result
                && current.actual == expected.actual;
    }
    static AutoCloseable retain(FullRegistrationRuntime runtime, FullInitializedInputs.Target target) {
        runtime.requireObservationThread();
        var holder = target.holder();
        var evidence = target.structureReferences() != null ? target.structureReferences() : target.structureStarts();
        if (!validates(holder, evidence) || runtime.visible(target.position()) != holder) return null;
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
                var failures = new FullInputNotifications.Failures();
                failures.run(holder::decreaseGenerationRefCount);
                failures.run(() -> runtime.custody.acknowledgeLifecycle(lifecycle));
                failures.run(() -> services.tryReleaseRetiredLifecycle(lifecycle));
                failures.rethrow();
            }
        };
        // The pinned increment commits before its addSaveDependency callback can fail.
        try { holder.increaseGenerationRefCount(); }
        catch (Throwable failure) {
            try { use.close(); } catch (Throwable cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
            throw failure;
        }
        if (!validates(holder, evidence) || runtime.visible(target.position()) != holder) {
            try { use.close(); } catch (Exception failure) { throw new ChunkProtocolException("status input release", failure); }
            return null;
        }
        return use;
    }
}
