package dev.phoenium.mixin;

import dev.phoenium.Phoenium;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Observes one base-server chunk future per world; it never changes or completes Minecraft's future. */
@Mixin(ServerChunkCache.class)
abstract class ServerChunkCacheMixin {
    @Shadow @Final private ServerLevel level;

    @Inject(method = "getChunkFuture(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Ljava/util/concurrent/CompletableFuture;",
        at = @At("RETURN"))
    private void phoenium$observeChunkFuture(int x, int z, ChunkStatus status, boolean create,
                                               CallbackInfoReturnable<CompletableFuture<ChunkResult<ChunkAccess>>> cir) {
        Phoenium.liveRuntime().observeChunkFuture(level, x, z, status, cir.getReturnValue());
    }
}
