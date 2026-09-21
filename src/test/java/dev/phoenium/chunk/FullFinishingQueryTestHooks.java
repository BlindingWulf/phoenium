package dev.phoenium.chunk;

import java.lang.reflect.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.shapes.*;

/** Reflection merely reaches private original methods normally callable from their mixin. */
public final class FullFinishingQueryTestHooks {
    public static BlockState blockState(Level world, BlockPos position) {
        VaultContinuationTestHooks.event("read:" + position.toShortString());
        return BlockEntityChunkQueryDiagnostic.blockState(world, position, () -> (BlockState) invoke(
                Level.class, world, "phoenium$originalPredicate$getBlockState", new Class<?>[]{BlockPos.class}, position));
    }
    public static void blockEntityChanged(Level world, BlockPos position) {
        VaultContinuationTestHooks.blockEntityChanged(world, position);
    }
    public static boolean locationPredicate(net.minecraft.advancements.predicates.LocationPredicate predicate,
            ServerLevel world, double x, double y, double z) {
        return FullFinishingQueries.locationPredicate(world, x, y, z, () -> (boolean) invoke(
                net.minecraft.advancements.predicates.LocationPredicate.class, predicate, "phoenium$originalPredicate$matches",
                new Class<?>[]{ServerLevel.class, double.class, double.class, double.class}, world, x, y, z));
    }
    public static ChunkAccess predicateChunk(ServerChunkCache cache, int x, int z, ChunkStatus status, boolean create) {
        return FullFinishingQueries.predicateChunk((ServerLevel) cache.getLevel(), x, z, status, create,
                () -> (ChunkAccess) invoke(ServerChunkCache.class, cache, "phoenium$originalPredicate$getChunk",
                        new Class<?>[]{int.class, int.class, ChunkStatus.class, boolean.class}, x, z, status, create));
    }
    public static ChunkAccess difficulty(ServerLevel world, int x, int z, ChunkStatus status, boolean create) {
        return FullFinishingQueries.query(world, FullInitializedInputs.Purpose.DIFFICULTY, x, z, status, create,
                () -> world.getChunk(x, z, status, create));
    }
    public static ChunkAccess collision(Level world, int x, int z, ChunkStatus status, boolean create) {
        return FullFinishingQueries.query(world, FullInitializedInputs.Purpose.COLLISION, x, z, status, create,
                () -> world.getChunk(x, z, status, create));
    }
    public static ChunkAccess environment(LevelReader world, int x, int z, ChunkStatus status, boolean create) {
        return FullFinishingQueries.query(world, FullInitializedInputs.Purpose.ENVIRONMENT, x, z, status, create,
                () -> world.getChunk(x, z, status, create));
    }
    public static LevelChunk dynamicChunk(Level world, BlockPos position) {
        return FullFinishingQueries.dynamicChunk(world, position, () -> world.getChunkAt(position));
    }
    public static BlockEntity dynamicBlockEntity(LevelChunk chunk, BlockPos position, LevelChunk.EntityCreationType creation) {
        return FullFinishingQueries.dynamicBlockEntity(chunk, position, creation, () -> chunk.getBlockEntity(position, creation));
    }
    public static VoxelShape shape(CollisionContext context, BlockState state, CollisionGetter world, BlockPos position) {
        return FullFinishingQueries.collisionShape(state, world, position, () -> context.getCollisionShape(state, world, position));
    }
    @SuppressWarnings("unchecked")
    public static CompletableFuture<ChunkResult<ChunkAccess>> selected(ServerChunkCache cache,
            int x, int z, ChunkStatus status, boolean create) {
        var selected = (CompletableFuture<ChunkResult<ChunkAccess>>) invoke(ServerChunkCache.class, cache,
                "getChunkFutureMainThread", new Class<?>[]{int.class, int.class, ChunkStatus.class, boolean.class}, x, z, status, create);
        FullFinishingQueries.selected((ServerLevel) cache.getLevel(), x, z, status, create, selected);
        return selected;
    }
    public static void managedBlock(Object loop, BooleanSupplier ready) {
        if (FullFinishingQueries.usesPrivateResult()) return;
        ChunkSynchronousAccessGuard.beforeWait(ready);
        invoke(net.minecraft.util.thread.BlockableEventLoop.class, loop, "managedBlock", new Class<?>[]{BooleanSupplier.class}, ready);
    }
    public static Object join(CompletableFuture<?> future) {
        var result = FullFinishingQueries.privateJoin(future);
        if (result != null) return result;
        ChunkSynchronousAccessGuard.beforeWait(future::isDone);
        return future.join();
    }
    public static void store(ServerChunkCache cache, long position, ChunkAccess chunk, ChunkStatus status) {
        if (!FullFinishingQueries.usesPrivateResult())
            invoke(ServerChunkCache.class, cache, "storeInCache", new Class<?>[]{long.class, ChunkAccess.class, ChunkStatus.class}, position, chunk, status);
    }
    static Object invoke(Class<?> owner, Object receiver, String method, Class<?>[] parameters, Object... arguments) {
        try {
            Method original = owner.getDeclaredMethod(method, parameters); original.setAccessible(true);
            return original.invoke(receiver, arguments);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            if (failure.getCause() instanceof Error error) throw error;
            throw new AssertionError(failure.getCause());
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
}
