package dev.phoenium.mixin;

import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(targets = "net.minecraft.world.level.chunk.LevelChunk$RebindableTickingBlockEntityWrapper")
abstract class LocalRebindableBlockEntityTickerMixin implements LocalNativeTickerAccess {
    @Shadow private TickingBlockEntity ticker;
    @Override public BlockEntity phoenium$localBlockEntity() {
        return ticker instanceof LocalNativeTickerAccess access ? access.phoenium$localBlockEntity() : null;
    }
}
