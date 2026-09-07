package dev.phoenium.chunk;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.frog.Frog;
import net.minecraft.world.entity.animal.frog.Tadpole;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static dev.phoenium.chunk.FullDecisionWitnessSupport.*;
import static dev.phoenium.chunk.FullInitializedInputBindingTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Original saved reader witnesses exercise concrete families with the game's initialized registries. */
final class FullSavedEntityInputTest {
    private static RegistryAccess.Frozen registries;

    @BeforeAll static void bootstrap() {
        FullSavedVillagerInputTest.bootstrap();
        var lookup = net.minecraft.data.registries.VanillaRegistries.createLookup();
        List<Registry<?>> copied = new ArrayList<>();
        lookup.listRegistries().forEach(registry -> copied.add(copyRegistry(registry)));
        registries = new RegistryAccess.ImmutableRegistryAccess(copied).freeze();
    }

    @SuppressWarnings("unchecked")
    private static <T> Registry<T> copyRegistry(HolderLookup.RegistryLookup<T> source) {
        var key = (net.minecraft.resources.ResourceKey<? extends Registry<T>>) (Object) source.key();
        var registry = new MappedRegistry<T>(key, source.registryLifecycle());
        source.listElements().forEach(holder -> registry.register(holder.key(), holder.value(), RegistrationInfo.BUILT_IN));
        return registry.freeze();
    }

