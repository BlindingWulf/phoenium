package dev.phoenium.chunk;

import com.mojang.serialization.Lifecycle;
import dev.phoenium.mixin.RegionalServerChunkCacheAccess;
import dev.phoenium.ownership.OwnershipContext;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.*;
import net.minecraft.util.RandomSource;
import net.minecraft.util.StaticCache2D;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.status.*;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.structure.*;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.structures.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static dev.phoenium.chunk.FullDecisionWitnessSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class NaturalSpawnStructureQueryTest {
    @BeforeAll static void bootstrap() { FullProductionRegistrationTest.bootstrap(); }
    static final class Fixture {
        final FullRegistrationFixture f = new FullRegistrationFixture(true);
        final LevelChunk source = new LevelChunk(f.world.level, f.world.proto, null);
        final Map<ChunkPos, CompletableFuture<ChunkResult<ChunkAccess>>> delayed = new HashMap<>();
        final List<String> requests = new ArrayList<>();
        final AtomicBoolean valid = new AtomicBoolean(true);
        final WeightedList<MobSpawnSettings.SpawnerData> fallback = spawns(EntityTypes.PIG);
        Fixture() throws Exception {
            var cache = stub(ServerChunkCache.class, List.of(new Answer(RegionalServerChunkCacheAccess.class.getMethod(
                    "phoenium$naturalSpawnFuture", int.class, int.class, ChunkStatus.class, boolean.class), args -> {
                assertTrue(OwnershipContext.current().isEmpty()); assertFalse(f.owner.leases().hasActiveLease());
                var pos = new ChunkPos((int) args[0], (int) args[1]); f.holder(pos);
                requests.add(pos.x() + "," + pos.z() + ":" + ((ChunkStatus) args[2]).getName());
                return delayed.getOrDefault(pos, CompletableFuture.completedFuture(ChunkResult.of(f.input(pos))));
            })), RegionalServerChunkCacheAccess.class);
            set(cache, ServerChunkCache.class, "mainThread", Thread.currentThread());
            set(cache, ServerChunkCache.class, "chunkMap", f.fixtureMap);
            set(f.world.level, ServerLevel.class, "chunkSource", cache);
            set(f.holder(ChunkPos.ZERO), ChunkHolder.class, "tickingChunkFuture", CompletableFuture.completedFuture(ChunkResult.of(source)));
        }
        NaturalSpawnStructureQuery query(boolean fortress) {
            return new NaturalSpawnStructureQuery(f.runtime, source, new BlockPos(8, 8, 8), MobCategory.MONSTER, fortress, fallback, valid::get);
        }
        Structure structure(EntityType<?> mob, StructureSpawnOverride.BoundingBoxType type) {
            return new DesertPyramidStructure(new Structure.StructureSettings(HolderSet.empty(),
                Map.of(MobCategory.MONSTER, new StructureSpawnOverride(type, spawns(mob))),
                GenerationStep.Decoration.SURFACE_STRUCTURES, TerrainAdjustment.NONE));
        }
        StructureStart start(Structure structure, ChunkPos at, BoundingBox box) throws Exception {
            var piece = new DesertPyramidPiece(RandomSource.create(0L), 0, 0);
            set(piece, StructurePiece.class, "boundingBox", box);
            var start = new StructureStart(structure, at, 0, new PiecesContainer(List.of(piece)));
            f.input(at).setStartForStructure(structure, start); f.holder(at); return start;
        }
        void references(Structure structure, ChunkPos... positions) {
            var refs = new it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet();
            for (var pos : positions) refs.add(pos.pack());
            ((FullStructureInputs.ChunkView) f.world.proto).phoenium$structureReferences().put(structure, refs);
        }
        void fortress(Structure structure) throws Exception {
            var registry = new MappedRegistry<Structure>(Registries.STRUCTURE, Lifecycle.stable());
            registry.register(BuiltinStructures.FORTRESS, structure, RegistrationInfo.BUILT_IN);
            var access = new RegistryAccess.ImmutableRegistryAccess(List.of(registry)).freeze();
            set(f.world.level, ServerLevel.class, "structureManager", new StructureManager(f.world.level, new WorldOptions(0, true, false), null) {
                @Override public RegistryAccess registryAccess() { return access; }
            });
        }
    }
    static WeightedList<MobSpawnSettings.SpawnerData> spawns(EntityType<?> type) {
        return WeightedList.of(new MobSpawnSettings.SpawnerData(type, 1, 1));
    }
    @Test void noReferencesPublishesImmutableBiomeFallbackWithoutPolling() throws Exception {
        var f = new Fixture(); try (var query = f.query(false)) {
            query.prepare(); assertTrue(query.dependency().isDone());
            assertEquals(f.fallback, query.snapshot().spawns()); assertFalse(query.snapshot().fortress());
            assertEquals(List.of("0,0:minecraft:structure_references"), f.requests);
            assertFalse(f.f.owner.leases().hasActiveLease());
        }
    }
    @Test void exactOverrideOrderStopsDemandAfterTheFirstMatchingStructure() throws Exception {
        var f = new Fixture(); var first = f.structure(EntityTypes.ZOMBIE, StructureSpawnOverride.BoundingBoxType.STRUCTURE);
        var later = f.structure(EntityTypes.SKELETON, StructureSpawnOverride.BoundingBoxType.PIECE);
        var a = new ChunkPos(8, 0); var b = new ChunkPos(9, 0);
        f.start(first, a, new BoundingBox(0, 0, 0, 15, 15, 15)); f.start(later, b, new BoundingBox(0, 0, 0, 15, 15, 15));
        var refs = new LinkedHashMap<Structure, it.unimi.dsi.fastutil.longs.LongSet>();
        refs.put(first, new it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet(new long[] {a.pack()}));
        refs.put(later, new it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet(new long[] {b.pack()}));
        set(f.f.world.proto, ChunkAccess.class, "structuresRefences", refs);
        try (var query = f.query(false)) {
            query.prepare(); assertEquals(spawns(EntityTypes.ZOMBIE), query.snapshot().spawns());
            assertEquals(List.of("0,0:minecraft:structure_references", "8,0:minecraft:structure_starts"), f.requests);
            assertEquals(List.of(first, later), query.snapshot().references().stream().map(NaturalSpawnStructureQuery.ReferenceFact::structure).toList());
        }
    }
    @Test void copiedPieceFactsSurviveMutationAndDoNotWarmAggregateBox() throws Exception {
        var f = new Fixture(); var structure = f.structure(EntityTypes.ZOMBIE, StructureSpawnOverride.BoundingBoxType.PIECE);
        var at = new ChunkPos(8, 0); var box = new BoundingBox(0, 0, 0, 15, 15, 15);
        StructureStart start = f.start(structure, at, box); f.references(structure, at);
        try (var query = f.query(false)) {
            query.prepare(); var fact = query.snapshot().starts().getFirst();
            assertNull(fact.box()); assertTrue(fact.contains(new BlockPos(8, 8, 8), true));
            box.move(100, 0, 0);
            assertTrue(fact.contains(new BlockPos(8, 8, 8), true));
            assertFalse(start.getBoundingBox().isInside(new BlockPos(8, 8, 8)), "piece-only query must not eagerly cache a structure box");
            assertThrows(UnsupportedOperationException.class, () -> fact.pieces().clear());
        }
    }
    @Test void matchingOverrideStillWaitsForRemainingReferencesButDoesNotObserveTheirBoxes() throws Exception {
        var f = new Fixture(); var structure = f.structure(EntityTypes.ZOMBIE, StructureSpawnOverride.BoundingBoxType.STRUCTURE);
        var a = new ChunkPos(8, 0); var b = new ChunkPos(9, 0);
        f.start(structure, a, new BoundingBox(0, 0, 0, 15, 15, 15));
        var box = new BoundingBox(0, 0, 0, 15, 15, 15); var later = f.start(structure, b, box);
        f.references(structure, a, b); var pending = new CompletableFuture<ChunkResult<ChunkAccess>>(); f.delayed.put(b, pending);
        try (var query = f.query(false)) {
            query.prepare(); assertFalse(query.dependency().isDone());
            pending.complete(ChunkResult.of(f.f.input(b))); query.prepare();
            assertEquals(spawns(EntityTypes.ZOMBIE), query.snapshot().spawns());
            assertNull(query.snapshot().starts().getLast().box());
            box.move(100, 0, 0); assertFalse(later.getBoundingBox().isInside(new BlockPos(8, 8, 8)));
        }
    }
    @Test void fortressDefersBoundingObservationUntilAllNativeStartsAreLoaded() throws Exception {
        var f = new Fixture(); var structure = f.structure(EntityTypes.ZOMBIE, StructureSpawnOverride.BoundingBoxType.STRUCTURE);
        f.fortress(structure); var a = new ChunkPos(8, 0); var b = new ChunkPos(9, 0);
        var box = new BoundingBox(0, 0, 0, 15, 15, 15);
        f.start(structure, a, box); f.start(structure, b, new BoundingBox(100, 0, 0, 115, 15, 15)); f.references(structure, a, b);
        var pending = new CompletableFuture<ChunkResult<ChunkAccess>>(); f.delayed.put(b, pending);
        try (var query = f.query(true)) {
            query.prepare(); assertFalse(query.dependency().isDone());
            box.move(100, 0, 0); pending.complete(ChunkResult.of(f.f.input(b))); query.prepare();
            assertFalse(query.snapshot().fortress()); assertEquals(f.fallback, query.snapshot().spawns());
        }
    }
    @Test void fortressMatchReturnsNativeFortressWeightedList() throws Exception {
        var f = new Fixture(); var structure = f.structure(EntityTypes.ZOMBIE, StructureSpawnOverride.BoundingBoxType.STRUCTURE);
        f.fortress(structure); var a = new ChunkPos(8, 0); f.start(structure, a, new BoundingBox(0, 0, 0, 15, 15, 15)); f.references(structure, a);
        try (var query = f.query(true)) {
            query.prepare(); assertTrue(query.snapshot().fortress()); assertEquals(NetherFortressStructure.FORTRESS_ENEMIES, query.snapshot().spawns());
        }
    }
    @Test void sourceAndDependencyReplacementRetireWithoutPublishing() throws Exception {
        for (boolean source : List.of(false, true)) {
            var f = new Fixture(); var structure = f.structure(EntityTypes.ZOMBIE, StructureSpawnOverride.BoundingBoxType.STRUCTURE);
            var a = new ChunkPos(8, 0); f.start(structure, a, new BoundingBox(0, 0, 0, 15, 15, 15)); f.references(structure, a);
            var pending = new CompletableFuture<ChunkResult<ChunkAccess>>(); f.delayed.put(a, pending);
            try (var query = f.query(false)) {
                query.prepare(); var at = source ? ChunkPos.ZERO : a;
                f.f.world.visibleHolders.put(at.pack(), new FullRegistrationFixture.Holder(at)); f.f.publishVisible();
                pending.complete(ChunkResult.of(f.f.input(a))); query.prepare();
                assertFalse(query.current()); assertFalse(query.dependency().isDone());
            }
        }
    }
    @Test void failedDependencyPreservesCauseAndNeverPublishesFacts() throws Exception {
        var f = new Fixture(); var failure = new IllegalArgumentException("native structure failure");
        f.delayed.put(ChunkPos.ZERO, CompletableFuture.failedFuture(failure));
        try (var query = f.query(false)) {
            query.prepare(); assertSame(failure, assertThrows(java.util.concurrent.CompletionException.class, query::snapshot).getCause());
        }
    }
    @Test void structureWriterDelaysCopyWithoutRetainingARegionalLease() throws Exception {
        var f = new Fixture(); var cache = StaticCache2D.create(0, 0, 0, (x, z) -> (GenerationChunkHolder) f.f.holder(new ChunkPos(x, z)));
        var writer = f.f.runtime.structureMutations.beginWriter(cache.get(0, 0), ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.STRUCTURE_REFERENCES), cache);
        writer.awaitReady();
        try (var query = f.query(false)) {
            query.prepare(); assertFalse(query.dependency().isDone()); assertFalse(f.f.owner.leases().hasActiveLease());
            writer.close(); query.prepare(); assertTrue(query.dependency().isDone());
            assertTrue(f.f.runtime.structureMutations.measurements().values().stream().allMatch(value -> value == 0));
        } finally { writer.close(); }
    }
    @Test void shutdownAndRetiredOperationMakeLatePreparationInert() throws Exception {
        var f = new Fixture(); try (var query = f.query(false)) {
            f.valid.set(false); query.prepare(); assertTrue(f.requests.isEmpty()); assertFalse(query.current());
        }
        f = new Fixture(); try (var query = f.query(false)) {
            f.f.runtime.shutdown(); query.prepare(); assertTrue(f.requests.isEmpty()); assertFalse(query.current());
        }
    }
}
