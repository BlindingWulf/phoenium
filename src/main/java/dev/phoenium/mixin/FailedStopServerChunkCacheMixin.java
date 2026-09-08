package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.phoenium.chunk.FullServerShutdown;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ServerChunkCache.class)
abstract class FailedStopServerChunkCacheMixin {
    private static final String CLOSE = "close()V";

    @WrapOperation(method = CLOSE, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerChunkCache;save(Z)V"), require = 1, expect = 1)
    private void phoenium$save(ServerChunkCache cache, boolean flush, Operation<Void> original) {
        if (!FullServerShutdown.failedStop()) original.call(cache, flush);
    }

    @WrapOperation(method = CLOSE, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/storage/SavedDataStorage;close()V"), require = 1, expect = 1)
    private void phoenium$closeData(SavedDataStorage storage, Operation<Void> original) {
        FailedStopCleanup.run(() -> original.call(storage));
    }

    @WrapOperation(method = CLOSE, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ThreadedLevelLightEngine;close()V"), require = 1, expect = 1)
    private void phoenium$closeLight(ThreadedLevelLightEngine light, Operation<Void> original) {
        FailedStopCleanup.run(() -> original.call(light));
    }

    @WrapOperation(method = CLOSE, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ChunkMap;close()V"), require = 1, expect = 1)
    private void phoenium$closeMap(ChunkMap chunks, Operation<Void> original) {
        FailedStopCleanup.run(() -> original.call(chunks));
    }
}
