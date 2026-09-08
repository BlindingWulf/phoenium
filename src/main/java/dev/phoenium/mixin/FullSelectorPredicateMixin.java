package dev.phoenium.mixin;

import dev.phoenium.chunk.FullSelectorPredicateInputs;
import java.util.List;
import net.minecraft.world.level.storage.loot.predicates.CompositeLootItemCondition;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/** Immutable registered predicate syntax exposed for dependency discovery only. */
@Mixin(CompositeLootItemCondition.class)
abstract class FullSelectorPredicateMixin implements FullSelectorPredicateInputs.CompositeAccess {
    @Shadow protected List<LootItemCondition> terms;
    @Override public List<LootItemCondition> phoenium$predicateTerms() { return terms; }
}
