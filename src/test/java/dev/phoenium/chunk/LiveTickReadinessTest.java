package dev.phoenium.chunk;

import dev.phoenium.core.WorldId;
import dev.phoenium.live.LiveTickPhases;
import dev.phoenium.live.LiveTickReadiness;
import dev.phoenium.ownership.OperationCategory;
import dev.phoenium.ownership.OwnershipContext;
import dev.phoenium.ownership.RegionLeaseManager;
import dev.phoenium.region.RegionId;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static dev.phoenium.chunk.FullDecisionWitnessSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Original visible-holder and ticking-future selectors; only asynchronous delivery is controlled. */
final class LiveTickReadinessTest {
    @BeforeAll static void bootstrap() { FullProductionRegistrationTest.bootstrap(); }

    @Test void existingPublicTickingFutureSettlesWithNoOwnerAndNoReplacement() throws Exception {
        var f = new Fixture(); var original = f.input;
        f.delivery = ready -> {
            assertTrue(OwnershipContext.current().isEmpty()); assertFalse(ready.getAsBoolean());
            f.input.complete(ChunkResult.of(f.actual)); assertTrue(ready.getAsBoolean());
        };
        var receipt = LiveTickReadiness.await(f.level);
        assertEquals(1, receipt.count()); assertTrue(receipt.current()); assertEquals(1, f.waits);
        assertSame(original, f.holder.getTickingChunkFuture()); assertSame(f.actual, original.join().orElse(null));
    }

    @Test void holderAndFutureReplacementReobservesWithoutCancellingTheOldProducer() throws Exception {
        var f = new Fixture(); var old = f.input;
        var replacement = f.newHolder(); var next = new CompletableFuture<ChunkResult<LevelChunk>>();
        set(replacement, ChunkHolder.class, "tickingChunkFuture", next);
        f.delivery = ready -> {
            if (f.waits == 1) { f.visible.put(ChunkPos.ZERO.pack(), replacement); assertTrue(ready.getAsBoolean()); }
            else { assertFalse(ready.getAsBoolean()); next.complete(ChunkResult.of(f.actual)); }
        };
        var receipt = LiveTickReadiness.await(f.level);
        assertEquals(2, f.waits); assertTrue(receipt.current()); assertFalse(old.isDone());
        set(replacement, ChunkHolder.class, "tickingChunkFuture", CompletableFuture.completedFuture(ChunkHolder.UNLOADED_LEVEL_CHUNK));
        assertFalse(receipt.current());
    }

    @Test void ownerCannotWaitAndTerminalOriginalResultsAreNotManufacturedIntoSuccess() throws Exception {
        var f = new Fixture(); var failure = new IllegalStateException("original neighbor generation");
        f.input.completeExceptionally(failure);
        f.delivery = ready -> assertTrue(ready.getAsBoolean());
        assertTrue(LiveTickReadiness.await(f.level).current()); assertTrue(f.input.isCompletedExceptionally());
        var owner = new RegionId(new WorldId("tick"), "owner"); var leases = new RegionLeaseManager(owner);
        try (var lease = leases.acquire(); var owned = OwnershipContext.enter(owner, lease.epoch(), OperationCategory.TICK)) {
            assertThrows(IllegalStateException.class, () -> LiveTickReadiness.await(f.level));
        }
        assertEquals(1, f.waits);
    }

    @Test void retainedPhaseLocalsCannotReplayOrRunInTheWrongOwnershipPhase() throws Exception {
        var f = new Fixture(); BooleanSupplier haveTime = () -> true;
        var cache = new LiveTickPhases.CacheState(f.cache, haveTime, true, InactiveProfiler.INSTANCE);
        assertThrows(IllegalStateException.class, () -> cache.consume(f.cache));
        assertThrows(IllegalStateException.class, () -> new LiveTickPhases.LevelState(f.level, f.cache, haveTime,
                InactiveProfiler.INSTANCE, new TickRateManager(), true, 100));
        var owner = new RegionId(new WorldId("tick"), "owner"); var leases = new RegionLeaseManager(owner);
        try (var lease = leases.acquire(); var owned = OwnershipContext.enter(owner, lease.epoch(), OperationCategory.TICK)) {
            var level = new LiveTickPhases.LevelState(f.level, f.cache, haveTime, InactiveProfiler.INSTANCE, new TickRateManager(), true, 100);
            level.consume(f.level); cache.consume(f.cache);
            assertThrows(IllegalStateException.class, () -> level.consume(f.level));
            assertThrows(IllegalStateException.class, () -> cache.consume(f.cache));
            assertThrows(IllegalStateException.class, () -> new LiveTickPhases.CacheState(f.cache, haveTime, true, InactiveProfiler.INSTANCE));
        }
    }

    private static final class Fixture {
        final Long2ObjectLinkedOpenHashMap<ChunkHolder> visible = new Long2ObjectLinkedOpenHashMap<>();
        final FullRegistrationFixture.Holder holder = newHolder();
        final CompletableFuture<ChunkResult<LevelChunk>> input = new CompletableFuture<>();
        final LevelChunk actual = allocate(LevelChunk.class);
        final ServerChunkCache cache = allocate(ServerChunkCache.class);
        final ServerLevel level;
        int waits;
        Consumer<BooleanSupplier> delivery;
        Fixture() throws Exception {
            var server = stub(MinecraftServer.class, List.of(
                    new Answer(MinecraftServer.class.getMethod("getRunningThread"), ignored -> Thread.currentThread()),
                    new Answer(MinecraftServer.class.getMethod("managedBlock", BooleanSupplier.class), args -> {
                        waits++; delivery.accept((BooleanSupplier) args[0]); return null;
                    })));
            level = stub(ServerLevel.class, List.of(new Answer(ServerLevel.class.getMethod("getServer"), ignored -> server)));
            var map = allocate(ChunkMap.class); visible.put(ChunkPos.ZERO.pack(), holder);
            set(map, ChunkMap.class, "visibleChunkMap", visible); set(cache, ServerChunkCache.class, "chunkMap", map);
            set(level, ServerLevel.class, "chunkSource", cache); set(holder, ChunkHolder.class, "tickingChunkFuture", input);
        }
        FullRegistrationFixture.Holder newHolder() throws Exception {
            var holder = new FullRegistrationFixture.Holder(ChunkPos.ZERO); set(holder, ChunkHolder.class, "ticketLevel", 32); return holder;
        }
    }
}
