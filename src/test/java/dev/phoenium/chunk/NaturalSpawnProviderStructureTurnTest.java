package dev.phoenium.chunk;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.level.*;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static dev.phoenium.chunk.FullDecisionWitnessSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class NaturalSpawnProviderStructureTurnTest {
    @BeforeAll static void bootstrap() { FullProductionRegistrationTest.bootstrap(); }
    private FullRegistrationFixture fixture() throws Exception {
        var f = new FullRegistrationFixture(true);
        var map = stub(ChunkMap.class, List.of(
            new Answer(FullGenerationSource.class.getMethod("phoenium$fullRegistration"), ignored -> null),
            new Answer(FullGenerationSource.class.getMethod("phoenium$naturalSpawnStructures"), ignored -> f.runtime)), FullGenerationSource.class);
        set(f.world.cache, ServerChunkCache.class, "chunkMap", map); return f;
    }
    @Test void c2meFeatureWriterCoversReferencedStartsAndKeepsOriginalFuture() throws Exception {
        var f = fixture();
        var cache = StaticCache2D.create(0, 0, 8, (x, z) -> (GenerationChunkHolder) f.holder(new ChunkPos(x, z)));
        var original = new CompletableFuture<ChunkAccess>();
        var selected = FullRegistrationRuntime.protectC2meStructures(f.context,
            ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.FEATURES), cache, f.input(ChunkPos.ZERO), () -> original);
        assertSame(original, selected); assertEquals(289L, f.runtime.structureMutations.measurements().get("writers"));
        original.complete(f.input(ChunkPos.ZERO));
        assertTrue(f.runtime.structureMutations.measurements().values().stream().allMatch(value -> value == 0));
    }
    @Test void synchronousProviderFailureRemainsSynchronousAndReleasesWriter() throws Exception {
        var f = fixture(); var cache = StaticCache2D.create(0, 0, 0, (x, z) -> (GenerationChunkHolder) f.holder(new ChunkPos(x, z)));
        var original = new IllegalStateException("original generation synchronous failure");
        assertSame(original, assertThrows(IllegalStateException.class, () -> FullRegistrationRuntime.protectC2meStructures(f.context,
            ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.STRUCTURE_STARTS), cache, f.input(ChunkPos.ZERO), () -> { throw original; })));
        assertTrue(f.runtime.structureMutations.measurements().values().stream().allMatch(value -> value == 0));
    }
    @Test void failedProviderFutureKeepsIdentityAndCauseAndReleasesWriter() throws Exception {
        var f = fixture(); var cache = StaticCache2D.create(0, 0, 0, (x, z) -> (GenerationChunkHolder) f.holder(new ChunkPos(x, z)));
        var original = new CompletableFuture<ChunkAccess>(); var failure = new IllegalArgumentException("original asynchronous failure");
        assertSame(original, FullRegistrationRuntime.protectC2meStructures(f.context,
            ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.STRUCTURE_REFERENCES), cache, f.input(ChunkPos.ZERO), () -> original));
        original.completeExceptionally(failure);
        assertSame(failure, assertThrows(java.util.concurrent.CompletionException.class, original::join).getCause());
        assertTrue(f.runtime.structureMutations.measurements().values().stream().allMatch(value -> value == 0));
    }
    @Test void nativeGenerationAlsoProtectsStructureWritersWithoutSelectingAuthoritativeFull() throws Exception {
        var f = fixture();
        var cache = StaticCache2D.create(0, 0, 0, (x, z) -> (GenerationChunkHolder) f.holder(new ChunkPos(x, z)));
        var result = new CompletableFuture<ChunkAccess>(); var calls = new java.util.concurrent.atomic.AtomicInteger();
        var map = (GeneratingChunkMap) f.world.cache.chunkMap;
        assertNull(((FullGenerationSource) map).phoenium$fullRegistration());
        assertSame(result, FullRegistrationRuntime.applyWinning(map, cache.get(0, 0),
            ChunkPyramid.GENERATION_PYRAMID.getStepTo(ChunkStatus.STRUCTURE_STARTS), cache, () -> { calls.incrementAndGet(); return result; }));
        assertEquals(1, calls.get()); assertEquals(1L, f.runtime.structureMutations.measurements().get("writers"));
        result.complete(f.input(ChunkPos.ZERO));
        assertTrue(f.runtime.graphSnapshot().isEmpty());
        assertTrue(f.runtime.structureMutations.measurements().values().stream().allMatch(value -> value == 0));
    }
}
