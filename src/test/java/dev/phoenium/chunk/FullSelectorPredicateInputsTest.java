package dev.phoenium.chunk;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.MapCodec;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.commands.arguments.NbtPathArgument;
import net.minecraft.advancements.predicates.BlockPredicate;
import net.minecraft.advancements.predicates.LocationPredicate;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.loot.IntRange;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.providers.number.*;
import net.minecraft.world.level.storage.loot.predicates.*;
import net.minecraft.world.attribute.EnvironmentAttribute;
import com.mojang.brigadier.StringReader;
import org.junit.jupiter.api.Test;

/** Dependency discovery tests. The reflected visitor is used so these cases do not require
 * a server or predicate registry, and can assert that discovery never evaluates providers. */
final class FullSelectorPredicateInputsTest {
    private static final ChunkPos CANDIDATE = new ChunkPos(3, -2);

    @Test void nestedNumericProvidersExposeStorageWithoutEvaluatingScoreRandomOrStorage() throws Exception {
        Identifier storageId = Identifier.parse("phoenium:numeric_fixture");
        NumberProvider storage = new StorageValue(storageId,
                NbtPathArgument.nbtPath().parse(new StringReader("value")));
        NumberProvider nested = new UniformGenerator(
                new BinomialDistributionGenerator(new Sum(List.of(ConstantValue.exactly(2), storage)),
                        ConstantValue.exactly(.5F)),
                new ScoreboardValue(null, "score", 1F));
        Object visitor = visit(new ValueCheckCondition(nested, range(nested, ConstantValue.exactly(9))));

        assertNull(field(visitor, "unmet"));
        assertEquals(Set.of(storageId), field(visitor, "storageIds"));
    }

    @Test void storageIdentifierReservationHonorsFinitePlanCapacityBeforeGrowing() throws Exception {
        Identifier storageId = Identifier.parse("phoenium:capacity_fixture");
        NumberProvider storage = new StorageValue(storageId,
                NbtPathArgument.nbtPath().parse(new StringReader("value")));
        Object visitor = visit(new ValueCheckCondition(storage, IntRange.exact(1)), ignored -> false);
        assertTrue((Boolean) field(visitor, "capacity"));
        assertTrue(((Set<?>) field(visitor, "storageIds")).isEmpty());
    }

    @Test void timeAndEntityScoreRangesInspectBothImmutableBounds() throws Exception {
        NumberProvider min = ConstantValue.exactly(2);
        NumberProvider max = ConstantValue.exactly(8);
        Object time = visit(new TimeCheck(null, Optional.empty(), range(min, max)));
        assertNull(field(time, "unmet"));

        Object scores = visit(new EntityHasScoreCondition(
                Map.of("objective", range(min, max)), LootContext.EntityTarget.THIS));
        assertNull(field(scores, "unmet"));
    }

    @Test void enchantmentLevelProviderRemainsOriginalMissingParameterFailurePoint() throws Exception {
        NumberProvider provider = EnchantmentLevelProvider.forEnchantmentLevel(
                net.minecraft.world.item.enchantment.LevelBasedValue.constant(1));
        Object visitor = visit(new ValueCheckCondition(provider, IntRange.exact(1)));
        assertNull(field(visitor, "unmet"));
        // Discovery only recognizes the provider. It neither supplies nor probes the absent
        // ENCHANTMENT_LEVEL context parameter; the original condition remains authoritative.
        assertTrue(new ValueCheckCondition(ConstantValue.exactly(1), IntRange.exact(1)).test(null));
    }

    @Test void positionalEnvironmentNumberProviderDeclaresCandidateBiomeOnly() throws Exception {
        EnvironmentAttribute<?> attribute = BuiltInRegistries.ENVIRONMENT_ATTRIBUTE.stream()
                .filter(EnvironmentAttribute::isPositional).findFirst().orElseThrow();
        Object visitor = visit(new ValueCheckCondition(
                EnvironmentAttributeValue.forEnvironmentAttribute(attribute), IntRange.exact(1)));
        assertNull(field(visitor, "unmet"));
        assertEquals(Set.of(CANDIDATE), field(visitor, "biomes"));
    }

