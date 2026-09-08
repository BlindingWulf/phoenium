package dev.phoenium.chunk;

import static dev.phoenium.chunk.FullDecisionWitnessSupport.set;
import static dev.phoenium.chunk.FullDecisionWitnessSupport.allocate;
import static dev.phoenium.chunk.FullInitializedInputBindingTest.assertCleanup;
import static dev.phoenium.chunk.FullInitializedInputBindingTest.saved;
import static dev.phoenium.chunk.FullInitializedInputBindingTest.tag;
import static org.junit.jupiter.api.Assertions.*;

import com.mojang.brigadier.StringReader;
import com.mojang.serialization.Lifecycle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.ReloadableServerRegistries;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.entity.EntityLookup;
import net.minecraft.world.level.storage.CommandStorage;
import net.minecraft.world.level.storage.SavedDataStorage;
import net.minecraft.world.level.storage.loot.IntRange;
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue;
import net.minecraft.world.level.storage.loot.providers.number.StorageValue;
import net.minecraft.world.level.storage.loot.providers.number.Sum;
import net.minecraft.world.level.storage.loot.predicates.EntityHasScoreCondition;
import net.minecraft.world.level.storage.loot.predicates.EnvironmentAttributeCheck;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.level.storage.loot.predicates.ValueCheckCondition;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real registered selector execution after immutable predicate dependency discovery. */
final class FullRegisteredPredicateExecutionTest {
    private static final Identifier PREDICATE = Identifier.parse("phoenium:registered_numeric");
    private static final Identifier STORAGE = Identifier.parse("phoenium:registered_numeric_storage");

    @TempDir Path directory;

    @BeforeAll static void bootstrap() { FullTadpoleDecisionTest.bootstrap(); }

    /** Invoked only by the disposable installed registration harness. */
    static int runInstalled() throws Exception {
        FullTadpoleDecisionTest.bootstrap();
        var test = new FullRegisteredPredicateExecutionTest();
        test.directory = Files.createTempDirectory("phoenium-installed-registered-predicates");
        test.registeredValueCheckRunsOriginalSelector();
        test.registeredNestedStorageValueCapturesBeforeOriginalSelectorWithoutEarlyStorageRead();
        test.registeredEntityScoreUsesOriginalScoreboardContext();
        test.registeredPositionalEnvironmentCheckUsesOriginalEnvironmentContext();
        test.registeredRandomChanceUsesExactlyOneOriginalRandomDraw();
        return 5;
    }

    @Test void registeredValueCheckRunsOriginalSelector() throws Exception {
        runRegistered(new ValueCheckCondition(ConstantValue.exactly(3), IntRange.exact(3)),
                "numeric candidate", (fixture, candidate) -> { });
    }

    @Test void registeredNestedStorageValueCapturesBeforeOriginalSelectorWithoutEarlyStorageRead() throws Exception {
        SavedDataStorage[] dataHolder = new SavedDataStorage[1];
        CountingCommandStorage[] storageHolder = new CountingCommandStorage[1];
        try {
            var nested = new StorageValue(STORAGE,
                    net.minecraft.commands.arguments.NbtPathArgument.nbtPath().parse(new StringReader("value")));
            var provider = Sum.sum(ConstantValue.exactly(1), nested);
            runRegistered(new ValueCheckCondition(provider, IntRange.exact(3)),
                    "storage candidate", (fixture, candidate) -> set(fixture.world.level.getServer(),
                            MinecraftServer.class, "commandStorage", storage(dataHolder, storageHolder, fixture)),
                    (fixture, candidate) -> {
                        assertNotNull(storageHolder[0]);
                        assertEquals(1, storageHolder[0].discoveryGets,
                                "storage capture must perform one owner-free discovery read");
                        assertEquals(1, storageHolder[0].originalGets,
                                "the original selector must perform one finishing read");
                    });
        } finally { if (dataHolder[0] != null) dataHolder[0].close(); }
    }

    @Test void registeredEntityScoreUsesOriginalScoreboardContext() throws Exception {
        runRegistered(new EntityHasScoreCondition(Map.of("numeric", IntRange.exact(3)),
                net.minecraft.world.level.storage.loot.LootContext.EntityTarget.THIS), "score candidate",
                (fixture, candidate) -> {
                    var objective = fixture.world.scoreboard.addObjective("numeric", ObjectiveCriteria.DUMMY,
                            Component.literal("numeric"), ObjectiveCriteria.RenderType.INTEGER, false, null);
                    fixture.world.scoreboard.getOrCreatePlayerScore(candidate, objective).set(3);
                });
    }

