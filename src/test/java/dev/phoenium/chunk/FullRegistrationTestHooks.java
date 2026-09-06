package dev.phoenium.chunk;

import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import net.minecraft.server.level.*;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStep;

/** Test-agent equivalents of only the installed Mixin call-site plumbing. */
public final class FullRegistrationTestHooks {
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
        Runnable notify;
        input.beforeWrite();
        try {
            try { proto.getClass().getMethod("phoenium$originalAddEntity", net.minecraft.nbt.CompoundTag.class).invoke(proto, tag); }
            catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            notify = input.written(proto.getEntities());
        } finally { input.endWrite(); }
        if (notify != null) notify.run();
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
