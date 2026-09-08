package dev.phoenium.chunk;

import static dev.phoenium.chunk.FullDecisionWitnessSupport.*;
import static dev.phoenium.chunk.FullInitializedInputBindingTest.*;
import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.Lifecycle;
import dev.phoenium.core.TopologyGeneration;
import java.util.*;
import net.minecraft.advancements.predicates.LocationPredicate;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.*;
import net.minecraft.server.ReloadableServerRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.entity.EntityLookup;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.structures.DesertPyramidPiece;
import net.minecraft.world.level.levelgen.structure.structures.DesertPyramidStructure;
import net.minecraft.world.level.storage.loot.predicates.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Original registered selector -> LocationPredicate -> StructureManager -> original cache.
 * Only world registration, candidate enumeration and settled generation outputs are controlled. */
class FullStructureInputTest {
    @BeforeAll static void bootstrap() { FullSavedVillagerInputTest.bootstrap(); }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void originalStructureQueryFollowsExactLowerStatusReferenceAndRebuildsChangedAliases(boolean replace) throws Exception {
        run(replace, false);
    }
    @org.junit.jupiter.api.Test void absentReferencedStartLoadsAtItsOriginalLowerStatusBeforeClaim() throws Exception {
        run(false, true);
    }
    private static void run(boolean replace, boolean absent) throws Exception {
        var f = new FullRegistrationFixture(true);
        var lookup = new EntityLookup<Entity>();
        var candidate = EntityTypes.TEXT_DISPLAY.create(f.world.level, EntitySpawnReason.LOAD);
        assertNotNull(candidate); candidate.setId(906); candidate.setPos(8, 8, 8);
        candidate.setCustomName(Component.literal("original structure")); lookup.add(candidate);
        f.world.selectorEntities.add(candidate);
        try {
            var structure = new DesertPyramidStructure(new Structure.StructureSettings(HolderSet.empty()));
            var registry = new MappedRegistry<Structure>(Registries.STRUCTURE, Lifecycle.stable());
            var structureKey = ResourceKey.create(Registries.STRUCTURE, Identifier.parse("phoenium:retained_structure"));
            var structureHolder = registry.register(structureKey, structure, RegistrationInfo.BUILT_IN);
            var structureRegistry = new RegistryAccess.ImmutableRegistryAccess(List.of(registry)).freeze();
            var manager = new StructureManager(f.world.level, new WorldOptions(0L, true, false), null) {
                @Override public RegistryAccess registryAccess() { return structureRegistry; }
            };
            set(f.world.level, ServerLevel.class, "structureManager", manager);
            var firstStart = new ChunkPos(absent ? 20 : 8, 0); var secondStart = new ChunkPos(9, 0);
            putStart(f, firstStart, structure); putStart(f, secondStart, structure);
            f.input(ChunkPos.ZERO).addReferenceForStructure(structure, firstStart.pack());
            var distanceUpdates = new java.util.concurrent.atomic.AtomicInteger();
            if (absent) {
                var distance = stub(net.minecraft.server.level.DistanceManager.class, List.of(new Answer(
                        net.minecraft.server.level.DistanceManager.class.getMethod("runAllUpdates", net.minecraft.server.level.ChunkMap.class), args -> {
                            assertTrue(dev.phoenium.ownership.OwnershipContext.current().isEmpty());
                            assertFalse(FullFinishingServices.finishing()); distanceUpdates.incrementAndGet();
                            f.holder(firstStart).eligibility(net.minecraft.server.level.ChunkLevel.byStatus(ChunkStatus.STRUCTURE_STARTS), f.fixtureMap);
                            return true;
                        })));
                set(f.world.cache, net.minecraft.server.level.ServerChunkCache.class, "distanceManager", distance);
            } else f.holder(firstStart);
            f.holder(secondStart);
            var location = LocationPredicate.Builder.location().setStructures(HolderSet.direct(structureHolder)).build();
            var predicates = new MappedRegistry<LootItemCondition>(Registries.PREDICATE, Lifecycle.stable());
            var predicateId = Identifier.parse("phoenium:structure");
            predicates.register(ResourceKey.create(Registries.PREDICATE, predicateId),
                    new LocationCheck(Optional.of(location), BlockPos.ZERO), RegistrationInfo.BUILT_IN);
            f.world.reloadableRegistries.set(new ReloadableServerRegistries.Holder(
                    new RegistryAccess.ImmutableRegistryAccess(List.of(predicates)).freeze()));
            var selected = new CompoundTag();
            selected.putString("selector", "@e[type=minecraft:text_display,distance=..32,predicate=phoenium:structure,limit=1]");
            var root = tag(8, 8, 0); root.putString("id", "minecraft:text_display"); root.put("text", selected);
            saved(f, ChunkPos.ZERO, false, root);
            f.start(ChunkPos.ZERO, ChunkStatus.FULL); f.drain();
            var graph = f.runtime.graph(f.holder(ChunkPos.ZERO));
            assertEquals(FullInitializedInputs.State.SATISFIED, graph.inputEvaluation().state(), graph.inputEvaluation().obligation());
            var firstReceipt = graph.inputReceipt();
            var lower = target(graph, firstStart);
            assertEquals(FullInitializedInputs.Availability.STRUCTURES, lower.availability());
            assertNotNull(lower.structureStarts()); assertNull(lower.publicSlot());
            assertNull(lower.actual(), "structure references must not demand a FULL conversion");
            assertEquals(Set.of(firstStart), firstReceipt.structures().starts());
            assertEquals(absent ? 1 : 0, distanceUpdates.get());
            assertEquals(absent ? 1 : 0, graph.structureDemand.size());
            if (absent) assertTrue(f.world.tickets.getTickets(firstStart.pack()).stream().allMatch(ticket ->
                    ticket.getType() != net.minecraft.server.level.TicketType.UNKNOWN && !ticket.getType().persist()
                            && !ticket.getType().doesSimulate()));
            if (replace) {
                var actual = graph.prepared().orElseThrow().actual();
                actual.getReferencesForStructure(structure).clear();
                actual.addReferenceForStructure(structure, secondStart.pack());
            }
            FullFinishingQueriesTest.installGetterAndContext(f);
            var calls = new ArrayList<ChunkStatus>();
            f.world.queryEndpoint = args -> {
                assertTrue(FullFinishingServices.finishing());
                var status = (ChunkStatus) args[2];
                assertTrue(status == ChunkStatus.STRUCTURE_REFERENCES || status == ChunkStatus.STRUCTURE_STARTS);
                assertTrue((boolean) args[3]); calls.add(status);
                return f.world.cache.getChunk((int) args[0], (int) args[1], status, (boolean) args[3]);
            };
            if (replace) {
                graph.finishing.prepareFullServices();
                var result = execute(f, graph);
                assertNotEquals(ChunkStageAdmission.Result.APPLIED, result);
                assertFalse(graph.finishing.outcome().isDone()); assertTrue(f.world.inserted.isEmpty()); assertTrue(calls.isEmpty());
                f.runtime.inputs.changed(); f.runtime.inputs.pump(); f.drain();
                assertInstanceOf(ChunkStageOutcome.Applied.class, graph.finishing.outcome().join());
                assertNotSame(firstReceipt, graph.inputReceipt());
            } else {
                graph.finishing.prepareFullServices();
                assertEquals(ChunkStageAdmission.Result.APPLIED, execute(f, graph),
                        () -> f.barrier.record().map(record -> record.cause().toString()).orElse("FULL did not apply"));
                assertFalse(graph.adjacent().isDone(), "original publication remains separately admitted");
            }
            assertEquals(List.of(ChunkStatus.STRUCTURE_REFERENCES, ChunkStatus.STRUCTURE_STARTS), calls);
            assertEquals("original structure", FullSavedContextTestHooks.text(
                    assertInstanceOf(Display.TextDisplay.class, f.world.inserted.getFirst())).getString());
            var reached = replace ? secondStart : firstStart;
            assertTrue(f.world.tickets.getTickets(reached.pack()).stream().anyMatch(ticket ->
                    ticket.getType() == net.minecraft.server.level.TicketType.UNKNOWN
                            && ticket.getTicketLevel() == net.minecraft.server.level.ChunkLevel.byStatus(ChunkStatus.STRUCTURE_STARTS)));
            assertEquals(0L, f.runtime.structureMutations.measurements().get("readers"));
            assertEquals(0, graph.structureDemand.size());
            assertTrue(f.world.tickets.getTickets(reached.pack()).stream().allMatch(ticket ->
                    ticket.getType() == net.minecraft.server.level.TicketType.UNKNOWN), "only the original reached ticket remains");
            assertFalse(f.barrier.tripped());
        } finally {
            lookup.remove(candidate); f.stop(); FullFinishingServices.stopped(f.world.level.getServer());
        }
        assertCleanup(f);
    }
    private static ChunkStageAdmission.Result execute(FullRegistrationFixture f, FullRegistrationRuntime.Graph graph) {
        return new ChunkStageExecutor(new ChunkStageAdmission(f.services.custody(), f.directory,
                Map.of(f.ownerId, f.owner.leases()), f.barrier)).execute(graph.finishing, f.ownerId, new TopologyGeneration(1));
    }
    private static void putStart(FullRegistrationFixture f, ChunkPos position, Structure structure) throws Exception {
        var piece = new DesertPyramidPiece(RandomSource.create(0L), 0, 0);
        set(piece, StructurePiece.class, "boundingBox", new BoundingBox(0, 0, 0, 15, 15, 15));
        f.input(position).setStartForStructure(structure, new StructureStart(structure, position, 0, new PiecesContainer(List.of(piece))));
    }
}
