package dev.phoenium.service;

import dev.phoenium.mixin.LocalFurnaceTickAccess;
import dev.phoenium.mixin.LocalHopperTickAccess;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.state.BlockState;

/** The pinned native branches below mutate only the exact block entity. Selection
 * happens under its owner before any callback; side-effectful branches retain their
 * original ordered invocation. This is a precondition, never a catch-and-retry path. */
public final class LocalBlockEntityTick {
    private LocalBlockEntityTick() { }
    private static final ThreadLocal<Selection> CURRENT = new ThreadLocal<>();
    private static volatile RecipePolicy recipePolicy;
    private record RecipePolicy(java.lang.ref.WeakReference<net.minecraft.world.item.crafting.RecipeMap> map, boolean safe) { }
    private static boolean trustedCookingRecipes(BlockEntity entity) {
        if (!(entity.getLevel() instanceof net.minecraft.server.level.ServerLevel level)) return false;
        var map = ((dev.phoenium.mixin.LocalRecipeManagerAccess) level.recipeAccess()).phoenium$recipes();
        RecipePolicy policy = recipePolicy;
        if (policy != null && policy.map().get() == map) return policy.safe();
        boolean safe = true;
        for (var holder : map.values()) {
            var recipe = holder.value(); var type = recipe.getType(); var implementation = recipe.getClass();
            if (type == net.minecraft.world.item.crafting.RecipeType.SMELTING)
                safe &= implementation == net.minecraft.world.item.crafting.SmeltingRecipe.class;
            else if (type == net.minecraft.world.item.crafting.RecipeType.BLASTING)
                safe &= implementation == net.minecraft.world.item.crafting.BlastingRecipe.class;
            else if (type == net.minecraft.world.item.crafting.RecipeType.SMOKING)
                safe &= implementation == net.minecraft.world.item.crafting.SmokingRecipe.class;
        }
        recipePolicy = new RecipePolicy(new java.lang.ref.WeakReference<>(map), safe);
        return safe;
    }

    /** The original crash reporter reads the exact bound native chunk under the same
     * current claim. It must not enter ServerChunkCache's server-thread future path. */
    public static BlockState crashBlockState(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos) {
        Selection selected = CURRENT.get();
        if (selected == null) return null;
        var owner = dev.phoenium.ownership.OwnershipContext.current().orElseThrow();
        if (selected.entity().getLevel() != level || !selected.entity().getBlockPos().equals(pos))
            throw new IllegalStateException("local crash report escaped its original block entity");
        return ((LocalNativeTickerAccess) selected.ticker()).phoenium$localChunk().getBlockState(pos);
    }

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
                && furnace.phoenium$cookingTimer() + 1 != furnace.phoenium$cookingTotalTime()
                && trustedCookingRecipes(entity);
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
        public void tickOriginal() {
            if (CURRENT.get() != null) throw new IllegalStateException("nested local native callback");
            CURRENT.set(this);
            try { ticker.tick(); } finally { CURRENT.remove(); }
        }
        public void requireCurrent() {
            if (((LocalNativeTickerAccess) ticker).phoenium$localBlockEntity() != entity
                    || entity.isRemoved() || !entity.hasLevel() || entity.getBlockState() != state || !eligible(entity))
                throw new IllegalStateException("selected local block entity changed before original execution: " + entity.getBlockPos());
        }
    }
}
