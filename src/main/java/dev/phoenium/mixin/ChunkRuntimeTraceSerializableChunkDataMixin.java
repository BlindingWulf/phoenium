package dev.phoenium.mixin;

import dev.phoenium.live.ChunkRuntimeTrace;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Test-only observation of the parsed-result read that can touch POI and lighting state. */
@Mixin(SerializableChunkData.class)
abstract class ChunkRuntimeTraceSerializableChunkDataMixin {
    @Inject(method = "read(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/ai/village/poi/PoiManager;Lnet/minecraft/world/level/chunk/storage/RegionStorageInfo;Lnet/minecraft/world/level/ChunkPos;)Lnet/minecraft/world/level/chunk/ProtoChunk;",
        at = @At("HEAD"), require = 1)
    private void phoenium$serializableReadStarted(ServerLevel level, PoiManager poiManager,
                                                  RegionStorageInfo storageInfo, ChunkPos pos,
                                                  CallbackInfoReturnable<ProtoChunk> cir) {
        ChunkRuntimeTrace.stage("SERIALIZABLE_DATA_READ_ENTER", level, pos,
            storageInfo == null ? "storageInfo=null" : storageInfo.toString(), this, null);
    }

    @Inject(method = "read(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/ai/village/poi/PoiManager;Lnet/minecraft/world/level/chunk/storage/RegionStorageInfo;Lnet/minecraft/world/level/ChunkPos;)Lnet/minecraft/world/level/chunk/ProtoChunk;",
        at = @At("RETURN"), require = 1)
    private void phoenium$serializableReadReturned(ServerLevel level, PoiManager poiManager,
                                                    RegionStorageInfo storageInfo, ChunkPos pos,
                                                    CallbackInfoReturnable<ProtoChunk> cir) {
        ChunkRuntimeTrace.stage("SERIALIZABLE_DATA_READ_RETURN", level, pos,
            storageInfo == null ? "storageInfo=null" : storageInfo.toString(), cir.getReturnValue(), null);
    }
}
