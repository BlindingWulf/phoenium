package dev.phoenium.chunk;

import dev.phoenium.core.WorldId;
import dev.phoenium.topology.RoutingKey;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static dev.phoenium.chunk.ChunkTypeMetadataService.Marker.*;
import static dev.phoenium.chunk.ChunkTypeMetadataService.Result.*;

/** Deterministic metadata fence proofs. Blocking acknowledgments exist only in this test class. */
class ChunkMetadataAuthorizationTest {
    @Test void prq15AStaleLifecyclePerformsZeroWritesAndCannotMarkFullEither() {
        var f = new Fixture(); var old = f.work;
        f.lifecycle = f.services.lifecycles().activateHolder(7, f.key, new Object());
        f.work = f.work("replacement");
        f.metadata().markPosition(f.work, f.key, FULL);
        f.reject(old, f.key);
        f.metadata().markPositionReplaceable(f.work, f.key);
        long writes = f.metadata().backingWrites();
        assertEquals(STALE, f.metadata().markPosition(old, f.key, FULL).result());
        assertEquals(writes, f.metadata().backingWrites());
        assertEquals((byte)-1, f.metadata().value(f.key).orElseThrow());
        f.metadata().retire(old.lifecycle());
        assertEquals((byte)-1, f.metadata().value(f.key).orElseThrow());
    }

    @Test void prq15BAllCurrentReplacementTransitionsPreservePreviousBytes() {
        for (int initial : new int[] {0, -1, 1}) {
            var f = new Fixture();
            if (initial != 0) f.metadata().markPosition(f.work, f.key, initial == 1 ? FULL : PROTO);
            long writes = f.metadata().backingWrites();
            var update = f.metadata().markPositionReplaceable(f.work, f.key);
            assertEquals(APPLIED, update.result()); assertEquals((byte)initial, update.previous());
            assertEquals(writes + 1, f.metadata().backingWrites());
            assertEquals((byte)-1, f.metadata().value(f.key).orElseThrow());
            assertEquals((byte)-1, f.metadata().markPosition(f.work, f.key, FULL).previous());
            assertEquals((byte)1, f.metadata().markPosition(f.work, f.key, PROTO).previous());
            assertEquals((byte)-1, f.metadata().value(f.key).orElseThrow());
        }
    }

    @Test void prq15CSameLifecycleActuallyRetiredWorkCannotDowngrade() {
        var f = new Fixture(); var old = f.work;
        assertEquals(LoadApplyPrerequisites.State.READY, f.services.prerequisites().evaluate(f.lifecycle, old, LoadApplyPrerequisites.Required.fresh()));
        f.services.works().retireWork(old, false);
        f.work = f.work("successor");
        f.metadata().markPosition(f.work, f.key, FULL);
        f.reject(old, f.key);
        assertEquals(APPLIED, f.metadata().markPositionReplaceable(f.work, f.key).result());
        assertEquals((byte)-1, f.metadata().value(f.key).orElseThrow());
    }

    @Test void newerSharedWorkAndDemandCancellationDoNotCancelEarlierWork() {
        var f = new Fixture(); var earlier = f.work; var demand = f.services.works().addDemand(earlier);
        var later = f.work("later-status"); f.services.works().removeDemand(demand);
        f.metadata().markPosition(later, f.key, FULL);
        assertEquals(APPLIED, f.metadata().markPositionReplaceable(earlier, f.key).result());
        assertTrue(f.services.works().isCurrentWork(earlier));
    }

    @Test void pendingUnloadPreservesReservedMetadataEntitlement() {
        var f = new Fixture(); f.metadata().markPosition(f.work, f.key, FULL);
        assertTrue(f.services.lifecycles().beginPendingUnload(f.lifecycle));
        assertEquals(APPLIED, f.metadata().markPositionReplaceable(f.work, f.key).result());
    }

    @Test void wrongWorldSessionInstanceAndTargetPerformZeroWrites() {
        var f = new Fixture(); f.metadata().markPosition(f.work, f.key, FULL);
        var id = f.lifecycle;
        var foreignWorld = new WorldId("other"); var foreignKey = new RoutingKey(foreignWorld, 3, 4);
        f.reject(new ChunkCustodyModel.WorkId(new ChunkCustodyModel.LifecycleId("other-session", 7, f.world, f.key, 0), 0), f.key);
        // Even genuinely registered alternate world/instance lineages cannot authorize this service.
        var otherInstance = f.services.lifecycles().activateHolder(8, f.key, new Object());
        var otherWorld = f.services.lifecycles().activateHolder(7, foreignKey, new Object());
        f.reject(f.services.works().beginOrResolveSharedWork(otherInstance, new ChunkWorkRegistry.WorkReservation("load","p",0)), f.key);
        f.reject(f.services.works().beginOrResolveSharedWork(otherWorld, new ChunkWorkRegistry.WorkReservation("load","p",0)), f.key);
        f.reject(f.work, new RoutingKey(f.world, 4, 4));
        assertEquals(id, f.lifecycle);
    }

