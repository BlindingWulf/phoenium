package dev.phoenium.chunk;

import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import net.minecraft.server.level.*;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStep;

/** Test-agent equivalents of only the installed Mixin call-site plumbing. */
public final class FullRegistrationTestHooks {
    public static boolean actualUnload(it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<?> pending, long position, Object holder) {
        boolean removed = pending.remove(position, holder);
        if (removed) ChunkHolderLifetime.retired((GenerationChunkHolder) holder);
        return removed;
    }
    @SuppressWarnings("unchecked") public static boolean completeSlot(CompletableFuture<ChunkResult<ChunkAccess>> slot, Object value,
            GenerationChunkHolder holder, net.minecraft.world.level.chunk.status.ChunkStatus status) {
        var result = (ChunkResult<ChunkAccess>) value;
        FullPublishedInputs.proposing(holder, status, slot, result);
        boolean won = slot.complete(result); FullPublishedInputs.completed(holder, status, won); return won;
    }
    @SuppressWarnings("unchecked") public static boolean installSlot(java.util.concurrent.atomic.AtomicReferenceArray<Object> slots, int index, Object previous, Object value,
            GenerationChunkHolder holder, net.minecraft.world.level.chunk.status.ChunkStatus status) {
        var slot = (CompletableFuture<ChunkResult<ChunkAccess>>) value;
        FullPublishedInputs.proposing(holder, status, slot, slot.getNow(null));
        boolean won = slots.compareAndSet(index, previous, value); FullPublishedInputs.completed(holder, status, won); return won;
    }
    public static void invalidating(GenerationChunkHolder holder, int index, CompletableFuture<?> future) {
        if (index == net.minecraft.world.level.chunk.status.ChunkStatus.FULL.getIndex() && !future.isDone()) FullPublishedInputs.invalidating(holder);
    }
    public static void visiblePublished(boolean changed, ChunkMap map) { if (changed) ((FullInputView) map).phoenium$inputChanges().published(); }

    @SuppressWarnings("unchecked") public static java.util.List<net.minecraft.nbt.CompoundTag> savedEntities(net.minecraft.world.level.chunk.ProtoChunk proto) {
        try {
            var original = (java.util.List<net.minecraft.nbt.CompoundTag>) proto.getClass().getMethod("phoenium$originalGetEntities").invoke(proto);
            if (!((FullSavedInput.Access) proto).phoenium$savedInput().tracking()) return original;
            var guarded = FullSavedCollections.roots(original, ((FullSavedInput.Access) proto).phoenium$savedInput());
            FullDecisionWitnessSupport.set(proto, net.minecraft.world.level.chunk.ProtoChunk.class, "entities", guarded);
            return guarded;
        } catch (Exception failure) { throw new AssertionError(failure); }
    }
    public static void savedAdd(net.minecraft.world.level.chunk.ProtoChunk proto, net.minecraft.nbt.CompoundTag tag) {
        FullSavedInput input = ((FullSavedInput.Access) proto).phoenium$savedInput();
        Runnable notify = null; Throwable failure = null;
        input.beforeWrite();
        try {
            try { proto.getClass().getMethod("phoenium$originalAddEntity", net.minecraft.nbt.CompoundTag.class).invoke(proto, tag); }
            catch (ReflectiveOperationException reflectionFailure) { throw new AssertionError(reflectionFailure); }
            notify = input.written(proto.getEntities());
        } catch (Throwable problem) { failure = problem; }
        Throwable wakeFailure = input.endWrite();
        if (failure == null && notify != null) {
            try { notify.run(); }
            catch (Throwable problem) { failure = problem; }
        }
        if (failure == null) failure = wakeFailure;
        else if (wakeFailure != null && wakeFailure != failure) failure.addSuppressed(wakeFailure);
        FullSavedInput.rethrow(failure, "saved ProtoChunk entity publication failed");
    }
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
