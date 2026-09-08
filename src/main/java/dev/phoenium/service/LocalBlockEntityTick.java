package dev.phoenium.service;

import dev.phoenium.mixin.LocalFurnaceTickAccess;
import dev.phoenium.mixin.LocalHopperTickAccess;
import dev.phoenium.mixin.LocalNativeTickerAccess;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.BlockState;

/** The pinned native branches below mutate only the exact block entity. Selection
 * happens under its owner before any callback; side-effectful branches retain their
 * original ordered invocation. This is a precondition, never a catch-and-retry path. */
public final class LocalBlockEntityTick {
    private LocalBlockEntityTick() { }

    public static Selection select(TickingBlockEntity ticker) {
        if (!(ticker instanceof LocalNativeTickerAccess access)) return null;
        BlockEntity entity = access.phoenium$localBlockEntity();
        if (entity == null || entity.isRemoved() || !entity.hasLevel()) return null;
        return eligible(entity) ? new Selection(ticker, entity, entity.getBlockState()) : null;
    }

    private static boolean eligible(BlockEntity entity) {
        Class<?> type = entity.getClass();
        if (type == FurnaceBlockEntity.class || type == BlastFurnaceBlockEntity.class || type == SmokerBlockEntity.class) {
            var furnace = (LocalFurnaceTickAccess) entity;
            // No ignition/extinction, fuel consumption, recipe completion, dirty notification,
            // neighbor update or world block write is reachable on this native branch.
            return furnace.phoenium$litTimeRemaining() > 1
                && furnace.phoenium$cookingTimer() + 1 != furnace.phoenium$cookingTotalTime();
        }
        if (type == HopperBlockEntity.class) {
            // The original tick still advances cooldown and samples the unchanged native clock.
            // Transfer/lookup/entity-query branches run at their ordinary ordered boundary.
            return ((LocalHopperTickAccess) entity).phoenium$cooldownTime() > 1
                || !entity.getBlockState().getValue(HopperBlock.ENABLED);
        }
        return false;
    }

    public record Selection(TickingBlockEntity ticker, BlockEntity entity, BlockState state) {
        public void requireCurrent() {
            if (((LocalNativeTickerAccess) ticker).phoenium$localBlockEntity() != entity
                    || entity.isRemoved() || !entity.hasLevel() || entity.getBlockState() != state || !eligible(entity))
                throw new IllegalStateException("selected local block entity changed before original execution: " + entity.getBlockPos());
        }
    }
}
