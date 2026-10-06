package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.phoenium.ownership.OwnershipContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Pinned 26.2: setChunkForced adds the forced ticket, then loads the chunk synchronously and discards it. An owner
 * turn cannot wait on chunk loading, so the ticket alone loads the chunk; owner-free callers keep the native load.
 */
@Mixin(ServerLevel.class)
abstract class ForcedChunkOwnerLoadMixin {
    @WrapOperation(method = "setChunkForced", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/level/ServerLevel;getChunk(II)Lnet/minecraft/world/level/chunk/LevelChunk;"),
        require = 1, expect = 1)
    private LevelChunk phoenium$ownerForceLoad(ServerLevel level, int x, int z, Operation<LevelChunk> original) {
        return OwnershipContext.current().isPresent() ? null : original.call(level, x, z);
    }
}
