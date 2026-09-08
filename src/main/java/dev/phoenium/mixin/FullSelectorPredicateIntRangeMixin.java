package dev.phoenium.mixin;

import dev.phoenium.chunk.FullSelectorPredicateInputs;
import net.minecraft.world.level.storage.loot.IntRange;
import net.minecraft.world.level.storage.loot.providers.number.NumberProvider;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Read-only bridge for selector dependency discovery; the original IntRange predicate remains untouched. */
@Mixin(IntRange.class)
abstract class FullSelectorPredicateIntRangeMixin implements FullSelectorPredicateInputs.IntRangeAccess {
    @Shadow @Final private NumberProvider min;
    @Shadow @Final private NumberProvider max;

    @Override public NumberProvider phoenium$min() { return min; }
    @Override public NumberProvider phoenium$max() { return max; }
}
