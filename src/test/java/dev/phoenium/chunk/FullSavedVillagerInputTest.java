package dev.phoenium.chunk;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.attribute.EnvironmentAttribute;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.gossip.GossipContainer;
import net.minecraft.world.entity.ai.gossip.GossipType;
import net.minecraft.world.entity.ai.memory.ExpirableValue;
import net.minecraft.world.entity.ai.memory.MemoryMap;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static dev.phoenium.chunk.FullDecisionWitnessSupport.*;
import static dev.phoenium.chunk.FullInitializedInputBindingTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Original constructor/reader/brain construction are test witnesses only. Production discovery is scalar-only. */
final class FullSavedVillagerInputTest {
    @BeforeAll static void bootstrap() {
        FullProductionRegistrationTest.bootstrap();
        // 26.2 binds item prototype components after registry bootstrap. Use the game's
        // initializer and vanilla lookup; an empty fabricated component map would lose
        // the inventory/trade behavior this saved-reader witness needs to preserve.
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(net.minecraft.data.registries.VanillaRegistries.createLookup())
                .forEach(net.minecraft.core.component.DataComponentInitializers.PendingComponents::apply);
    }

    @ParameterizedTest
    @CsvSource({"0,false,2", "0,true,3", "-1200,false,3", "-1200,true,4"})
    void originalSavedReaderSamplesConstructionOriginAndSavedPositionWithoutRunningBehaviors(
            int age, boolean packedBrain, int samples) throws Exception {
        var f = new FullTadpoleDecisionTest.Fixture(1);
        var source = villager(31.875, -16.125, age);
        if (packedBrain) source.store("Brain", Brain.Packed.CODEC, memories());
        f.proto.getEntities().clear(); f.proto.addEntity(source);
        var observed = installOriginalBiomeLayers(f);
        var facts = new FullSavedInput().capture(List.of(source));
        assertNull(facts.unmet());
        assertEquals(Set.of(ChunkPos.ZERO, new ChunkPos(1, -2)),
                FullSavedVillagerInputs.environmentPositions(facts.entities().getFirst()));
        assertEquals(0, f.nextId); assertTrue(observed.isEmpty());

        f.run();
        Villager loaded = assertInstanceOf(Villager.class, f.inserted.getFirst());
        assertEquals(1, f.inserted.size()); assertEquals(age, loaded.getAge());
        assertEquals(31.875, loaded.getX()); assertEquals(-16.125, loaded.getZ());
        assertEquals(samples, observed.size());
        assertEquals(ChunkPos.ZERO, observed.getFirst());
        assertTrue(observed.subList(1, observed.size()).stream().allMatch(new ChunkPos(1, -2)::equals));
        assertEquals(Set.of(ChunkPos.ZERO, new ChunkPos(1, -2)), Set.copyOf(observed));
        assertTrue(loaded.getBrain().getRunningBehaviors().isEmpty());
        assertSame(age < 0 ? EnvironmentAttributes.BABY_VILLAGER_ACTIVITY : EnvironmentAttributes.VILLAGER_ACTIVITY,
                field(loaded.getBrain(), Brain.class, "schedule"));
        assertEquals(true, field(loaded, Entity.class, "firstTick"));
        assertEquals(0, f.localReads); assertEquals(0, f.difficultyQueries); assertEquals(0, f.sounds);
        if (packedBrain) assertEquals(GlobalPos.of(Level.NETHER, new BlockPos(912, 83, -704)),
                loaded.getBrain().getMemory(MemoryModuleType.HOME).orElseThrow());
        assertFalse(f.fullSlot.isDone(), "An original-method footprint witness does not publish public FULL");
    }

