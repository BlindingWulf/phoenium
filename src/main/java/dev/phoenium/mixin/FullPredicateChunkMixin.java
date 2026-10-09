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
        // S9-D4: with no query, diagnostic or portal observation open anywhere, predicateChunk reduces to
        // the original call plus its natural-spawn failure report; take that path without the observers.
        if (FullFinishingQueries.ordinaryChunkQuery()) {
            try { return original.call(x, z, status, create); }
            catch (RuntimeException | Error failure) {
                dev.phoenium.service.NaturalSpawnContinuation.failedChunkQuery(level, x, z, status, create, failure);
                throw failure;
            }
        }
        return FullFinishingQueries.predicateChunk(level, x, z, status, create, () -> original.call(x, z, status, create));
    }
}
