package dev.phoenium.adversestop.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.phoenium.adversestop.AdverseStopProbe;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(IOWorker.class)
abstract class AdverseIOWorkerMixin {
    @WrapOperation(method = "runStore", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/chunk/storage/RegionFileStorage;write(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/nbt/CompoundTag;)V"),
            require = 1, expect = 1)
    private void phoenium$gateExactNativeWrite(RegionFileStorage storage, ChunkPos pos, CompoundTag data,
                                                Operation<Void> original) {
        boolean selected = AdverseStopProbe.beginNativeWrite((IOWorker) (Object) this, pos);
        try {
            original.call(storage, pos, data);
            if (selected) AdverseStopProbe.nativeWriteComplete();
        } catch (Throwable failure) {
            if (selected) AdverseStopProbe.nativeWriteFailed(failure);
            throw failure;
        }
    }
}
