package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.phoenium.save.RetainedChunkSnapshot;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The original snapshot is produced once, then its remaining saved-tag aliases are
 * detached before the original background encoder can receive the data. */
@Mixin(ChunkMap.class)
abstract class RegionalChunkSaveMixin {
    @WrapOperation(method = "save(Lnet/minecraft/world/level/chunk/ChunkAccess;)Z",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/storage/SerializableChunkData;copyOf(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ChunkAccess;)Lnet/minecraft/world/level/chunk/storage/SerializableChunkData;"),
            require = 1, expect = 1)
    private SerializableChunkData phoenium$retainSnapshot(ServerLevel level, ChunkAccess chunk,
                                                         Operation<SerializableChunkData> original) {
        return RetainedChunkSnapshot.detach(original.call(level, chunk));
    }
}
