package dev.phoenium.mixin;

import dev.phoenium.service.LocalNativeTickerAccess;

import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity")
public interface LocalBoundBlockEntityTickerMixin extends LocalNativeTickerAccess {
    @Override @Accessor("blockEntity") BlockEntity phoenium$localBlockEntity();
}
