package dev.phoenium.mixin;

import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.world.level.redstone.CollectingNeighborUpdater$ShapeUpdate")
public interface NeighborCascadeShapeAccess {
    @Accessor("pos") BlockPos phoenium$pos();
}
