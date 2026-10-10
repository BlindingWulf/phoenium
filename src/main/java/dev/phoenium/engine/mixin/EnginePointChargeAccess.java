package dev.phoenium.engine.mixin;

import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** S9-E7: one charge of a spawn state (PotentialCalculator.PointCharge is package-private), read by the spawn memo's check. */
@Mixin(targets = "net.minecraft.world.level.PotentialCalculator$PointCharge")
public interface EnginePointChargeAccess {
    @Accessor("pos")
    BlockPos phoenium$pos();

    @Accessor("charge")
    double phoenium$charge();
}
