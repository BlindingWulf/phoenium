package dev.phoenium.mixin;

import dev.phoenium.chunk.FullCachedComponentInputs;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(BlockEntity.class)
public abstract class FullLiveBlockDataComponentsMixin implements FullCachedComponentInputs.BlockAccess {
    @Shadow private DataComponentMap components;
    @Override public DataComponentMap phoenium$rawComponents() { return components; }
}
