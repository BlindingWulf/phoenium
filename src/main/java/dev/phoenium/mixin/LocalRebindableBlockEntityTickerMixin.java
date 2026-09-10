package dev.phoenium.mixin;

import dev.phoenium.service.LocalNativeTickerAccess;
import dev.phoenium.service.WrappedBlockEntityTickerAccess;

import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.world.level.chunk.LevelChunk$RebindableTickingBlockEntityWrapper")
abstract class LocalRebindableBlockEntityTickerMixin implements LocalNativeTickerAccess, WrappedBlockEntityTickerAccess {
    @Shadow private TickingBlockEntity ticker;
    @Override @Accessor("ticker") public abstract TickingBlockEntity phoenium$wrappedTicker();
    @Override public BlockEntity phoenium$localBlockEntity() {
        return ticker instanceof LocalNativeTickerAccess access ? access.phoenium$localBlockEntity() : null;
    }
    @Override public net.minecraft.world.level.chunk.LevelChunk phoenium$localChunk() {
        return ticker instanceof LocalNativeTickerAccess access ? access.phoenium$localChunk() : null;
    }
}
