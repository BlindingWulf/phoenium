package dev.phoenium.chunk;

import com.mojang.serialization.Lifecycle;
import dev.phoenium.core.TopologyGeneration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.advancements.predicates.BlockPredicate;
import net.minecraft.advancements.predicates.LocationPredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.ReloadableServerRegistries;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.entity.EntityLookup;
import net.minecraft.world.level.storage.loot.predicates.LocationCheck;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static dev.phoenium.chunk.FullDecisionWitnessSupport.*;
import static dev.phoenium.chunk.FullInitializedInputBindingTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Original registry predicate, Level block reader and cache selector/join use retained
 * preparations. The controlled world supplies generation outputs and candidate iteration. */
class FullLocationPredicateInputTest {
    @BeforeAll static void bootstrap() { FullSavedVillagerInputTest.bootstrap(); }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void originalBlockPredicateReadsPreparedNeighborAndKeepsPublicationSeparate(boolean blockNbt) throws Exception {
        run(blockNbt, false);
    }

    @Test void originalDimensionShortCircuitDoesNotReachThePreparedBlockGetter() throws Exception {
        run(false, true);
    }

    @Test void registeredEntityLocationReadsTheSameOriginalPreparedBlockAndNbt() throws Exception {
        run(true, false, true);
    }

    private static void run(boolean blockNbt, boolean wrongDimension) throws Exception {
        run(blockNbt, wrongDimension, false);
    }
    private static void run(boolean blockNbt, boolean wrongDimension, boolean entityProperty) throws Exception {
        var f = new FullRegistrationFixture(true);
        var lookup = new EntityLookup<Entity>();
        var neighbor = new ChunkPos(1, 0); var position = new BlockPos(24, 8, 8);
        var candidate = EntityTypes.TEXT_DISPLAY.create(f.world.level, EntitySpawnReason.LOAD);
        assertNotNull(candidate);
        candidate.setId(901); candidate.setPos(24, 8, 8); candidate.setCustomName(Component.literal("matched original block"));
        lookup.add(candidate); f.world.selectorEntities.add(candidate);
        try {
            var block = blockNbt ? Blocks.BEACON : Blocks.DIAMOND_BLOCK;
            f.input(neighbor).getSection(0).setBlockState(8, 8, 8, block.defaultBlockState());
            var blockBuilder = BlockPredicate.Builder.block().of(BuiltInRegistries.BLOCK, block);
            if (blockNbt) {
                var expected = new CompoundTag(); expected.putString("CustomName", "original beacon");
                blockBuilder.hasNbt(expected);
                var pending = new CompoundTag(); pending.putString("id", "minecraft:beacon");
                pending.putInt("x", 24); pending.putInt("y", 8); pending.putInt("z", 8);
                pending.putString("CustomName", "original beacon");
                f.input(neighbor).setBlockEntityNbt(pending);
            }
            var location = LocationPredicate.Builder.location().setBlock(blockBuilder);
            if (wrongDimension) location.setDimension(Level.NETHER);
            var predicates = new MappedRegistry<LootItemCondition>(Registries.PREDICATE, Lifecycle.stable());
            var id = Identifier.parse("phoenium:prepared_block");
            LootItemCondition condition = entityProperty
                    ? new net.minecraft.world.level.storage.loot.predicates.LootItemEntityPropertyCondition(
                            Optional.of(net.minecraft.advancements.predicates.entity.EntityPredicate.Builder.entity().located(location).build()),
                            net.minecraft.world.level.storage.loot.LootContext.EntityTarget.THIS)
                    : new LocationCheck(Optional.of(location.build()), BlockPos.ZERO);
            predicates.register(ResourceKey.create(Registries.PREDICATE, id), condition, RegistrationInfo.BUILT_IN);
            f.world.reloadableRegistries.set(new ReloadableServerRegistries.Holder(
                    new RegistryAccess.ImmutableRegistryAccess(List.of(predicates)).freeze()));

            var selected = new CompoundTag();
            selected.putString("selector", "@e[type=minecraft:text_display,distance=..32,predicate=phoenium:prepared_block,limit=1]");
            var root = tag(8, 8, 0); root.putString("id", "minecraft:text_display"); root.put("text", selected);
            saved(f, ChunkPos.ZERO, false, root);
            f.holder(neighbor); // Actual demand keeps the controlled visible holder, as in the existing query fixtures.
            f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain();
            var source = f.runtime.graph(f.holder(ChunkPos.ZERO));
            assertEquals(FullInitializedInputs.State.SATISFIED, source.inputEvaluation().state(), source.inputEvaluation().obligation());
            var target = target(source, neighbor);
            assertEquals(FullInitializedInputs.Availability.PREPARED, target.availability());
            assertFalse(target.publicSlot().isDone());
            assertTrue(target.actual().getBlockEntities().isEmpty(), "discovery must not perform the original IMMEDIATE creation");

            FullFinishingQueriesTest.installGetterAndContext(f);
            var getters = new AtomicInteger();
            f.world.queryEndpoint = args -> {
                getters.incrementAndGet();
                assertTrue(FullFinishingServices.finishing());
                assertFalse(target.publicSlot().isDone(), "neighbor FULL stays unpublished during the original predicate");
                return f.world.cache.getChunk((int) args[0], (int) args[1], (ChunkStatus) args[2], (boolean) args[3]);
            };
            source.finishing.prepareFullServices();
            var result = new ChunkStageExecutor(new ChunkStageAdmission(f.services.custody(), f.directory,
                    Map.of(f.ownerId, f.owner.leases()), f.barrier)).execute(source.finishing, f.ownerId, new TopologyGeneration(1));
            assertEquals(ChunkStageAdmission.Result.APPLIED, result,
                    () -> f.barrier.record().map(record -> record.cause().toString()).orElse("original FULL failed"));
            assertEquals(wrongDimension ? 0 : blockNbt ? 2 : 1, getters.get());
            var display = assertInstanceOf(Display.TextDisplay.class, f.world.inserted.getFirst());
            assertEquals(wrongDimension ? "" : "matched original block", FullSavedContextTestHooks.text(display).getString());
            assertFalse(source.adjacent().isDone(), "publication is still a separate pending stage");
            assertFalse(target.publicSlot().isDone());
            assertEquals(blockNbt && !wrongDimension, target.actual().getBlockEntities().containsKey(position));
            assertTrue(java.util.Arrays.stream((ChunkAccess[]) field(f.world.cache, ServerChunkCache.class, "lastChunk"))
                    .noneMatch(value -> value == target.actual()), "the private preparation must not enter the ordinary cache");
            f.world.queryEndpoint = args -> f.world.cache.getChunk((int) args[0], (int) args[1], (ChunkStatus) args[2], (boolean) args[3]);
            f.runtime.inputs.changed(); f.drain();
            assertTrue(source.adjacent().isDone()); assertFalse(f.barrier.tripped());
        } finally {
            lookup.remove(candidate); f.stop(); FullFinishingServices.stopped(f.world.level.getServer());
        }
        assertCleanup(f);
    }
}
