package dev.phoenium.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.phoenium.chunk.FullFinishingQueries;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;

/** A typed LocationPredicate scope is mandatory; other cache calls keep their existing path. */
@Mixin(ServerChunkCache.class)
abstract class FullPredicateChunkMixin {
    @Shadow @Final private ServerLevel level;
    @WrapMethod(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;")
    private ChunkAccess phoenium$predicate(int x, int z, ChunkStatus status, boolean create, Operation<ChunkAccess> original) {
        return FullFinishingQueries.predicateChunk(level, x, z, status, create, () -> original.call(x, z, status, create));
    }
}
