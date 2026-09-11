package dev.phoenium.chunk;

import dev.phoenium.core.*;
import dev.phoenium.ownership.*;
import dev.phoenium.region.RegionId;
import dev.phoenium.topology.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
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
    @Test void providerSaveEntryCreatesOneSerialTurnOnlyWhenNoTurnIsAlreadyAdmitted() throws Exception {
        MinecraftServer server = stub(MinecraftServer.class, List.of());
        var service = FullFinishingServices.forServer(server);
        assertEquals(42, FullFinishingServices.ensureSerial(server, FullFinishingServices.Phase.SAVE, () -> {
            FullFinishingServices.requireServer(server, FullFinishingServices.Kind.UNSAVED);
            return 42;
        }));
        assertEquals(1L, service.measurements().get("serialEntries"));
        try (var serial = service.serial(FullFinishingServices.Phase.SERVER_TICK)) {
            assertEquals(7, FullFinishingServices.ensureSerial(server, FullFinishingServices.Phase.SAVE, () -> {
                FullFinishingServices.requireServer(server, FullFinishingServices.Kind.UNSAVED);
                return 7;
            }));
            assertEquals(2L, service.measurements().get("serialEntries"),
                    "provider save entry must reuse an already-admitted same-server turn");
        }
        FullFinishingServices.stopped(server);
    }
    @Test void providerSaveEntryRejectsRegionalOwnershipBeforeOpeningASerialTurn() throws Exception {
        MinecraftServer server = stub(MinecraftServer.class, List.of());
        var service = FullFinishingServices.forServer(server);
        RegionId region = new RegionId(new WorldId("services"), "save-owner");
        var manager = new RegionLeaseManager(region);
        try (var lease = manager.acquire();
             var owner = OwnershipContext.enter(region, lease.epoch(), OperationCategory.OWNER_TASK)) {
            assertThrows(ChunkProtocolException.class, () -> FullFinishingServices.ensureSerial(server,
                    FullFinishingServices.Phase.SAVE, () -> true));
        }
        assertEquals(0L, service.measurements().get("serialEntries"));
        FullFinishingServices.stopped(server);
    }
    @Test void providerSaveEntryCannotReuseAParentSerialTurnAfterRegionalOwnershipBegins() throws Exception {
        MinecraftServer server = stub(MinecraftServer.class, List.of());
        var service = FullFinishingServices.forServer(server);
        RegionId region = new RegionId(new WorldId("services"), "nested-save-owner");
        var manager = new RegionLeaseManager(region);
        try (var serial = service.serial(FullFinishingServices.Phase.SERVER_TICK);
             var lease = manager.acquire();
             var owner = OwnershipContext.enter(region, lease.epoch(), OperationCategory.OWNER_TASK)) {
            assertThrows(ChunkProtocolException.class, () -> FullFinishingServices.ensureSerial(server,
                    FullFinishingServices.Phase.SAVE, () -> true));
            FullFinishingServices.requireServer(server, FullFinishingServices.Kind.UNSAVED);
        }
        assertEquals(1L, service.measurements().get("serialEntries"));
        assertEquals(0L, service.measurements().get("active"));
        FullFinishingServices.stopped(server);
    }
    @Test void providerSaveEntryCannotBorrowAServiceTurnFromAnotherServer() throws Exception {
        MinecraftServer first = stub(MinecraftServer.class, List.of());
        MinecraftServer second = stub(MinecraftServer.class, List.of());
        var firstService = FullFinishingServices.forServer(first);
        var secondService = FullFinishingServices.forServer(second);
        try (var serial = firstService.serial(FullFinishingServices.Phase.SERVER_TASK)) {
            assertThrows(ChunkProtocolException.class, () -> FullFinishingServices.ensureSerial(second,
                    FullFinishingServices.Phase.SAVE, () -> true));
            FullFinishingServices.requireServer(first, FullFinishingServices.Kind.UNSAVED);
        }
        assertEquals(0L, secondService.measurements().get("serialEntries"));
        FullFinishingServices.stopped(first); FullFinishingServices.stopped(second);
    }
    @Test void providerSaveEntryClosesItsSerialTurnWhenTheOriginalBodyFails() throws Exception {
        MinecraftServer server = stub(MinecraftServer.class, List.of());
        var service = FullFinishingServices.forServer(server);
        var exact = new IllegalStateException("provider save body failed");
        assertSame(exact, assertThrows(IllegalStateException.class, () -> FullFinishingServices.ensureSerial(server,
                FullFinishingServices.Phase.SAVE, () -> { throw exact; })));
        assertEquals(0L, service.measurements().get("active"));
        try (var serial = service.serial(FullFinishingServices.Phase.SERVER_TASK)) {
            FullFinishingServices.requireServer(server, FullFinishingServices.Kind.UNSAVED);
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
    @Test void shutdownRequestedInsideAnActiveTurnDefersCleanupUntilTheSynchronousBodyExits() throws Exception {
        MinecraftServer server = stub(MinecraftServer.class, List.of());
        var service = FullFinishingServices.forServer(server);
        try (var serial = service.serial(FullFinishingServices.Phase.SERVER_TASK)) {
            FullFinishingServices.stopped(server);
            assertSame(service, FullFinishingServices.forServer(server), "a stopping server cannot acquire a second service incarnation");
            FullFinishingServices.requireServer(server, FullFinishingServices.Kind.SCOREBOARD);
            assertNull(service.tryReserve(stage(), new Object(), List.of(), 1));
        }
        service.legacyOwners.termination().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertThrows(ChunkProtocolException.class, () -> FullFinishingServices.forServer(server));
    }
    @Test void reentrantStopInsideFullDefersEveryChildUntilRegionalClaimExit() throws Exception {
        try (var fixture = new FullServiceOperationFixture()) {
            fixture.full(claim -> {
                assertTrue(OwnershipContext.current().isPresent());
                FullFinishingServices.stopped(fixture.server);
                assertEquals(0L, fixture.service.legacyOwners.measurements().get("producerTerminated"),
                        "FULL scope cleanup must not stop child executors while regional ownership is active");
                FullFinishingServices.requireServer(fixture.server, FullFinishingServices.Kind.SCOREBOARD);
            });
            assertTrue(OwnershipContext.current().isEmpty());
            fixture.service.legacyOwners.termination().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertThrows(ChunkProtocolException.class, () -> FullFinishingServices.forServer(fixture.server));
        }
    }
    @Test void partialChildShutdownFailureStillClosesReservationsAndStopsTheProducer() throws Exception {
        var exact = new IllegalStateException("controlled services lookup failure");
        MinecraftServer server = stub(MinecraftServer.class, List.of(new Answer(
                MinecraftServer.class.getMethod("services"), ignored -> { throw exact; })));
        var service = FullFinishingServices.forServer(server); Object holder = new Object(); var stage = stage();
        var reservation = service.tryReserve(stage, holder, List.of(), 1); assertNotNull(reservation);
        assertSame(exact, assertThrows(IllegalStateException.class, () -> FullFinishingServices.stopped(server)));
        assertFalse(reservation.valid(stage, holder));
        service.legacyOwners.termination().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(0L, service.measurements().get("reservations"));
        assertThrows(ChunkProtocolException.class, () -> FullFinishingServices.forServer(server));
    }
    @Test void childShutdownReentryObservesTheSameTerminalServiceAndCannotResurrectIt() throws Exception {
        AtomicReference<MinecraftServer> exactServer = new AtomicReference<>();
        AtomicReference<FullFinishingServices> observed = new AtomicReference<>();
        AtomicInteger reentries = new AtomicInteger();
        MinecraftServer server = stub(MinecraftServer.class, List.of(new Answer(
                MinecraftServer.class.getMethod("services"), ignored -> {
                    reentries.incrementAndGet();
                    observed.set(FullFinishingServices.forServer(exactServer.get()));
                    FullFinishingServices.stopped(exactServer.get());
                    return null;
                })));
        exactServer.set(server); var service = FullFinishingServices.forServer(server);
        FullFinishingServices.stopped(server);
        service.legacyOwners.termination().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertSame(service, observed.get()); assertEquals(1, reentries.get());
        assertThrows(ChunkProtocolException.class, () -> FullFinishingServices.forServer(server));
    }
    @Test void productionMixinConfigurationInstallsTheD1AndWorldLifecycleAdapters() throws Exception {
        String configuration;
        try (var input = Objects.requireNonNull(getClass().getResourceAsStream("/phoenium.mixins.json"))) {
            configuration = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (String mixin : List.of("FullFinishingWorldContextMixin", "FullEntityFactMixin", "FullEntityFactPlayerMixin",
                "FullEntityFactAbilitiesMixin", "FullEntityFactGameTypeMixin", "FullEntityFactGameModeMixin",
                "FullEntityFactDataMixin", "FullEntityFactLookupMixin", "FullEntityFactLevelMixin",
                "FullEntityFactConsumersMixin", "FullEntityFactEnemyMixin", "FullSavedSelectorContentsMixin",
                "FullSavedScoreContentsMixin", "FullSavedNbtContentsMixin", "FullLevelThreadAccessMixin",
                "FullBlockEntityTagInputMixin"))
            assertTrue(configuration.contains("\"" + mixin + "\""), "missing installed D1/service adapter " + mixin);
    }
    @Test void largeFiniteScoreboardFootprintIsLazyAndCanMakeProgress() throws Exception {
        MinecraftServer server = stub(MinecraftServer.class, List.of());
        var service = FullFinishingServices.forServer(server); Object holder = new Object(); var stage = stage();
        var reservation = service.tryReserve(stage, holder, List.of(), Integer.MAX_VALUE);
        assertNotNull(reservation, "a finite operation must not be refused by hypothetical eager journal or catch-up capacity");
        assertTrue(reservation.valid(stage, holder));
        RegionId region = new RegionId(stage.work().lifecycle().world(), "large-footprint-owner");
        var manager = new RegionLeaseManager(region);
        try (reservation) {
            try (var lease = manager.acquire();
                 var owner = OwnershipContext.enter(region, lease.epoch(), OperationCategory.OWNER_TASK);
                 var claim = new ChunkStageClaim(new ChunkCustodyModel(), stage, new RegionRoute(region, new TopologyGeneration(1), new OwnerEpoch(0)), lease, null);
                 var full = reservation.enter(claim);
                 var reached = service.scoreboard.begin("finite-member", "finite-team")) {
                service.scoreboard.primitive(); reached.result(true);
            }
        }
        assertEquals(1L, service.scoreboard.measurements().get("committed"));
        assertEquals(1L, service.scoreboard.measurements().get("completed"));
        assertEquals(0L, service.measurements().get("reservations"));
        assertFalse(manager.hasActiveLease());
        FullFinishingServices.stopped(server);
    }
    @Test void negativeScoreboardFootprintFailsBeforeInstallingAnyReservation() throws Exception {
        MinecraftServer server = stub(MinecraftServer.class, List.of());
        var service = FullFinishingServices.forServer(server);
        assertThrows(IllegalArgumentException.class, () -> service.tryReserve(stage(), new Object(), List.of(), -1));
        assertThrows(IllegalArgumentException.class, () -> service.scoreboard.reserve(-1));
        assertEquals(0L, service.measurements().get("reservations"));
        FullFinishingServices.stopped(server);
    }
}
