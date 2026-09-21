package dev.phoenium.service;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** Only Level.updateNeighbourForOutputSignal, pinned to 26.2. The PC advances after
 * each committed native action. In particular SECOND_READ retains the conductor
 * decision; it never rereads/reexecutes the immediate branch on resumption. */
public final class OutputNeighborSignalContinuation {
    public enum Pc { DIRECTION, IMMEDIATE_READ, SECOND_READ, DONE }
    private static final Direction[] HORIZONTAL = Direction.Plane.HORIZONTAL.stream().toArray(Direction[]::new);
    private final ServerLevel level;
    private final BlockPos source;
    private final Block block;
    private int direction;
    private BlockPos query;
    private Pc pc = Pc.DIRECTION;

    OutputNeighborSignalContinuation(ServerLevel level, BlockPos source, Block block) {
        this.level = level; this.source = source.immutable(); this.block = block;
    }
    public Pc pc() { return pc; }
    public int direction() { return direction; }
    public BlockPos query() { return query; }

    boolean run(VaultBlockEntityTickContinuation tick) {
        while (pc != Pc.DONE) switch (pc) {
            case DIRECTION -> {
                if (direction == HORIZONTAL.length) { pc = Pc.DONE; continue; }
                query = source.relative(HORIZONTAL[direction]);
                if (!level.hasChunkAt(query)) { direction++; continue; }
                pc = Pc.IMMEDIATE_READ;
            }
            case IMMEDIATE_READ -> {
                if (!tick.readReady(query)) return false;
                var state = level.getBlockState(query);
                if (state.is(Blocks.COMPARATOR)) {
                    level.neighborChanged(state, query, block, null, false);
                } else if (state.isRedstoneConductor(level, query)) {
                    query = query.relative(HORIZONTAL[direction]); pc = Pc.SECOND_READ; continue;
                }
                direction++; pc = Pc.DIRECTION;
            }
            case SECOND_READ -> {
                if (!tick.readReady(query)) return false;
                var state = level.getBlockState(query);
                if (state.is(Blocks.COMPARATOR)) level.neighborChanged(state, query, block, null, false);
                direction++; pc = Pc.DIRECTION;
            }
            default -> throw new IllegalStateException("invalid output-neighbor PC " + pc);
        }
        return true;
    }
}
