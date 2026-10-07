package dev.phoenium.service;

import dev.phoenium.chunk.ChunkProtocolException;
import dev.phoenium.mixin.ChunkMapLoadAccess;
import dev.phoenium.mixin.NeighborCascadeFullAccess;
import dev.phoenium.mixin.NeighborCascadeMultiAccess;
import dev.phoenium.mixin.NeighborCascadeShapeAccess;
import dev.phoenium.mixin.NeighborCascadeSimpleAccess;
import dev.phoenium.mixin.RegionalServerChunkCacheAccess;
import dev.phoenium.ownership.OwnershipContext;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.redstone.NeighborUpdater;
import net.minecraft.world.level.redstone.Orientation;

/** Native 26.2 step shapes (pinned by {@link NeighborCascadePin}) and the owner-free FULL wait. */
public final class LiveNeighborCascadeEnvironment implements NeighborCascadeContinuation.Environment {
    private record MultiCursor(int idx, Orientation orientation) { }

    /** Multi and Simple read their target first; Shape's executeShapeUpdate reads pos at bci 2;
     * Full carries its state and reads nothing before dispatch. */
    @Override public BlockPos startRead(Object step) {
        if (step instanceof NeighborCascadeMultiAccess multi) return multiTarget(multi);
        if (step instanceof NeighborCascadeSimpleAccess simple) return simple.phoenium$pos();
        if (step instanceof NeighborCascadeShapeAccess shape) return shape.phoenium$pos();
        return null;
    }
    @Override public BlockPos dispatchTarget(Object step) {
        if (step instanceof NeighborCascadeMultiAccess multi) return multiTarget(multi);
        if (step instanceof NeighborCascadeSimpleAccess simple) return simple.phoenium$pos();
        if (step instanceof NeighborCascadeFullAccess full) return full.phoenium$pos();
        return null; // a shape update never dispatches neighborChanged
    }
    private static BlockPos multiTarget(NeighborCascadeMultiAccess multi) {
        int idx = multi.phoenium$idx();
        var order = NeighborUpdater.UPDATE_ORDER;
        return idx >= 0 && idx < order.length ? multi.phoenium$sourcePos().relative(order[idx]) : null;
    }
    @Override public Object snapshot(Object step) {
        return step instanceof NeighborCascadeMultiAccess multi ? new MultiCursor(multi.phoenium$idx(), multi.phoenium$orientation()) : null;
    }
    @Override public void restore(Object step, Object snapshot) {
        if (step instanceof NeighborCascadeMultiAccess multi && snapshot instanceof MultiCursor cursor) {
            multi.phoenium$idx(cursor.idx()); multi.phoenium$orientation(cursor.orientation());
        }
    }
    @Override public boolean suspendable(Object level) {
        return level instanceof ServerLevel server && server.getServer().isSameThread();
    }
    /** Native Level getters return VOID_AIR/EMPTY outside the build height without a chunk, and
     * the main-thread getter returns a published FULL chunk without managedBlock. */
    @Override public boolean readable(Object level, BlockPos position) {
        var server = (ServerLevel) level;
        return server.isOutsideBuildHeight(position)
            || server.getChunkSource().getChunkNow(position.getX() >> 4, position.getZ() >> 4) != null;
    }
    @Override public NeighborCascadeContinuation.ChunkWait await(Object level, BlockPos position) {
        return new Wait((ServerLevel) level, position.getX() >> 4, position.getZ() >> 4);
    }

    private static final class Wait implements NeighborCascadeContinuation.ChunkWait {
        private final ServerLevel level;
        private final int x, z;
        private final BlockableEventLoop<Runnable> loop;
        private final CompletableFuture<Void> ready = new CompletableFuture<>();
        private CompletableFuture<ChunkResult<ChunkAccess>> selected;
        private boolean closed;
        Wait(ServerLevel level, int x, int z) {
            this.level = level; this.x = x; this.z = z;
            loop = ((ChunkMapLoadAccess) level.getChunkSource().chunkMap).phoenium$mainExecutor();
        }
        @Override public ManagedBlockPollTurnFence fence() { return ((ManagedBlockPollTurnFence.Loop) loop).phoenium$naturalSpawnFence(); }
        @Override public void managedBlock(BooleanSupplier supplier) { loop.managedBlock(supplier); }
        @Override public CompletionStage<?> dependency() { return ready; }
        @Override public boolean current() { return !closed; }
        /** The native FULL/create=true selection, once, after the owner returned. */
        @Override public void prepare() {
            if (OwnershipContext.current().isPresent()) throw new IllegalStateException("neighbor cascade chunk selected under owner");
            if (closed || ready.isDone()) return;
            try {
                if (level.getChunkSource().getChunkNow(x, z) != null) { ready.complete(null); return; }
                if (selected == null)
                    selected = ((RegionalServerChunkCacheAccess) level.getChunkSource()).phoenium$naturalSpawnFuture(x, z, ChunkStatus.FULL, true);
                if (!selected.isDone()) return;
                var result = selected.join();
                // Readiness is re-observed by the resumed step itself; the continuation bounds repeats.
                if (result.isSuccess()) ready.complete(null);
                else ready.completeExceptionally(new ChunkProtocolException("neighbor cascade FULL dependency not there when requested: "
                    + level.dimension() + " " + x + "," + z + " " + result.getError()));
            } catch (CompletionException failure) {
                ready.completeExceptionally(failure.getCause() == null ? failure : failure.getCause());
            } catch (RuntimeException | Error failure) { ready.completeExceptionally(failure); }
        }
        @Override public void close() { closed = true; selected = null; }
    }
}
