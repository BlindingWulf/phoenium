package dev.phoenium.engine.mixin;

import java.util.List;
import net.minecraft.world.level.PotentialCalculator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** S9-E7: a spawn state's charges, read by the spawn memo's check (PointCharge is package-private). */
@Mixin(PotentialCalculator.class)
public interface EnginePotentialAccess {
    @Accessor("charges")
    List<?> phoenium$charges();
}