    @ParameterizedTest @ValueSource(strings = {"allay", "armadillo", "armor_stand", "axolotl", "bee", "camel", "cat",
            "chicken", "cow", "donkey", "ender_dragon", "enderman", "fox", "frog", "goat", "happy_ghast", "horse",
            "husk", "iron_golem", "llama", "mooshroom", "pig", "piglin", "rabbit", "sheep", "shulker", "skeleton",
            "slime", "snow_golem", "wolf", "zombie", "zombie_villager", "skeleton_horse", "wandering_trader"})
    void originalLivingSavedReadersRestoreContextWithoutCollisionOrBehaviorTicks(String id) throws Exception {
        var f = new FullTadpoleDecisionTest.Fixture(1, registries);
        var source = contextual(id, 8, 8, -200);
        f.proto.getEntities().clear(); f.proto.addEntity(source);
        var fact = new FullSavedInput().capture(List.of(source)).entities().getFirst();
        assertTrue(FullSavedEntityInputs.supports(fact)); assertTrue(fact.contextualDimensions());
        assertEquals(0, f.nextId);
        f.run();
        assertEquals(1, f.inserted.size(), id + " original reader must not be silently dropped");
        var entity = assertInstanceOf(LivingEntity.class, f.inserted.getFirst());
        assertEquals(id, BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath());
        // Original Shulker.setPos snaps an unattached saved object to its block center.
        double coordinate = id.equals("shulker") ? 8.5 : 8.0;
        assertEquals(coordinate, entity.getX()); assertEquals(coordinate, entity.getZ());
        assertEquals(3.0, entity.getAttributeValue(Attributes.SCALE));
        assertEquals(Pose.SLEEPING, entity.getPose());
        assertEquals(new BlockPos(2032, 75, -1104), entity.getSleepingPos().orElseThrow());
        assertEquals(1, entity.getEffect(MobEffects.SPEED).getAmplifier());
        assertEquals(true, field(entity, Entity.class, "firstTick"));
        assertTrue(entity.getBrain().getRunningBehaviors().isEmpty());
        assertEquals(0, f.localReads); assertEquals(0, f.difficultyQueries);
        assertFalse(f.fullSlot.isDone());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void productionBindsGenericFamilyAndPassengerContextWithoutReaderExecution(boolean reload) throws Exception {
        var f = new FullRegistrationFixture(true);
        try {
            var source = contextual("allay", 17.8, -16.1, -200);
            var passengers = new net.minecraft.nbt.ListTag();
            passengers.add(contextual("sheep", 400, 200, -200)); source.put("Passengers", passengers);
            saved(f, ChunkPos.ZERO, reload, source); f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain();
            var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
            assertEquals(FullInitializedInputs.State.SATISFIED, graph.inputEvaluation().state(), graph.inputEvaluation().obligation());
            assertEquals(Set.of(FullInitializedInputs.Purpose.SELF_INITIALIZED), graph.inputReceipt().targets().getFirst().purposes());
            assertEquals(1, graph.inputReceipt().targets().size());
            assertEquals(0, f.world.nextId); assertTrue(f.world.inserted.isEmpty());
            assertEquals(RandomSource.create(73031L).nextLong(), f.world.random.nextLong());
        } finally { f.stop(); }
        assertCleanup(f);
    }

    @Test void originalMatureTadpoleCopiesEffectsAndSleepButKeepsNewFrogScaleBeforeExplicitRepair() throws Exception {
        var f = new FullTadpoleDecisionTest.Fixture(Tadpole.ticksToBeFrog);
        var source = contextual("tadpole", 8, 8, Tadpole.ticksToBeFrog);
        f.proto.getEntities().clear(); f.proto.addEntity(source);
        var fact = new FullSavedInput().capture(List.of(source)).entities().getFirst();
        assertEquals(Set.of(ChunkPos.ZERO), FullSavedInput.collisionPositions(fact, 256));
        f.run();
        assertEquals(2, f.inserted.size());
        Frog frog = assertInstanceOf(Frog.class, f.inserted.getFirst());
        Tadpole old = assertInstanceOf(Tadpole.class, f.inserted.get(1));
        assertTrue(old.isRemoved()); assertEquals(3.0, old.getAttributeValue(Attributes.SCALE));
        assertEquals(Pose.SLEEPING, old.getPose());
        assertEquals(1.0, frog.getAttributeValue(Attributes.SCALE));
        assertEquals(Pose.STANDING, frog.getPose());
        assertEquals(EntityTypes.FROG.getDimensions().width(), frog.getBbWidth());
        assertEquals(new BlockPos(2032, 75, -1104), frog.getSleepingPos().orElseThrow());
        assertEquals(1, frog.getEffect(MobEffects.SPEED).getAmplifier());
        assertTrue(f.localReads > 1); assertEquals(1, f.difficultyQueries); assertEquals(1, f.sounds);
    }

    @Test void fuzzyBiomeOriginalSelectionIsContainedIncludingCandidateOutsideCollisionWindow() throws Exception {
        var source = tag(17.8, 17.8, Tadpole.ticksToBeFrog);
        var fact = new FullSavedInput().capture(List.of(source)).entities().getFirst();
        Set<ChunkPos> candidates = FullSavedEntityInputs.conversionBiomePositions(fact);
        assertEquals(Set.of(ChunkPos.ZERO, new ChunkPos(0, 1), new ChunkPos(1, 0), new ChunkPos(1, 1)), candidates);
        assertEquals(Set.of(new ChunkPos(1, 1)), FullSavedInput.collisionPositions(fact, 256));
        var observed = new java.util.HashSet<ChunkPos>();
        var plains = registries.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
        // Actual pinned fuzzy lookup chooses from every seed; discovery never chooses or reads its seed.
        for (long seed = 0; seed < 256; seed++) new BiomeManager((x, y, z) -> {
            observed.add(new ChunkPos(x >> 2, z >> 2)); return plains;
        }, seed).getBiome(new BlockPos(17, 8, 17));
        assertEquals(candidates, observed);
    }

    @Test void productionMatureTadpoleBindsBiomeCandidatesOutsideItsCollisionInputs() throws Exception {
        var f = new FullRegistrationFixture(true);
        try {
            var position = new ChunkPos(1, 1);
            saved(f, position, false, tag(17.8, 17.8, Tadpole.ticksToBeFrog));
            f.start(position, ChunkStatus.FULL); f.drain(); var graph = f.runtime.graph(f.holder(position));
            assertEquals(FullInitializedInputs.State.SATISFIED, graph.inputEvaluation().state(), graph.inputEvaluation().obligation());
            assertEquals(Set.of(FullInitializedInputs.Purpose.ENVIRONMENT), target(graph, ChunkPos.ZERO).purposes());
            assertTrue(target(graph, position).purposes().contains(FullInitializedInputs.Purpose.COLLISION));
            assertEquals(0, f.world.nextId);
        } finally { f.stop(); }
        assertCleanup(f);
    }

    private static CompoundTag contextual(String id, double x, double z, int age) {
        var source = tag(x, z, age); source.putString("id", "minecraft:" + id);
        source.store("attributes", AttributeInstance.Packed.LIST_CODEC, List.of(new AttributeInstance.Packed(Attributes.SCALE, 3.0, List.of())));
        source.store("active_effects", MobEffectInstance.CODEC.listOf(), List.of(new MobEffectInstance(MobEffects.SPEED, 1200, 1)));
        source.store("sleeping_pos", BlockPos.CODEC, new BlockPos(2032, 75, -1104));
        return source;
    }
}
