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
import com.ishland.c2me.rewrites.chunksystem.common.NewChunkHolderVanillaInterface;
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

    @Test void churnedPublicWrappersUseStableProviderSlotWithoutBusySpin() throws Exception {
        var f = new Fixture(); var holder = new ChurnHolder(f.actual);
        set(holder, ChunkHolder.class, "ticketLevel", 32);
        f.visible.clear(); f.visible.put(ChunkPos.ZERO.pack(), holder);
        f.delivery = ready -> {
            assertFalse(ready.getAsBoolean());
            holder.slot.complete(null);
            assertTrue(ready.getAsBoolean());
        };
        var first = holder.getTickingChunkFuture(); var second = holder.getTickingChunkFuture();
        assertNotSame(first, second, "provider fixture must create a fresh public wrapper per observation");
        var receipt = LiveTickReadiness.await(f.level);
        assertEquals(1, f.waits); assertTrue(receipt.current()); assertEquals(1, receipt.count());
    }

    @Test void providerSlotReplacementReobservesWithoutCancellingTheOldIncarnation() throws Exception {
        var f = new Fixture(); var holder = new ChurnHolder(f.actual);
        set(holder, ChunkHolder.class, "ticketLevel", 32);
        f.visible.clear(); f.visible.put(ChunkPos.ZERO.pack(), holder);
        var old = holder.slot; var next = new CompletableFuture<Void>();
        f.delivery = ready -> {
            if (f.waits == 1) {
                assertFalse(ready.getAsBoolean()); holder.slot = next; assertTrue(ready.getAsBoolean());
            } else {
                assertFalse(ready.getAsBoolean()); next.complete(null); assertTrue(ready.getAsBoolean());
            }
        };
        var receipt = LiveTickReadiness.await(f.level);
        assertEquals(2, f.waits); assertTrue(receipt.current()); assertFalse(old.isDone());
        holder.slot = CompletableFuture.completedFuture(null);
        assertFalse(receipt.current());
    }

    @Test void lockedC2meShapeCachesOnePublicWrapperPerStableStatusSlot() throws Exception {
        var f = new Fixture(); var holder = new NewChunkHolderVanillaInterface(f.actual);
        set(holder, ChunkHolder.class, "ticketLevel", 32);
        f.visible.clear(); f.visible.put(ChunkPos.ZERO.pack(), holder);
        f.delivery = ready -> {
            assertFalse(ready.getAsBoolean()); holder.directSlot().complete(null); assertTrue(ready.getAsBoolean());
        };
        var receipt = LiveTickReadiness.await(f.level);
        assertEquals(1, f.waits); assertTrue(receipt.current()); assertTrue(receipt.current());
        assertEquals(1, holder.wrapperCalls(), "stable C2ME slot must retain exactly one public wrapper");
    }

    @Test void lockedC2meShapeReobservesSameHolderWhenStatusSlotIncarnationChanges() throws Exception {
        var f = new Fixture(); var holder = new NewChunkHolderVanillaInterface(f.actual);
        set(holder, ChunkHolder.class, "ticketLevel", 32);
        f.visible.clear(); f.visible.put(ChunkPos.ZERO.pack(), holder);
        var old = holder.directSlot(); var next = new CompletableFuture<Void>();
        f.delivery = ready -> {
            if (f.waits == 1) {
                assertFalse(ready.getAsBoolean()); holder.replaceSlot(next); assertTrue(ready.getAsBoolean());
            } else {
                assertFalse(ready.getAsBoolean()); next.complete(null); assertTrue(ready.getAsBoolean());
            }
        };
        var receipt = LiveTickReadiness.await(f.level);
        assertEquals(2, f.waits); assertTrue(receipt.current()); assertFalse(old.isDone());
        assertEquals(2, holder.wrapperCalls(), "each C2ME slot incarnation gets one retained public wrapper");
        holder.replaceSlot(CompletableFuture.completedFuture(null));
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
        cache.consume(f.cache);
        assertThrows(IllegalStateException.class, () -> cache.consume(f.cache));
        assertThrows(IllegalStateException.class, () -> new LiveTickPhases.LevelState(f.level, f.cache, haveTime,
                InactiveProfiler.INSTANCE, new TickRateManager(), true, 100));
        var owner = new RegionId(new WorldId("tick"), "owner"); var leases = new RegionLeaseManager(owner);
        try (var lease = leases.acquire(); var owned = OwnershipContext.enter(owner, lease.epoch(), OperationCategory.TICK)) {
            var level = new LiveTickPhases.LevelState(f.level, f.cache, haveTime, InactiveProfiler.INSTANCE, new TickRateManager(), true, 100);
            level.consume(f.level);
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

    private static final class ChurnHolder extends ChunkHolder implements LiveTickReadiness.TickingSlotAccess {
        final LevelChunk actual;
        volatile CompletableFuture<Void> slot = new CompletableFuture<>();
        ChurnHolder(LevelChunk actual) {
            super(ChunkPos.ZERO, 33, net.minecraft.world.level.LevelHeightAccessor.create(0, 256), null,
                    (position, previous, next, setter) -> setter.accept(next), (position, borderOnly) -> List.of());
            this.actual = actual;
        }
        @Override public CompletableFuture<ChunkResult<LevelChunk>> getTickingChunkFuture() {
            CompletableFuture<Void> observed = slot;
            return observed.thenApply(ignored -> ChunkResult.of(actual));
        }
        @Override public Object phoenium$tickingSlotIdentity() { return slot; }
    }
}
