package dev.phoenium.engine.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.phoenium.engine.EngineConfig;
import dev.phoenium.engine.EngineSpawnMemo;
import dev.phoenium.engine.TileContext;
import dev.phoenium.engine.TileEngine;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LocalMobCapCalculator;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * S9-E1 (engine mode only): chunk lookups from a tile thread read loaded chunks only (vanilla blocks on, or returns
 * null for, the server thread); broadcast marks and ticket changes from a tile are deferred to the server thread. The
 * lookups wrap the whole method, so C2ME's and Lithium's own off-thread paths inside it are not reached from a tile.
 *
 * <p>S9-E3: tickChunks' loops still call tickSpawningChunk (after ServerCore's cached list and loaded-chunk condition)
 * and ServerLevel.tickChunk; from the method head until the run, each call is recorded in its chunk's tile instead,
 * and the tiles run the recorded calls before the custom spawners (or at the end when mob spawning is off).
 *
 * <p>S9-E7: the NaturalSpawner.createState call at the head of tickChunks runs with EngineSpawnMemo as its chunk getter
 * and LocalMobCapCalculator (no other mod of the stack hooks createState, its lambda or addMob).
 */
@Mixin(value = ServerChunkCache.class, priority = 2000)
abstract class EngineChunkCacheMixin {
    @Unique
    private final EngineSpawnMemo.Level phoenium$spawnMemo = new EngineSpawnMemo.Level();

    @Inject(method = "tickChunks(Lnet/minecraft/util/profiling/ProfilerFiller;J)V", at = @At("HEAD"))
    private void phoenium$recordChunks(ProfilerFiller profiler, long timeDiff, CallbackInfo ci) {
        TileEngine.beginChunks((ServerChunkCache) (Object) this);
    }

    /** S9-E7: the tick's spawn state with each chunk looked up once (EngineSpawnMemo). */
    @WrapOperation(method = "tickChunks(Lnet/minecraft/util/profiling/ProfilerFiller;J)V", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/NaturalSpawner;createState(ILjava/lang/Iterable;Lnet/minecraft/world/level/NaturalSpawner$ChunkGetter;Lnet/minecraft/world/level/LocalMobCapCalculator;)Lnet/minecraft/world/level/NaturalSpawner$SpawnState;"))
    private NaturalSpawner.SpawnState phoenium$spawnState(int spawnableChunks, Iterable<Entity> entities, NaturalSpawner.ChunkGetter getter,
                                                         LocalMobCapCalculator caps, Operation<NaturalSpawner.SpawnState> original) {
        if (!EngineConfig.SPAWN_MEMO) return original.call(spawnableChunks, entities, getter, caps);
        return EngineSpawnMemo.createState(this.phoenium$spawnMemo, spawnableChunks, entities, getter, caps, original);
    }

    @WrapMethod(method = "tickSpawningChunk")
    private void phoenium$recordSpawningChunk(LevelChunk chunk, long timeDiff, List<MobCategory> spawningCategories,
                                              NaturalSpawner.SpawnState spawnCookie, Operation<Void> original) {
        if (!TileEngine.recordSpawnChunk((ServerChunkCache) (Object) this, chunk, timeDiff, spawningCategories, spawnCookie))
            original.call(chunk, timeDiff, spawningCategories, spawnCookie);
    }

    @Inject(method = "tickChunks(Lnet/minecraft/util/profiling/ProfilerFiller;J)V", require = 0,
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;tickCustomSpawners(Z)V"))
    private void phoenium$runChunksBeforeCustomSpawners(ProfilerFiller profiler, long timeDiff, CallbackInfo ci) {
        TileEngine.runChunks((ServerChunkCache) (Object) this);
    }

    @Inject(method = "tickChunks(Lnet/minecraft/util/profiling/ProfilerFiller;J)V", at = @At("RETURN"))
    private void phoenium$runChunks(ProfilerFiller profiler, long timeDiff, CallbackInfo ci) {
        TileEngine.runChunks((ServerChunkCache) (Object) this);
    }

    @WrapMethod(method = "getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;")
    private ChunkAccess phoenium$tileChunk(int x, int z, ChunkStatus status, boolean load, Operation<ChunkAccess> original) {
        TileContext context = TileEngine.current();
        if (context == null) return original.call(x, z, status, load);
        return context.chunk((ServerChunkCache) (Object) this, x, z, status, load);
    }

    @WrapMethod(method = "getChunkNow")
    private LevelChunk phoenium$tileChunkNow(int x, int z, Operation<LevelChunk> original) {
        TileContext context = TileEngine.current();
        if (context == null) return original.call(x, z);
        return context.fullChunk((ServerChunkCache) (Object) this, x, z);
    }

    @Inject(method = "blockChanged", at = @At("HEAD"), cancellable = true)
    private void phoenium$deferBlockChanged(BlockPos pos, CallbackInfo ci) {
        TileContext context = TileEngine.current();
        if (context == null) return;
        ServerChunkCache cache = (ServerChunkCache) (Object) this;
        BlockPos at = pos.immutable();
        context.defer(() -> cache.blockChanged(at));
        ci.cancel();
    }

    @Inject(method = "addTicket", at = @At("HEAD"), cancellable = true)
    private void phoenium$deferTicket(Ticket ticket, ChunkPos pos, CallbackInfo ci) {
        TileContext context = TileEngine.current();
        if (context == null) return;
        ServerChunkCache cache = (ServerChunkCache) (Object) this;
        context.defer(() -> cache.addTicket(ticket, pos));
        ci.cancel();
    }

    @Inject(method = "addTicketWithRadius", at = @At("HEAD"), cancellable = true)
    private void phoenium$deferTicketWithRadius(TicketType type, ChunkPos pos, int radius, CallbackInfo ci) {
        TileContext context = TileEngine.current();
        if (context == null) return;
        ServerChunkCache cache = (ServerChunkCache) (Object) this;
        context.defer(() -> cache.addTicketWithRadius(type, pos, radius));
        ci.cancel();
    }

    @Inject(method = "removeTicketWithRadius", at = @At("HEAD"), cancellable = true)
    private void phoenium$deferTicketRemoval(TicketType type, ChunkPos pos, int radius, CallbackInfo ci) {
        TileContext context = TileEngine.current();
        if (context == null) return;
        ServerChunkCache cache = (ServerChunkCache) (Object) this;
        context.defer(() -> cache.removeTicketWithRadius(type, pos, radius));
        ci.cancel();
    }

    @Inject(method = "addTicketAndLoadWithRadius", at = @At("HEAD"), cancellable = true)
    private void phoenium$deferTicketAndLoad(TicketType type, ChunkPos pos, int radius, CallbackInfoReturnable<CompletableFuture<?>> cir) {
        TileContext context = TileEngine.current();
        if (context == null) return;
        ServerChunkCache cache = (ServerChunkCache) (Object) this;
        CompletableFuture<Object> loaded = new CompletableFuture<>();
        context.defer(() -> cache.addTicketAndLoadWithRadius(type, pos, radius).whenComplete((value, failure) -> {
            if (failure != null) loaded.completeExceptionally(failure);
            else loaded.complete(value);
        }));
        cir.setReturnValue(loaded);
    }
}