    @Test void originalReaderRestoresAttributesSleepEffectsTradeInventoryAndMemoriesWithoutPositionRepair() throws Exception {
        var f = new FullTadpoleDecisionTest.Fixture(1);
        var source = richVillager(16.125, -0.125, -200);
        f.proto.getEntities().clear(); f.proto.addEntity(source);
        var observed = installOriginalBiomeLayers(f);
        var fact = new FullSavedInput().capture(List.of(source)).entities().getFirst();
        assertTrue(fact.contextualDimensions());
        assertEquals(Set.of(ChunkPos.ZERO, new ChunkPos(1, -1)), FullSavedVillagerInputs.environmentPositions(fact));
        assertEquals(0, f.nextId); assertTrue(observed.isEmpty());

        f.run();
        Villager loaded = assertInstanceOf(Villager.class, f.inserted.getFirst());
        assertEquals(-200, loaded.getAge()); assertTrue(loaded.isAgeLocked());
        assertEquals(16.125, loaded.getX()); assertEquals(-0.125, loaded.getZ());
        assertEquals(Pose.SLEEPING, loaded.getPose());
        assertEquals(new BlockPos(2032, 75, -1104), loaded.getSleepingPos().orElseThrow());
        assertEquals(3.0, loaded.getAttributeValue(Attributes.SCALE));
        assertEquals(1, loaded.getEffect(MobEffects.SPEED).getAmplifier());
        assertTrue(loaded.getVillagerData().profession().is(VillagerProfession.FARMER));
        assertEquals(3, loaded.getVillagerData().level()); assertTrue(loaded.getVillagerDataFinalized());
        assertEquals(81, loaded.getVillagerXp());
        assertEquals(7, loaded.getInventory().countItem(Items.BREAD));
        assertEquals(1, loaded.getOffers().size());
        assertEquals(2, loaded.getOffers().getFirst().getUses());
        assertEquals(5, loaded.getOffers().getFirst().getBaseCostA().getCount());
        assertEquals(4, loaded.getGossips().getReputation(GOSSIP_PLAYER, ignored -> true));
        assertEquals(GlobalPos.of(Level.NETHER, new BlockPos(912, 83, -704)),
                loaded.getBrain().getMemory(MemoryModuleType.HOME).orElseThrow());
        assertEquals(Set.of(ChunkPos.ZERO, new ChunkPos(1, -1)), Set.copyOf(observed));
        assertEquals(4, observed.size()); assertTrue(loaded.getBrain().getRunningBehaviors().isEmpty());
        assertEquals(0, f.localReads); assertEquals(0, f.sounds);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void productionDiscoveryBindsRichVillagerPreparedInputsWithoutExecutingReaderOrRandom(boolean reload) throws Exception {
        var f = new FullRegistrationFixture(true);
        var source = new ChunkPos(1, -1);
        try {
            saved(f, source, reload, richVillager(31.875, -0.125, -200));
            f.start(source, ChunkStatus.FULL); f.drain();
            var graph = f.runtime.graph(f.holder(source));
            assertEquals(FullInitializedInputs.State.SATISFIED, graph.inputEvaluation().state(), graph.inputEvaluation().obligation());
            assertEquals(Set.of(ChunkPos.ZERO, source), graph.inputReceipt().targets().stream()
                    .map(FullInitializedInputs.Target::position).collect(java.util.stream.Collectors.toSet()));
            assertTrue(target(graph, ChunkPos.ZERO).purposes().contains(FullInitializedInputs.Purpose.ENVIRONMENT));
            assertTrue(target(graph, source).purposes().containsAll(Set.of(
                    FullInitializedInputs.Purpose.SELF_INITIALIZED, FullInitializedInputs.Purpose.ENVIRONMENT)));
            assertTrue(graph.inputReceipt().targets().stream().noneMatch(t -> t.purposes().contains(FullInitializedInputs.Purpose.COLLISION)));
            assertEquals(0, f.world.nextId); assertTrue(f.world.inserted.isEmpty());
            assertEquals(RandomSource.create(73031L).nextLong(), f.world.random.nextLong());
            assertFalse(graph.conversion().isDone()); assertFalse(f.owner.leases().hasActiveLease());
            assertEquals(FullInitializedInputs.State.SATISFIED, graph.validateInputs(graph.finishing.stage(), graph.inputReceipt()).state());
        } finally { f.stop(); }
        assertCleanup(f);
    }

    @Test void passengerSavedPositionAndPositionAliasChangesDriveRediscoveryWithoutConstruction() throws Exception {
        var f = new FullRegistrationFixture(true);
        try {
            var root = villager(8, 8, 0); var passenger = richVillager(16.125, -0.125, -200);
            var passengers = new ListTag(); passengers.add(passenger); root.put("Passengers", passengers);
            saved(f, ChunkPos.ZERO, false, root); f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain();
            var graph = f.runtime.graph(f.holder(ChunkPos.ZERO)); var prior = graph.inputReceipt();
            assertEquals(Set.of(ChunkPos.ZERO, new ChunkPos(1, -1)), graph.inputReceipt().targets().stream()
                    .map(FullInitializedInputs.Target::position).collect(java.util.stream.Collectors.toSet()));
            passenger.getListOrEmpty("Pos").set(0, DoubleTag.valueOf(32.125));
            assertEquals(FullInitializedInputs.State.STALE, graph.validateInputs(graph.finishing.stage(), prior).state());
            f.drain(); assertNotSame(prior, graph.inputReceipt());
            assertEquals(Set.of(ChunkPos.ZERO, new ChunkPos(2, -1)), graph.inputReceipt().targets().stream()
                    .map(FullInitializedInputs.Target::position).collect(java.util.stream.Collectors.toSet()));
            assertEquals("root[0]/passenger[0]", graph.savedSnapshot().entities().get(1).path());
            assertEquals(0, f.world.nextId); assertTrue(f.world.inserted.isEmpty());
        } finally { f.stop(); }
        assertCleanup(f);
    }

    @Test void protectedSnapshotUsesOriginalDefaultsClampsAndNegativeSectionBoundary() {
        var missing = new CompoundTag(); missing.putString("id", "minecraft:villager");
        var snapshot = new FullSavedInput().capture(List.of(missing,
                villager(-0.00001, -16.00001, 0), villager(Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1)));
        assertNull(snapshot.unmet());
        assertEquals(Set.of(ChunkPos.ZERO), FullSavedVillagerInputs.environmentPositions(snapshot.entities().getFirst()));
        assertEquals(Set.of(ChunkPos.ZERO, new ChunkPos(-1, -2)), FullSavedVillagerInputs.environmentPositions(snapshot.entities().get(1)));
        assertEquals(3.0000512E7, snapshot.entities().get(2).x());
        assertEquals(-3.0000512E7, snapshot.entities().get(2).z());
        assertEquals(Set.of(ChunkPos.ZERO, new ChunkPos(1875032, -1875032)),
                FullSavedVillagerInputs.environmentPositions(snapshot.entities().get(2)));
    }

    @Test void invalidSavedPositionCannotProduceAnInitializationReceipt() throws Exception {
        var f = new FullRegistrationFixture(true);
        try {
            saved(f, ChunkPos.ZERO, false, villager(Double.NaN, 8, 0));
            f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain();
            var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
            assertEquals(FullInitializedInputs.State.UNRESOLVED, graph.inputEvaluation().state());
            assertTrue(graph.inputEvaluation().obligation().contains("invalid saved position"));
            assertNull(graph.inputReceipt()); assertEquals(0, f.world.nextId);
        } finally { f.stop(); }
        assertCleanup(f);
    }

    private static List<ChunkPos> installOriginalBiomeLayers(FullTadpoleDecisionTest.Fixture f) throws Exception {
        List<ChunkPos> observed = new ArrayList<>();
        var plains = f.level.registryAccess().lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
        var biomeManager = new BiomeManager((quartX, quartY, quartZ) -> {
            observed.add(new ChunkPos(QuartPos.toSection(quartX), QuartPos.toSection(quartZ)));
            return plains;
        }, 0L);
        var builder = EnvironmentAttributeSystem.builder();
        var original = EnvironmentAttributeSystem.class.getDeclaredMethod("addBiomeLayerForAttribute",
                EnvironmentAttributeSystem.Builder.class, EnvironmentAttribute.class, BiomeManager.class);
        original.setAccessible(true);
        original.invoke(null, builder, EnvironmentAttributes.VILLAGER_ACTIVITY, biomeManager);
        original.invoke(null, builder, EnvironmentAttributes.BABY_VILLAGER_ACTIVITY, biomeManager);
        f.level.setEnvironmentAttributes(builder.build());
        return observed;
    }

    private static final UUID GOSSIP_PLAYER = UUID.fromString("ed8bcb0d-24ea-49c9-9520-d9d8a88ca559");
    static CompoundTag villager(double x, double z, int age) {
        var tag = FullInitializedInputBindingTest.tag(x, z, age); tag.putString("id", "minecraft:villager"); return tag;
    }
    private static Brain.Packed memories() {
        return new Brain.Packed(new MemoryMap.Builder().add(MemoryModuleType.HOME,
                ExpirableValue.of(GlobalPos.of(Level.NETHER, new BlockPos(912, 83, -704)))).build());
    }
    static CompoundTag richVillager(double x, double z, int age) {
        var tag = villager(x, z, age);
        tag.store("Brain", Brain.Packed.CODEC, memories());
        tag.store("attributes", AttributeInstance.Packed.LIST_CODEC, List.of(new AttributeInstance.Packed(Attributes.SCALE, 3.0, List.of())));
        tag.store("active_effects", MobEffectInstance.CODEC.listOf(), List.of(new MobEffectInstance(MobEffects.SPEED, 1200, 1)));
        tag.store("sleeping_pos", BlockPos.CODEC, new BlockPos(2032, 75, -1104));
        tag.store("VillagerData", VillagerData.CODEC, new VillagerData(Villager.createDefaultVillagerData().type(),
                BuiltInRegistries.VILLAGER_PROFESSION.getOrThrow(VillagerProfession.FARMER), 3));
        tag.putInt("Xp", 81); tag.putInt("RestocksToday", 2); tag.putLong("LastRestock", 120L);
        tag.store("Inventory", ItemStack.CODEC.listOf(), List.of(new ItemStack(Items.BREAD, 7)));
        var offers = new MerchantOffers(); var offer = new MerchantOffer(new ItemCost(Items.EMERALD, 5), new ItemStack(Items.BREAD), 12, 2, 0.05F);
        offer.increaseUses(); offer.increaseUses(); offers.add(offer); tag.store("Offers", MerchantOffers.CODEC, offers);
        var gossip = new GossipContainer(); gossip.add(GOSSIP_PLAYER, GossipType.MINOR_POSITIVE, 4); tag.store("Gossips", GossipContainer.CODEC, gossip);
        return tag;
    }
}
