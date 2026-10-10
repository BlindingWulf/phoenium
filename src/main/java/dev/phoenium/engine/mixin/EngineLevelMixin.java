package dev.phoenium.engine.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.phoenium.engine.EngineNeighborUpdater;
import dev.phoenium.engine.EngineRandom;
import dev.phoenium.engine.TileContext;
import dev.phoenium.engine.TileEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.redstone.CollectingNeighborUpdater;
import net.minecraft.world.level.storage.WritableLevelData;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * S9-E1 (engine mode only): a level's random and neighbour updater route a tile thread to its own, a tile thread reads
 * block entities from its chunks (vanilla answers null off the level thread), and ticker registration is deferred.
 */
@Mixin(value = Level.class, priority = 2000)
abstract class EngineLevelMixin {
    @Shadow @Final @Mutable protected RandomSource random;
    @Shadow @Final @Mutable protected CollectingNeighborUpdater neighborUpdater;

    @Shadow public abstract boolean isInValidBounds(BlockPos pos);

    @Shadow public abstract LevelChunk getChunkAt(BlockPos pos);

    @Inject(method = "<init>", at = @At("RETURN"))
    private void phoenium$engineRouting(WritableLevelData levelData, ResourceKey<Level> dimension, RegistryAccess registryAccess,
                                        Holder<DimensionType> dimensionTypeRegistration, boolean isClientSide, boolean isDebug,
                                        long biomeZoomSeed, int maxChainedNeighborUpdates, CallbackInfo ci) {
        this.random = new EngineRandom(this.random);
        this.neighborUpdater = new EngineNeighborUpdater((Level) (Object) this, maxChainedNeighborUpdates);
    }

    @Inject(method = "getBlockEntity", at = @At("HEAD"), cancellable = true)
    private void phoenium$tileBlockEntity(BlockPos pos, CallbackInfoReturnable<BlockEntity> cir) {
        if (TileEngine.current() == null) return;
        cir.setReturnValue(this.isInValidBounds(pos)
            ? this.getChunkAt(pos).getBlockEntity(pos, LevelChunk.EntityCreationType.IMMEDIATE) : null);
    }

    /** A block entity's ticker registered from a tile joins the list after the colour (still before this tick's block entities). */
    @WrapMethod(method = "addBlockEntityTicker")
    private void phoenium$deferTicker(TickingBlockEntity ticker, Operation<Void> original) {
        TileContext context = TileEngine.current();
        if (context == null) {
            original.call(ticker);
            return;
        }
        Level level = (Level) (Object) this;
        context.defer(() -> level.addBlockEntityTicker(ticker));
    }
}
