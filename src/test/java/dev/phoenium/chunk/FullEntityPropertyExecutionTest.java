package dev.phoenium.chunk;

import static dev.phoenium.chunk.FullRegisteredPredicateExecutionTest.runRegistered;
import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.Codec;
import java.util.*;
import net.minecraft.advancements.predicates.NbtPredicate;
import net.minecraft.advancements.predicates.entity.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.predicates.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Original registry lookup, selector, entity sub-predicates and NBT serializer execute
 * inside the admitted FULL body; discovery only retains their original producer shape. */
class FullEntityPropertyExecutionTest {
    @BeforeAll static void bootstrap() { FullSavedVillagerInputTest.bootstrap(); }

    static int runInstalled() throws Exception {
        bootstrap(); var test = new FullEntityPropertyExecutionTest();
        test.originalTypeFlagsAndNbtPredicateRunAgainstTheConvergedCandidate();
        test.originalConstructedEmptyPredicateIgnoresLaterUnsupportedMapReplacement();
        test.absentNonSelectorEntityParameterRetainsOriginalNullShortCircuit();
        return 3;
    }

    @Test void originalTypeFlagsAndNbtPredicateRunAgainstTheConvergedCandidate() throws Exception {
        var expected = new CompoundTag(); expected.putString("CustomName", "entity property candidate");
        var predicate = EntityPredicate.Builder.entity().of(BuiltInRegistries.ENTITY_TYPE, EntityTypes.TEXT_DISPLAY)
                .flags(EntityFlagsPredicate.Builder.flags().setCrouching(false)).nbt(new NbtPredicate(expected)).build();
        runRegistered(new LootItemEntityPropertyCondition(Optional.of(predicate), LootContext.EntityTarget.THIS),
                "entity property candidate", (fixture, candidate) -> { },
                (fixture, candidate) -> assertFalse(fixture.barrier.tripped()));
    }

    @Test void originalConstructedEmptyPredicateIgnoresLaterUnsupportedMapReplacement() throws Exception {
        var parts = new LinkedHashMap<Codec<? extends EntitySubPredicate>, EntitySubPredicate>();
        var predicate = new EntityPredicate(parts);
        EntitySubPredicate unreachable = (entity, level, position) -> { throw new AssertionError("post-construction replacement reached"); };
        parts.put(Codec.STRING.xmap(ignored -> unreachable, ignored -> "unused"), unreachable);
        runRegistered(new LootItemEntityPropertyCondition(Optional.of(predicate), LootContext.EntityTarget.THIS),
                "original empty predicate", (fixture, candidate) -> { });
    }

    @Test void absentNonSelectorEntityParameterRetainsOriginalNullShortCircuit() throws Exception {
        EntitySubPredicate unreachable = (entity, level, position) -> { throw new AssertionError("absent target predicate reached"); };
        var predicate = new EntityPredicate(Map.of(Codec.STRING.xmap(ignored -> unreachable, ignored -> "unused"), unreachable));
        var missingTarget = Arrays.stream(LootContext.EntityTarget.values()).filter(value -> value != LootContext.EntityTarget.THIS)
                .findFirst().orElseThrow();
        runRegistered(new InvertedLootItemCondition(new LootItemEntityPropertyCondition(Optional.of(predicate), missingTarget)),
                "missing target short circuit", (fixture, candidate) -> { });
    }
}
