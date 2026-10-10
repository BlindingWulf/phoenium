package dev.phoenium.engine;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.redstone.CollectingNeighborUpdater;
import net.minecraft.world.level.redstone.Orientation;
import org.jspecify.annotations.Nullable;

/**
 * S9-E1: the value of {@code Level.neighborUpdater} in engine mode. A thread running a tile collects and runs its
 * neighbour updates on its own {@link CollectingNeighborUpdater} (vanilla's keeps one stack per level); every other
 * call runs on this updater, which is the level's own as before.
 */
public final class EngineNeighborUpdater extends CollectingNeighborUpdater {
    private final Level owner;
    private final int maxChainedNeighborUpdates;

    public EngineNeighborUpdater(Level owner, int maxChainedNeighborUpdates) {
        super(owner, maxChainedNeighborUpdates);
        this.owner = owner;
        this.maxChainedNeighborUpdates = maxChainedNeighborUpdates;
    }

    private CollectingNeighborUpdater tileUpdater() {
        TileContext context = TileEngine.current();
        return context == null ? null : context.neighborUpdater(owner, maxChainedNeighborUpdates);
    }

    @Override
    public void shapeUpdate(Direction direction, BlockState neighborState, BlockPos pos, BlockPos neighborPos,
                            int updateFlags, int updateLimit) {
        CollectingNeighborUpdater tile = tileUpdater();
        if (tile != null) tile.shapeUpdate(direction, neighborState, pos, neighborPos, updateFlags, updateLimit);
        else super.shapeUpdate(direction, neighborState, pos, neighborPos, updateFlags, updateLimit);
    }

    @Override
    public void neighborChanged(BlockPos pos, Block block, @Nullable Orientation orientation) {
        CollectingNeighborUpdater tile = tileUpdater();
        if (tile != null) tile.neighborChanged(pos, block, orientation);
        else super.neighborChanged(pos, block, orientation);
    }

    @Override
    public void neighborChanged(BlockState state, BlockPos pos, Block block, @Nullable Orientation orientation,
                                boolean movedByPiston) {
        CollectingNeighborUpdater tile = tileUpdater();
        if (tile != null) tile.neighborChanged(state, pos, block, orientation, movedByPiston);
        else super.neighborChanged(state, pos, block, orientation, movedByPiston);
    }

    @Override
    public void updateNeighborsAtExceptFromFacing(BlockPos pos, Block block, @Nullable Direction skipDirection,
                                                  @Nullable Orientation orientation) {
        CollectingNeighborUpdater tile = tileUpdater();
        if (tile != null) tile.updateNeighborsAtExceptFromFacing(pos, block, skipDirection, orientation);
        else super.updateNeighborsAtExceptFromFacing(pos, block, skipDirection, orientation);
    }
}