    @Test void registeredPositionalEnvironmentCheckUsesOriginalEnvironmentContext() throws Exception {
        assertTrue(EnvironmentAttributes.VILLAGER_ACTIVITY.isPositional());
        runRegistered(new EnvironmentAttributeCheck<>(EnvironmentAttributes.VILLAGER_ACTIVITY,
                EnvironmentAttributes.VILLAGER_ACTIVITY.defaultValue()), "environment candidate",
                (fixture, candidate) -> { }, FullRegisteredPredicateExecutionTest::installBiomeEnvironment,
                (fixture, candidate) -> assertEquals(1, fixture.world.biomeReads,
                        "original positional environment sampler must consume one retained BIOMES route"));
    }

    @Test void registeredRandomChanceUsesExactlyOneOriginalRandomDraw() throws Exception {
        CountingRandomSource[] randomHolder = new CountingRandomSource[1];
        runRegistered(new net.minecraft.world.level.storage.loot.predicates.LootItemRandomChanceCondition(
                        ConstantValue.exactly(1)), "random candidate", (fixture, candidate) -> {
                    var random = new CountingRandomSource(RandomSource.create(9917L)); randomHolder[0] = random;
                    // The allocated fixture's getRandom answer closes over this field, while
                    // the ordinary Level field is bypassed by the controlled endpoint.
                    set(fixture.world, FullTadpoleDecisionTest.Fixture.class, "random", random);
                }, (fixture, candidate) -> {
                    assertNotNull(randomHolder[0]);
                    assertEquals(1, randomHolder[0].floatDraws,
                            "the original random-chance predicate must draw exactly once");
                });
    }

    @FunctionalInterface
    interface Setup { void install(FullRegistrationFixture fixture, Entity candidate) throws Exception; }

    private CommandStorage storage(SavedDataStorage[] dataHolder, CountingCommandStorage[] storageHolder,
                                   FullRegistrationFixture fixture) {
        dataHolder[0] = new SavedDataStorage(directory, null, fixture.world.level.registryAccess());
        var storage = new CountingCommandStorage(dataHolder[0]); storageHolder[0] = storage;
        var value = new CompoundTag(); value.putInt("value", 2); storage.set(STORAGE, value);
        return storage;
    }

    private static void installBiomeEnvironment(FullRegistrationFixture fixture, Entity ignored) throws Exception {
        // Use vanilla's original biome layer and LevelReader route. The layer must reach
        // LevelReader.getNoiseBiome -> getChunk(BIOMES,false), rather than a test BiomeManager
        // lambda that could return a biome without consuming the retained BIOMES input.
        var biomeManager = new net.minecraft.world.level.biome.BiomeManager(fixture.world.level::getNoiseBiome, 0L);
        var builder = EnvironmentAttributeSystem.builder();
        var original = EnvironmentAttributeSystem.class.getDeclaredMethod("addBiomeLayerForAttribute",
                EnvironmentAttributeSystem.Builder.class, net.minecraft.world.attribute.EnvironmentAttribute.class,
                net.minecraft.world.level.biome.BiomeManager.class);
        original.setAccessible(true);
        original.invoke(null, builder, EnvironmentAttributes.VILLAGER_ACTIVITY, biomeManager);
        var graph = fixture.runtime.graph(fixture.holder(ChunkPos.ZERO));
        var biomeTarget = graph.inputReceipt().targets().stream()
                .filter(target -> target.purposes().contains(FullInitializedInputs.Purpose.ENVIRONMENT))
                .findFirst().orElseThrow();
        assertNotNull(biomeTarget.biomes(), "registered positional environment must retain a BIOMES input");
        set(fixture.world.cache, ServerChunkCache.class, "level", fixture.world.level);
        set(fixture.world.cache, ServerChunkCache.class, "mainThreadProcessor",
                allocate(Class.forName("net.minecraft.server.level.ServerChunkCache$MainThreadExecutor")));
        fixture.world.queryEndpoint = args -> {
            if (args[2] == ChunkStatus.BIOMES) {
                assertFalse((boolean) args[3], "environment route must preserve LevelReader's BIOMES,false request");
                var cached = fixture.world.cache.getChunk((int) args[0], (int) args[1],
                        (ChunkStatus) args[2], (boolean) args[3]);
                assertSame(biomeTarget.biomes().actual(), cached,
                        "original ServerChunkCache must return the retained BIOMES object");
                fixture.world.biomeReads++;
                var biome = cached.getNoiseBiome(0, 0, 0);
                assertEquals(net.minecraft.world.level.biome.Biomes.PLAINS,
                        biome.unwrapKey().orElseThrow(), "the retained original biome value must be consumed");
                return cached;
            }
            return fixture.world.borrow(fixture.world.work, (int) args[0], (int) args[1]);
        };
        fixture.world.level.setEnvironmentAttributes(builder.build());
    }

