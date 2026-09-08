package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkTaskDispatcher;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.chunk.storage.SimpleRegionStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ChunkMap.class)
abstract class FailedStopChunkMapMixin {
    @WrapOperation(method = "close()V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ChunkTaskDispatcher;close()V"), require = 2, expect = 2)
    private void phoenium$closeDispatcher(ChunkTaskDispatcher dispatcher, Operation<Void> original) {
        FailedStopCleanup.run(() -> original.call(dispatcher));
    }

    @WrapOperation(method = "close()V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/ai/village/poi/PoiManager;close()V"), require = 1, expect = 1)
    private void phoenium$closePoi(PoiManager poi, Operation<Void> original) {
        FailedStopCleanup.run(() -> original.call(poi));
    }

    @WrapOperation(method = "close()V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/storage/SimpleRegionStorage;close()V"), require = 2, expect = 2)
    private void phoenium$closeStorage(SimpleRegionStorage storage, Operation<Void> original) {
        FailedStopCleanup.run(() -> original.call(storage));
    }
}
