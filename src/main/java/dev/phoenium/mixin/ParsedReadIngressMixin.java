package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.phoenium.chunk.LightInputService;
import dev.phoenium.chunk.PrivateC2meReadInvocation;
import dev.phoenium.chunk.ScalableLuxLightBridge;
import dev.phoenium.chunk.SynchronousChunkInvocation;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(SerializableChunkData.class)
abstract class ParsedReadIngressMixin {
    @WrapMethod(method = "read(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/ai/village/poi/PoiManager;Lnet/minecraft/world/level/chunk/storage/RegionStorageInfo;Lnet/minecraft/world/level/ChunkPos;)Lnet/minecraft/world/level/chunk/ProtoChunk;")
    private ProtoChunk phoenium$read(ServerLevel level,PoiManager poi,RegionStorageInfo storage,ChunkPos position,Operation<ProtoChunk> original) {
        var privateRead = PrivateC2meReadInvocation.current(position);
        if (privateRead != null) privateRead.requireRead((SerializableChunkData) (Object) this,level);
        else SynchronousChunkInvocation.require(position).requireRead((SerializableChunkData) (Object) this,level);
        ProtoChunk result = original.call(level,poi,storage,position);
        ScalableLuxLightBridge.requireReloadState(level,result);
        if (privateRead != null) privateRead.readReturned();
        else SynchronousChunkInvocation.require(position).readReturned();
        return result;
    }
    @WrapOperation(method = "read",at = @At(value = "INVOKE",target = "Lnet/minecraft/world/level/lighting/LevelLightEngine;retainData(Lnet/minecraft/world/level/ChunkPos;Z)V"),require = 1,expect = 1)
    private void phoenium$retain(LevelLightEngine engine,ChunkPos position,boolean retain,Operation<Void> original) throws Throwable {
        var privateRead = PrivateC2meReadInvocation.current(position);
        if (privateRead != null) privateRead.ingress(LightInputService.Kind.RETAIN,position,null,
            insertion -> ScalableLuxLightBridge.submit(engine,insertion,() -> original.call(engine,position,retain)));
        else SynchronousChunkInvocation.require(position).ingress(LightInputService.Kind.RETAIN,position,null,
            insertion -> ScalableLuxLightBridge.submit(engine,insertion,() -> original.call(engine,position,retain)));
    }
    @WrapOperation(method = "read",at = @At(value = "INVOKE",target = "Lnet/minecraft/world/level/lighting/LevelLightEngine;queueSectionData(Lnet/minecraft/world/level/LightLayer;Lnet/minecraft/core/SectionPos;Lnet/minecraft/world/level/chunk/DataLayer;)V"),require = 2,expect = 2)
    private void phoenium$section(LevelLightEngine engine,LightLayer layer,SectionPos section,DataLayer data,Operation<Void> original,
            @Local(argsOnly = true) ChunkPos position) throws Throwable {
        LightInputService.Kind kind = layer == LightLayer.BLOCK ? LightInputService.Kind.BLOCK : LightInputService.Kind.SKY;
        var privateRead = PrivateC2meReadInvocation.current(position);
        if (privateRead != null) privateRead.ingress(kind,section,data,
            insertion -> ScalableLuxLightBridge.submit(engine,insertion,() -> original.call(engine,layer,section,data)));
        else SynchronousChunkInvocation.require(position).ingress(kind,section,data,
            insertion -> ScalableLuxLightBridge.submit(engine,insertion,() -> original.call(engine,layer,section,data)));
    }
}
