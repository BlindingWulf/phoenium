package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.phoenium.chunk.SynchronousChunkInvocation;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ChunkMap.class)
abstract class ClaimedChunkMetadataMixin {
    @WrapOperation(method = {"markPosition(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/world/level/chunk/status/ChunkType;)B",
            "markPositionReplaceable(Lnet/minecraft/world/level/ChunkPos;)V"},
        at = @At(value = "INVOKE",target = "Lit/unimi/dsi/fastutil/longs/Long2ByteMap;put(JB)B"),require = 2,expect = 2)
    private byte phoenium$put(Long2ByteMap cache,long position,byte value,Operation<Byte> original,
            @Local(argsOnly = true) ChunkPos argument) {
        if (!SynchronousChunkInvocation.bound(argument)) return original.call(cache,position,value);
        var invocation = SynchronousChunkInvocation.require(argument);
        var claim = invocation.claim();
        return invocation.services().metadata().putClaimed(claim,claim.stage().work().lifecycle().chunkKey(),value,
                () -> original.call(cache,position,value));
    }
}
