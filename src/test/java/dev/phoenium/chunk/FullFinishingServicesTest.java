package dev.phoenium.chunk;

import dev.phoenium.core.*;
import dev.phoenium.ownership.*;
import dev.phoenium.region.RegionId;
import dev.phoenium.topology.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.server.MinecraftServer;
import org.junit.jupiter.api.Test;
import static dev.phoenium.chunk.FullDecisionWitnessSupport.*;
import static org.junit.jupiter.api.Assertions.*;

final class FullFinishingServicesTest {
    private static ChunkCustodyModel.StageId stage() {
        WorldId world = new WorldId("services");
        var life = new ChunkCustodyModel.LifecycleId("service-test", 1, world, new RoutingKey(world, 0, 0), 1);
        return new ChunkCustodyModel.StageId(new ChunkCustodyModel.WorkId(life, 1), ChunkStageKind.FULL_FINISH, "FULL", 0);
    }
    @Test void reservationExcludesOtherBodiesBeforeClaimsAndReleasesOnStaleDelivery() throws Exception {
        MinecraftServer server = stub(MinecraftServer.class, List.of());
        var service = FullFinishingServices.forServer(server); Object holder = new Object(); var stage = stage();
        var first = service.tryReserve(stage, holder, List.of(), 4);
        assertNotNull(first); assertTrue(first.valid(stage, holder));
        assertFalse(first.valid(stage, new Object()));
        assertNull(service.tryReserve(stage, new Object(), List.of(), 4));
        assertThrows(ChunkProtocolException.class, () -> service.serial(FullFinishingServices.Phase.SERVER_TASK));
        first.close(); assertFalse(first.valid(stage, holder)); first.close();
        try (var serial = service.serial(FullFinishingServices.Phase.SERVER_TASK)) {
            FullFinishingServices.requireServer(server, FullFinishingServices.Kind.SCOREBOARD);
        }
        assertEquals(0L, service.measurements().get("reservations"));
        FullFinishingServices.stopped(server);
    }
    @Test void ownerFreeNestedFullLendsAndRestoresTheActualSerialTurn() throws Exception {
        MinecraftServer server = stub(MinecraftServer.class, List.of());
        var service = FullFinishingServices.forServer(server); Object holder = new Object(); var stage = stage();
        RegionId region = new RegionId(stage.work().lifecycle().world(), "owner");
        var manager = new RegionLeaseManager(region);
        try (var serial = service.serial(FullFinishingServices.Phase.SERVER_TASK)) {
            var reservation = service.tryReserve(stage, holder, List.of(), 4);
            try (var lease = manager.acquire();
                 var owner = OwnershipContext.enter(region, lease.epoch(), OperationCategory.OWNER_TASK);
                 var claim = new ChunkStageClaim(new ChunkCustodyModel(), stage, new RegionRoute(region, new TopologyGeneration(1), new OwnerEpoch(0)), lease, null)) {
                try (var full = reservation.enter(claim)) {
                    assertTrue(FullFinishingServices.finishing());
                    FullFinishingServices.requireServer(server, FullFinishingServices.Kind.SCOREBOARD);
                    assertThrows(ChunkProtocolException.class, () -> service.tryReserve(stage, holder, List.of(), 1));
                }
            }
            reservation.close(); assertFalse(FullFinishingServices.finishing());
            FullFinishingServices.requireServer(server, FullFinishingServices.Kind.SCOREBOARD);
        }
        assertEquals(1L, service.measurements().get("fullEntries"));
        assertEquals(0L, service.measurements().get("active"));
        FullFinishingServices.stopped(server);
    }
    @Test void changedPrimitiveInvalidatesUnusedReservationWithoutStartingFull() throws Exception {
        MinecraftServer server = stub(MinecraftServer.class, List.of());
        var service = FullFinishingServices.forServer(server); Object holder = new Object();
        try (var serial = service.serial(FullFinishingServices.Phase.SERVER_TASK)) {
            var reservation = service.tryReserve(stage(), holder, List.of(), 1);
            service.changed(FullFinishingServices.Kind.SCOREBOARD);
            assertFalse(reservation.valid(stage(), holder)); reservation.close();
            assertNotNull(service.tryReserve(stage(), holder, List.of(), 1));
        }
        FullFinishingServices.stopped(server);
    }
    @Test void storageWaitReleasesOrdinarySerialTurnAndRestoresItBeforeReturn() throws Exception {
        MinecraftServer server = stub(MinecraftServer.class, List.of());
        var service = FullFinishingServices.forServer(server);
        try (var serial = service.serial(FullFinishingServices.Phase.SAVE)) {
            int value = FullFinishingServices.ownerFreeIo(() -> {
                assertEquals(0L, service.measurements().get("active")); return 42;
            });
            assertEquals(42, value);
            FullFinishingServices.requireServer(server, FullFinishingServices.Kind.SCOREBOARD);
        }
        FullFinishingServices.stopped(server);
    }
    @Test void reservationOnAnotherThreadDoesNotInheritSerialAuthority() throws Exception {
        MinecraftServer server = stub(MinecraftServer.class, List.of());
        var service = FullFinishingServices.forServer(server);
        try (var serial = service.serial(FullFinishingServices.Phase.SERVER_TASK)) {
            assertNull(CompletableFuture.supplyAsync(() -> service.tryReserve(stage(), new Object(), List.of(), 1)).get(5, TimeUnit.SECONDS));
        }
        FullFinishingServices.stopped(server);
    }
}