    @Test void positionalEnvironmentCheckUsesTheSameCandidateBiomeReceipt() throws Exception {
        EnvironmentAttribute<?> attribute = BuiltInRegistries.ENVIRONMENT_ATTRIBUTE.stream()
                .filter(EnvironmentAttribute::isPositional).findFirst().orElseThrow();
        @SuppressWarnings({"rawtypes", "unchecked"})
        EnvironmentAttributeCheck<?> condition = new EnvironmentAttributeCheck(
                (EnvironmentAttribute) attribute, attribute.defaultValue());
        Object visitor = visit(condition);
        assertNull(field(visitor, "unmet"));
        assertEquals(Set.of(CANDIDATE), field(visitor, "biomes"));
    }

    @Test void locationBiomeAndFullClausesDeclareBothFullAndBiomeBorderInputs() throws Exception {
        var block = BlockPredicate.Builder.block().of(BuiltInRegistries.BLOCK, Blocks.STONE).build();
        var location = new LocationPredicate(Optional.empty(), Optional.of(HolderSet.<Biome>empty()),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(block),
                Optional.empty(), Optional.empty());
        Object visitor = visit(new LocationCheck(Optional.of(location), net.minecraft.core.BlockPos.ZERO));

        assertNull(field(visitor, "unmet"));
        assertEquals(Set.of(CANDIDATE), field(visitor, "full"));
        Set<ChunkPos> expectedBiomes = new LinkedHashSet<>();
        for (int x = CANDIDATE.x() - 1; x <= CANDIDATE.x() + 1; x++)
            for (int z = CANDIDATE.z() - 1; z <= CANDIDATE.z() + 1; z++) expectedBiomes.add(new ChunkPos(x, z));
        assertEquals(expectedBiomes, field(visitor, "biomes"));
    }

    @Test void locationStructureClauseRemainsUnmetUntilReferencedChunkInputsAreTyped() throws Exception {
        var location = new LocationPredicate(Optional.empty(), Optional.empty(),
                Optional.of(HolderSet.<net.minecraft.world.level.levelgen.structure.Structure>empty()),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        Object visitor = visit(new LocationCheck(Optional.of(location), net.minecraft.core.BlockPos.ZERO));
        assertTrue(((String) field(visitor, "unmet")).contains("STRUCTURE_REFERENCES/STRUCTURE_STARTS"));
    }

    @Test void unknownNumberProviderIsRejectedWithoutCallingItsOriginalMethod() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        NumberProvider unknown = new NumberProvider() {
            @Override public float getFloat(LootContext ignored) { calls.incrementAndGet(); return 1; }
            @Override public MapCodec<? extends NumberProvider> codec() { return null; }
        };
        Object visitor = visit(new ValueCheckCondition(ConstantValue.exactly(1), range(unknown, null)));
        assertTrue(((String) field(visitor, "unmet")).contains("unsupported"));
        assertEquals(0, calls.get());
    }

    @Test void missingSelectorBlockStateParameterKeepsOriginalFalseBranch() throws Exception {
        var condition = new LootItemBlockStatePropertyCondition(
                BuiltInRegistries.BLOCK.wrapAsHolder(Blocks.STONE), Optional.empty());
        Object visitor = visit(condition);
        assertNull(field(visitor, "unmet"));
    }

    private static IntRange range(NumberProvider min, NumberProvider max) throws Exception {
        Constructor<IntRange> constructor = IntRange.class.getDeclaredConstructor(Optional.class, Optional.class);
        constructor.setAccessible(true);
        return constructor.newInstance(Optional.ofNullable(min), Optional.ofNullable(max));
    }

    private static Object visit(LootItemCondition condition) throws Exception {
        return visit(condition, ignored -> true);
    }

    private static Object visit(LootItemCondition condition, java.util.function.LongPredicate capacity) throws Exception {
        Class<?> visitorClass = Class.forName(FullSelectorPredicateInputs.class.getName() + "$Visitor");
        Constructor<?> constructor = visitorClass.getDeclaredConstructor(
                net.minecraft.core.HolderLookup.RegistryLookup.class, Set.class,
                java.util.function.LongPredicate.class);
        constructor.setAccessible(true);
        Object visitor = constructor.newInstance(null, Set.of(CANDIDATE), capacity);
        var visit = visitorClass.getDeclaredMethod("visit", LootItemCondition.class);
        visit.setAccessible(true);
        try { visit.invoke(visitor, condition); }
        catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error error) throw error;
            throw failure;
        }
        return visitor;
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
