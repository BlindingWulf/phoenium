package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.phoenium.Phoenium;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

/** Binds C2ME's synchronous fallback DISK parse to the exact Phoenium reload transaction. */
@Pseudo
@Mixin(targets = "com.ishland.c2me.rewrites.chunksystem.common.statuses.ReadFromDisk", remap = false)
abstract class C2meReadFromDiskMixin {
    private static final String READ = "Lnet/minecraft/world/level/chunk/storage/SerializableChunkData;read("
            + "Lnet/minecraft/server/level/ServerLevel;"
            + "Lnet/minecraft/world/entity/ai/village/poi/PoiManager;"
            + "Lnet/minecraft/world/level/chunk/storage/RegionStorageInfo;"
            + "Lnet/minecraft/world/level/ChunkPos;)Lnet/minecraft/world/level/chunk/ProtoChunk;";

    @WrapOperation(method = "lambda$invokeVanillaLoad$0",
            at = @At(value = "INVOKE", target = READ, remap = true), require = 1, expect = 1)
    private ProtoChunk phoenium$diskRead(SerializableChunkData data, ServerLevel level, PoiManager poi,
                                         RegionStorageInfo storage, ChunkPos position, Operation<ProtoChunk> original,
                                         @Coerce Object context) {
        return Phoenium.liveRuntime().chunkBinding().c2meRead(context, data, level, position,
                () -> original.call(data, level, poi, storage, position));
    }
}
