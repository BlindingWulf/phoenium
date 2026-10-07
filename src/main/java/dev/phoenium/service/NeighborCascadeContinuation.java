package dev.phoenium.service;

import dev.phoenium.topology.RoutingKey;
import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;

/** S8-F6 red: the API seam only. Every hook is the native passthrough, so an owned cascade that
 * reaches an unfinished chunk still fails at the synchronous chunk guard. */
public final class NeighborCascadeContinuation implements AutoCloseable {
    public interface Port {
        Object phoenium$cascadeLevel();
        ArrayDeque<Object> phoenium$cascadeStack();
        List<Object> phoenium$cascadeLayer();
        int phoenium$cascadeCount();
        void phoenium$cascadeCount(int count);
        int phoenium$cascadeMaxChained();
        void phoenium$cascadeRunUpdates();
    }
    public interface Environment {
        BlockPos startRead(Object step);
        BlockPos dispatchTarget(Object step);
        Object snapshot(Object step);
        void restore(Object step, Object snapshot);
        boolean suspendable(Object level);
        boolean readable(Object level, BlockPos position);
        ChunkWait await(Object level, BlockPos position);
    }
    public interface ChunkWait extends AutoCloseable {
        ManagedBlockPollTurnFence fence();
        void managedBlock(BooleanSupplier ready);
        CompletionStage<?> dependency();
        void prepare();
        boolean current();
        @Override void close();
    }
    private static final Set<RegionalServiceKind> KINDS = EnumSet.of(RegionalServiceKind.RUN_SCHEDULED_BLOCK_TICK,
        RegionalServiceKind.RUN_SCHEDULED_FLUID_TICK, RegionalServiceKind.BLOCK_EVENT);
    private NeighborCascadeContinuation() { }
    public static boolean armable(RegionalServiceKind kind) { return KINDS.contains(kind); }
    static NeighborCascadeContinuation arm(Environment environment, RoutingKey key, RegionalServiceKind kind) { return null; }
    RegionalServiceDispatcher.RegionalServiceWork wrap(RegionalServiceDispatcher.RegionalServiceWork body) { return body; }
    void finish(RegionalServiceDispatcher dispatcher) { }
    public static boolean runNext(Port updater, Object step, BooleanSupplier original) { return original.getAsBoolean(); }
    public static boolean deferRoot(Port updater, BlockPos position, Object update) { return false; }
    public static void neighborChanged(Object level, BlockPos position, Runnable original) { original.run(); }
    public static boolean spreadCheck(BooleanSupplier original) { return original.getAsBoolean(); }
    public static void deepRead(Object level, BlockPos position) { }
    public static void beforeMutation() { }
    @Override public void close() { }
}
