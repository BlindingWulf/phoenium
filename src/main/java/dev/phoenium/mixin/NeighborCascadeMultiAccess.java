package dev.phoenium.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.redstone.Orientation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The only mutable native step: its cursor and lazily created orientation are rolled back on a cut. */
@Mixin(targets = "net.minecraft.world.level.redstone.CollectingNeighborUpdater$MultiNeighborUpdate")
public interface NeighborCascadeMultiAccess {
    @Accessor("sourcePos") BlockPos phoenium$sourcePos();
    @Accessor("idx") int phoenium$idx();
    @Accessor("idx") void phoenium$idx(int idx);
    @Accessor("orientation") Orientation phoenium$orientation();
    @Accessor("orientation") void phoenium$orientation(Orientation orientation);
}
