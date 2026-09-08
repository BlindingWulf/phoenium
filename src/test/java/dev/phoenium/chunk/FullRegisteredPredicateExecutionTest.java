package dev.phoenium.chunk;

import static dev.phoenium.chunk.FullDecisionWitnessSupport.set;
import static dev.phoenium.chunk.FullInitializedInputBindingTest.assertCleanup;
import static dev.phoenium.chunk.FullInitializedInputBindingTest.saved;
import static dev.phoenium.chunk.FullInitializedInputBindingTest.tag;
import static org.junit.jupiter.api.Assertions.*;

import com.mojang.brigadier.StringReader;
import com.mojang.serialization.Lifecycle;
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
import net.minecraft.server.ReloadableServerRegistries;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.entity.EntityLookup;
import net.minecraft.world.level.storage.CommandStorage;
import net.minecraft.world.level.storage.SavedDataStorage;
import net.minecraft.world.level.storage.loot.IntRange;
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue;
import net.minecraft.world.level.storage.loot.providers.number.StorageValue;
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

    @Test void registeredValueCheckRunsOriginalSelector() throws Exception {
        runRegistered(new ValueCheckCondition(ConstantValue.exactly(3), IntRange.exact(3)),
                "numeric candidate", (fixture, candidate) -> { });
    }

    @Test void registeredStorageValueCapturesBeforeOriginalSelectorWithoutEarlyStorageRead() throws Exception {
        SavedDataStorage[] dataHolder = new SavedDataStorage[1];
        try {
            var provider = new StorageValue(STORAGE,
                    net.minecraft.commands.arguments.NbtPathArgument.nbtPath().parse(new StringReader("value")));
            runRegistered(new ValueCheckCondition(provider, IntRange.exact(3)),
                    "storage candidate", (fixture, candidate) -> set(fixture.world.level.getServer(),
                            MinecraftServer.class, "commandStorage", storage(dataHolder, fixture)));
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
        runRegistered(new EnvironmentAttributeCheck<>(EnvironmentAttributes.VILLAGER_ACTIVITY,
                EnvironmentAttributes.VILLAGER_ACTIVITY.defaultValue()), "environment candidate", (fixture, candidate) -> { });
    }

    @FunctionalInterface
    private interface Setup { void install(FullRegistrationFixture fixture, Entity candidate) throws Exception; }

    private CommandStorage storage(SavedDataStorage[] dataHolder, FullRegistrationFixture fixture) {
        dataHolder[0] = new SavedDataStorage(directory, null, fixture.world.level.registryAccess());
        var storage = new CommandStorage(dataHolder[0]);
        var value = new CompoundTag(); value.putInt("value", 3); storage.set(STORAGE, value);
        return storage;
    }

    private static void runRegistered(LootItemCondition condition, String name, Setup setup) throws Exception {
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
            FullAuthoritativeFinishingTest.context(fixture); fixture.runtime.inputs.changed(); fixture.drain();
            assertInstanceOf(ChunkStageOutcome.Applied.class, graph.finishing.outcome().join());
            assertEquals(name, FullSavedContextTestHooks.text(
                    assertInstanceOf(Display.TextDisplay.class, fixture.world.inserted.getFirst())).getString());
            assertFalse(fixture.barrier.tripped());
        } finally {
            lookup.remove(candidate); fixture.stop(); FullFinishingServices.stopped(fixture.world.level.getServer());
        }
        assertCleanup(fixture);
    }
}
