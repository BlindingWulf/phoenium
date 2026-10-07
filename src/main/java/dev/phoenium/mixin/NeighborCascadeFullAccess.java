package dev.phoenium.mixin;

import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.world.level.redstone.CollectingNeighborUpdater$FullNeighborUpdate")
public interface NeighborCascadeFullAccess {
    @Accessor("pos") BlockPos phoenium$pos();
}
