package dev.phoenium.chunk;

import dev.phoenium.live.PhoeniumLiveRuntime;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static dev.phoenium.chunk.FullDecisionWitnessSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class NaturalSpawnRuntimeBindingTest {
    @BeforeAll static void bootstrap() { FullProductionRegistrationTest.bootstrap(); }
    @Test void ordinaryWorldPublicationProvidesOneStructureRuntimeWithoutEnablingFullGeneration() throws Exception {
        var coordinator = Thread.currentThread();
        var server = stub(MinecraftServer.class, List.of(new Answer(MinecraftServer.class.getMethod("isSameThread"),
            args -> Thread.currentThread() == coordinator)));
        try (var f = new FullServiceOperationFixture(server); var worker = Executors.newSingleThreadExecutor()) {
            var runtime = new PhoeniumLiveRuntime(org.slf4j.LoggerFactory.getLogger("natural-spawn-binding"));
            set(runtime, PhoeniumLiveRuntime.class, "authoritativeFull", false);
            var world = f.world(); set(world, Level.class, "dimension", Level.NETHER);
            var cache = stub(ServerChunkCache.class, List.of());
            var map = stub(net.minecraft.server.level.ChunkMap.class, List.of());
            set(map, net.minecraft.server.level.ChunkMap.class, "phoenium$inputChanges", new FullInputNotifications());
            set(cache, ServerChunkCache.class, "chunkMap", map);
            set(cache, ServerChunkCache.class, "mainThread", coordinator);
            set(world, ServerLevel.class, "chunkSource", cache);
            assertNull(runtime.fullRegistration(world));
            worker.submit(() -> assertThrows(IllegalStateException.class, () -> runtime.naturalSpawnStructures(world))).get(5, TimeUnit.SECONDS);
            runtime.requireChunkWorld(world);
            var structures = runtime.naturalSpawnStructures(world);
            assertSame(world, structures.level()); assertTrue(structures.naturalSpawnOpen());
            assertSame(structures, worker.submit(() -> runtime.naturalSpawnStructures(world)).get(5, TimeUnit.SECONDS));
            assertNull(runtime.fullRegistration(world)); assertTrue(structures.graphSnapshot().isEmpty());
            assertFalse(runtime.pumpFullOne());
            structures.shutdown();
            assertSame(structures, runtime.naturalSpawnStructures(world)); assertFalse(structures.naturalSpawnOpen());
        }
    }
    @Test void diagnosticCollectionFailurePreservesTheOriginalAndIsBounded() {
        var observation = new NaturalSpawnObservation();
        var original = new ChunkProtocolException("original unfinished read");
        var diagnostic = new IllegalArgumentException("diagnostic unavailable");
        assertDoesNotThrow(() -> observation.failure(original, () -> { throw diagnostic; }));
        assertArrayEquals(new Throwable[] {diagnostic}, original.getSuppressed());
        observation.failure(original, () -> { throw new AssertionError("second failure must not collect"); });
        assertEquals(1, original.getSuppressed().length);
    }
}