    @FunctionalInterface
    interface Verify { void verify(FullRegistrationFixture fixture, Entity candidate) throws Exception; }

    static void runRegistered(LootItemCondition condition, String name, Setup setup) throws Exception {
        runRegistered(condition, name, setup, (fixture, candidate) -> { });
    }

    static void runRegistered(LootItemCondition condition, String name, Setup setup, Verify verify) throws Exception {
        runRegistered(condition, name, setup, null, verify);
    }

    static void runRegistered(LootItemCondition condition, String name, Setup setup,
                                      Setup afterContext, Verify verify) throws Exception {
        var fixture = new FullRegistrationFixture(true);
        var lookup = new EntityLookup<Entity>();
        var candidate = EntityTypes.TEXT_DISPLAY.create(fixture.world.level, EntitySpawnReason.LOAD);
        assertNotNull(candidate);
        candidate.setId(1301); candidate.setPos(8, 8, 8); candidate.setCustomName(Component.literal(name));
        lookup.add(candidate); fixture.world.selectorEntities.add(candidate);
        try {
            setup.install(fixture, candidate);
            var predicates = new MappedRegistry<LootItemCondition>(Registries.PREDICATE, Lifecycle.stable());
            predicates.register(ResourceKey.create(Registries.PREDICATE, PREDICATE), condition, RegistrationInfo.BUILT_IN);
            fixture.world.reloadableRegistries.set(new ReloadableServerRegistries.Holder(
                    new net.minecraft.core.RegistryAccess.ImmutableRegistryAccess(List.of(predicates)).freeze()));
            var selector = new CompoundTag();
            selector.putString("selector", "@e[type=minecraft:text_display,distance=..32,predicate="
                    + PREDICATE + ",limit=1]");
            var root = tag(8, 8, 0); root.putString("id", "minecraft:text_display"); root.put("text", selector);
            saved(fixture, ChunkPos.ZERO, false, root);
            fixture.start(ChunkPos.ZERO, ChunkStatus.FULL); fixture.drain();
            var graph = fixture.runtime.graph(fixture.holder(ChunkPos.ZERO));
            assertEquals(FullInitializedInputs.State.SATISFIED, graph.inputEvaluation().state(),
                    graph.inputEvaluation().obligation());
            FullAuthoritativeFinishingTest.context(fixture);
            if (afterContext != null) afterContext.install(fixture, candidate);
            fixture.runtime.inputs.changed(); fixture.drain();
            assertInstanceOf(ChunkStageOutcome.Applied.class, graph.finishing.outcome().join());
            assertEquals(name, FullSavedContextTestHooks.text(
                    assertInstanceOf(Display.TextDisplay.class, fixture.world.inserted.getFirst())).getString());
            assertFalse(fixture.barrier.tripped());
            verify.verify(fixture, candidate);
        } finally {
            lookup.remove(candidate); fixture.stop(); FullFinishingServices.stopped(fixture.world.level.getServer());
        }
        assertCleanup(fixture);
    }

    private static final class CountingCommandStorage extends CommandStorage {
        int discoveryGets;
        int originalGets;

        CountingCommandStorage(SavedDataStorage storage) { super(storage); }

        @Override public CompoundTag get(Identifier id) {
            if (FullFinishingServices.finishing()) originalGets++;
            else discoveryGets++;
            return super.get(id);
        }
    }

    private static final class CountingRandomSource implements RandomSource {
        private final RandomSource delegate;
        int floatDraws;
        CountingRandomSource(RandomSource delegate) { this.delegate = delegate; }
        @Override public RandomSource fork() { return new CountingRandomSource(delegate.fork()); }
        @Override public PositionalRandomFactory forkPositional() { return delegate.forkPositional(); }
        @Override public void setSeed(long seed) { delegate.setSeed(seed); }
        @Override public int nextInt() { return delegate.nextInt(); }
        @Override public int nextInt(int bound) { return delegate.nextInt(bound); }
        @Override public long nextLong() { return delegate.nextLong(); }
        @Override public boolean nextBoolean() { return delegate.nextBoolean(); }
        @Override public float nextFloat() { floatDraws++; return delegate.nextFloat(); }
        @Override public double nextDouble() { return delegate.nextDouble(); }
        @Override public double nextGaussian() { return delegate.nextGaussian(); }
    }
}