    @Test void unknownUnregisteredAndCancelledWorkPerformZeroWrites() {
        var f = new Fixture(); f.metadata().markPosition(f.work, f.key, FULL);
        f.reject(new ChunkCustodyModel.WorkId(f.lifecycle, 500), f.key);
        var unregistered = f.services.custody().beginWork(f.lifecycle, 501);
        f.reject(unregistered, f.key);
        f.services.works().retireWork(f.work, true); f.reject(f.work, f.key);
    }

    @Test void retirementCleanupPreventsReinsertion() {
        var f = new Fixture(); f.metadata().markPosition(f.work, f.key, FULL);
        f.services.retire(f.lifecycle);
        long writes = f.metadata().backingWrites();
        assertEquals(STALE, f.metadata().markPositionReplaceable(f.work, f.key).result());
        assertEquals(writes, f.metadata().backingWrites()); assertEquals(0, f.metadata().entries());
    }

    @Test void shutdownPreventsReinsertion() {
        var f = new Fixture(); f.metadata().markPosition(f.work, f.key, FULL);
        f.services.shutdown(); long writes = f.metadata().backingWrites();
        assertEquals(SHUTDOWN, f.metadata().markPositionReplaceable(f.work, f.key).result());
        assertEquals(writes, f.metadata().backingWrites()); assertEquals(0, f.metadata().entries());
    }

    @Test void invalidationWinsBeforeConcurrentMutation() throws Exception {
        var f = new Fixture(); f.metadata().markPosition(f.work, f.key, FULL);
        var started = new CountDownLatch(1);
        CompletableFuture<ChunkTypeMetadataService.Update> pending;
        f.services.custody().gate().lock();
        try {
            pending = CompletableFuture.supplyAsync(() -> { started.countDown(); return f.metadata().markPositionReplaceable(f.work, f.key); });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            f.services.works().retireWork(f.work, false);
        } finally { f.services.custody().gate().unlock(); }
        long writes = f.metadata().backingWrites();
        assertEquals(STALE, pending.get(5, TimeUnit.SECONDS).result());
        assertEquals(writes, f.metadata().backingWrites()); assertEquals((byte)1, f.metadata().value(f.key).orElseThrow());
    }

    @Test void mutationWinsBeforeConcurrentInvalidation() throws Exception {
        var f = new Fixture(); f.metadata().markPosition(f.work, f.key, FULL);
        var started = new CountDownLatch(1); CompletableFuture<Void> retirement;
        f.services.custody().gate().lock();
        try {
            retirement = CompletableFuture.runAsync(() -> { started.countDown(); f.services.works().retireWork(f.work, false); });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            var update = f.metadata().markPositionReplaceable(f.work, f.key);
            assertEquals(APPLIED, update.result()); assertEquals((byte)1, update.previous());
        } finally { f.services.custody().gate().unlock(); }
        retirement.get(5, TimeUnit.SECONDS);
        assertEquals((byte)-1, f.metadata().value(f.key).orElseThrow());
        long writes = f.metadata().backingWrites();
        assertEquals(STALE, f.metadata().markPosition(f.work, f.key, FULL).result());
        assertEquals(writes, f.metadata().backingWrites());
    }

    private static final class Fixture {
        final WorldId world = new WorldId("metadata");
        final RoutingKey key = new RoutingKey(world, 3, 4);
        final ChunkPrerequisiteServices services = new ChunkPrerequisiteServices(world, "session", 7);
        ChunkCustodyModel.LifecycleId lifecycle = services.lifecycles().activateHolder(7, key, new Object());
        ChunkCustodyModel.WorkId work = work("parsed");
        ChunkCustodyModel.WorkId work(String slot) { return services.works().beginOrResolveSharedWork(lifecycle, new ChunkWorkRegistry.WorkReservation("load", slot, 0)); }
        ChunkTypeMetadataService metadata() { return services.metadata(); }
        void reject(ChunkCustodyModel.WorkId rejected, RoutingKey target) {
            long writes = metadata().backingWrites(); var before = metadata().value(key);
            assertEquals(STALE, metadata().markPositionReplaceable(rejected, target).result());
            assertEquals(writes, metadata().backingWrites()); assertEquals(before, metadata().value(key));
        }
    }
}
