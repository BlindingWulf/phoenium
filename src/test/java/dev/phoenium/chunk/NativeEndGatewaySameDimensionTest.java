package dev.phoenium.chunk;

import dev.phoenium.core.OwnerEpoch;
import dev.phoenium.ownership.OwnershipContext;
import dev.phoenium.region.RegionId;
import dev.phoenium.region.RegionLifecycle;
import dev.phoenium.region.RegionRuntime;
import dev.phoenium.service.RegionalServiceKind;
import dev.phoenium.topology.RegionRoute;
import dev.phoenium.topology.RoutingKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.PortalProcessor;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.level.block.entity.TheEndGatewayBlockEntity;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static dev.phoenium.chunk.FullDecisionWitnessSupport.set;
import static org.junit.jupiter.api.Assertions.*;

/** Pinned 26.2 native EndGatewayBlock transition and Entity.teleportSameDimension control. */
class NativeEndGatewaySameDimensionTest {
    @BeforeAll static void bootstrap() { NativeItemPortalTransferTest.bootstrap(); }
    @AfterEach void resetTicket() {
        NativePortalTestHooks.ticket = entity -> entity.placePortalTicket(BlockPos.containing(entity.position()));
        NativePortalTestHooks.tickets = null;
    }

    @Test void sameOwnerNativeGatewayKeepsOneObjectAndPlacesTicketAfterMove() throws Exception {
        var result = nativeGateway(false, false, true);
        assertEquals(0, result.deferredTickets(), "a ticket for the caller's own chunk must stay inline");
        assertEquals(List.of("move", "ticket"), result.events());
        assertSame(result.pig(), result.returned());
        assertEquals(result.uuid(), result.returned().getUUID());
        assertEquals(result.runtimeId(), result.returned().getId());
        assertEquals(new Vec3(160.5, 65.0, 160.5), result.returned().position());
        assertEquals(result.sourceOwner(), result.ownerAtTicket());
        assertEquals(result.destinationOwner(), result.ownerAtTicket());
        assertEquals(0, result.pendingRelocations());
        assertEquals(result.destination(), result.bindingAtTicket());
        assertEquals(1, result.ticketCalls());
    }

    /** Historical pre-repair control: with the ticket boundary absent native A/B runs it under A. */
    @Test void unrepairedCrossOwnerGatewayControlDocumentsHistoricalWrongOwnerTicket() throws Exception {
        var result = nativeGateway(true, false, false);
        assertEquals(List.of("move", "ticket"), result.events());
        assertSame(result.pig(), result.returned(), "native same-dimension teleport returns this");
        assertEquals(result.uuid(), result.returned().getUUID());
        assertEquals(result.runtimeId(), result.returned().getId());
        assertEquals(new Vec3(160.5, 65.0, 160.5), result.returned().position());
        assertEquals(1, result.ticketCalls(), "the exact PLACE_PORTAL_TICKET callback ran");
        assertEquals(result.sourceOwner(), result.ownerAtTicket(),
            "without the boundary the native callback runs inside the source owner turn");
        assertNotEquals(result.destinationOwner(), result.ownerAtTicket(),
            "explicit A/B topology exposes a wrong-owner native gateway callback");
        assertEquals(result.sourceKey(), result.bindingAtTicket(),
            "the same-object relocation remains uncommitted when the native callback fires");
        assertEquals(1, result.pendingRelocations());
    }

    /**
     * Required gate (formerly the opt-in PHOENIUM_GATEWAY_STRICT red): the exact native
     * PLACE_PORTAL_TICKET callback for a chunk B owns must run under B, not under the source owner A.
     */
    @Test void strictCrossOwnerGatewayTicketMustRunUnderDestinationOwner() throws Exception {
        var result = nativeGateway(true, false, true);
        assertEquals(result.destinationOwner(), result.ownerAtTicket(),
            "native End gateway PLACE_PORTAL_TICKET for a chunk B owns must run under B");
        assertNotEquals(result.sourceOwner(), result.ownerAtTicket());
        assertEquals(List.of("move", "ticket"), result.events());
        assertEquals(1, result.ticketCalls(), "the native callback ran exactly once");
    }

    @Test void repairedCrossOwnerGatewayKeepsOneObjectAndSettlesWithoutLeakingState() throws Exception {
        var result = nativeGateway(true, false, true);
        // Same native object: no retirement, replacement, new UUID or new runtime id.
        assertSame(result.pig(), result.returned());
        assertEquals(result.uuid(), result.returned().getUUID());
        assertEquals(result.runtimeId(), result.returned().getId());
        assertEquals(new Vec3(160.5, 65.0, 160.5), result.returned().position());
        // The callback is retained, not run inline, and not replayed or lost.
        assertEquals(1, result.deferredTickets());
        assertEquals(1, result.placedTickets());
        assertEquals(0, result.droppedTickets());
        assertEquals(0, result.pendingTickets());
        // The owner-free relocation cut stays the only authority move for the object itself.
        assertEquals(1, result.pendingRelocationsBeforeCut());
        assertEquals(0, result.pendingRelocationsAfterCut());
        assertEquals(result.destinationOwner(), result.ownerAfterCut(),
            "the same object ends owned by the destination region after the cut");
        assertEquals(result.destination(), result.bindingAfterCut());
        assertEquals(result.destinationOwner(), result.unrelatedOwnerAfterEnclosure(),
            "no unrelated region ownership moved");
    }

    @Test void ownerFreeTopologyEnclosureMakesTicketOwnedButMigratesUnrelatedRegion() throws Exception {
        var result = nativeGateway(true, true, false);
        assertEquals(List.of("move", "ticket"), result.events());
        assertSame(result.pig(), result.returned());
        assertEquals(result.uuid(), result.returned().getUUID());
        assertEquals(result.runtimeId(), result.returned().getId());
        assertEquals(result.sourceOwner(), result.ownerAtTicket());
        assertEquals(result.destinationOwner(), result.ownerAtTicket());
        assertEquals(result.destination(), result.bindingAtTicket());
        assertEquals(0, result.pendingRelocations());
        assertEquals(1, result.ticketCalls());
        assertEquals(result.sourceOwner(), result.unrelatedOwnerAfterEnclosure(),
            "FullRegionalEnclosure moves an unrelated B-region route into A; it is not a narrow gateway handoff");
    }

    private static Control nativeGateway(boolean crossOwner, boolean enclose, boolean repaired) throws Exception {
        var f = new NativeItemPortalTransferTest.Fixture(Level.END);
        NativePortalDestinationPreparationTest.installNativeLookup(f);
        var source = f.nether;
        var sourceKey = f.to; // End chunk (6,0), deliberately distinct from the far exit.
        var destination = new RoutingKey(f.b, 10, 10);
        var unrelated = new RoutingKey(f.b, 100, 100);
        var destinationOwner = crossOwner ? new RegionId(f.b, "zz-gateway-exit") : f.br;
        if (crossOwner) {
            var runtime = new RegionRuntime(destinationOwner, new OwnerEpoch(0));
            runtime.transitionTo(RegionLifecycle.READY);
            f.runtimes.put(destinationOwner, runtime);
        }
        var generation = f.directory.snapshot().generation().next();
        f.directory.publish(generation, Map.of(
            f.from, new RegionRoute(f.ar, generation, new OwnerEpoch(0)),
            sourceKey, new RegionRoute(f.br, generation, new OwnerEpoch(0)),
            destination, new RegionRoute(destinationOwner, generation, new OwnerEpoch(0)),
            unrelated, new RegionRoute(destinationOwner, generation, new OwnerEpoch(0))));

        var registries = NativePortalDestinationPreparationTest.dynamicRegistries();
        set(source, Level.class, "registryAccess", registries);
        set(source, ServerLevel.class, "environmentAttributes",
            net.minecraft.world.attribute.EnvironmentAttributeSystem.builder().build());
        Pig pig = EntityTypes.PIG.create(source, EntitySpawnReason.COMMAND);
        assertNotNull(pig);
        pig.setPos(96.5, 65.0, 8.5);
        f.publish(pig, sourceKey);
        UUID uuid = pig.getUUID();
        int runtimeId = pig.getId();

        BlockPos entry = new BlockPos(96, 65, 8);
        BlockPos exit = new BlockPos(160, 65, 160);
        var gateway = new TheEndGatewayBlockEntity(entry, Blocks.END_GATEWAY.defaultBlockState());
        gateway.setExitPosition(exit, true);
        f.blockEntities.put(entry, gateway);
        TeleportTransition transition = ((Portal) Blocks.END_GATEWAY).getPortalDestination(source, pig, entry);
        assertNotNull(transition);
        assertSame(source, transition.newLevel());
        assertSame(TeleportTransition.PLACE_PORTAL_TICKET, transition.postTeleportTransition());
        assertEquals(Vec3.atBottomCenterOf(exit), transition.position());
        if (enclose) {
            var closure = new FullRegionalEnclosure(f.directory, f.runtimes, f.gateway, f.objects);
            assertEquals(destinationOwner, f.directory.resolve(unrelated).orElseThrow().region());
            assertFalse(closure.enclose(java.util.Set.of(sourceKey, destination)));
            assertTrue(closure.enclose(java.util.Set.of(sourceKey, destination)),
                "owner-free convergence should publish a common End owner");
            assertEquals(f.br, f.directory.resolve(destination).orElseThrow().region());
        }

        List<String> events = new ArrayList<>();
        AtomicReference<RegionId> ticketOwner = new AtomicReference<>();
        AtomicReference<RoutingKey> ticketBinding = new AtomicReference<>();
        AtomicInteger ticketCalls = new AtomicInteger();
        pig.setLevelCallback(new net.minecraft.world.level.entity.EntityInLevelCallback() {
            @Override public void onMove() {
                events.add("move");
                f.identities.routingObserved(uuid.toString(), f.b, runtimeId, destination);
            }
            @Override public void onRemove(Entity.RemovalReason reason) {
                fail("same-dimension gateway must not retire its source object: " + reason);
            }
        });
        NativePortalTestHooks.ticket = entity -> {
            assertSame(pig, entity);
            assertEquals(Vec3.atBottomCenterOf(exit), entity.position());
            ticketCalls.incrementAndGet();
            ticketOwner.set(OwnershipContext.current().orElseThrow().region());
            ticketBinding.set(f.objects.current(uuid.toString()).orElseThrow().routingKey());
            events.add("ticket");
        };

        var boundary = new dev.phoenium.live.PortalTicketBoundary(f.authority,
            key -> f.directory.resolve(key).map(RegionRoute::region),
            (entity, position) -> NativePortalTestHooks.ticket.accept(entity));
        if (repaired) NativePortalTestHooks.tickets = boundary;
        AtomicReference<Entity> returned = new AtomicReference<>();
        var manager = new dev.phoenium.live.MobPortalTransitionManager(f.authority, f.objects);
        f.dispatcher.executeAndDrain(sourceKey, RegionalServiceKind.ENTITY_PORTAL_SOURCE, ignored -> {
            if (crossOwner && !enclose) {
                pig.portalProcess = new PortalProcessor((Portal) Blocks.END_GATEWAY, entry);
                manager.begin(pig);
                assertFalse(manager.suspendDestination(pig, pig.portalProcess),
                    "current PHOENIUM gateway selector falls through to synchronous native teleport");
            }
            try { returned.set(pig.teleport(transition)); }
            finally { if (crossOwner && !enclose) manager.finish(pig); }
        });
        manager.close();
        if (enclose) destinationOwner = f.br;
        int pendingAtReturn = f.identities.pendingRelocationCount();
        // Owner-free boundary: replay any retained foreign-owner ticket, then commit relocations.
        boundary.advance();
        int pendingBefore = f.identities.pendingRelocationCount();
        f.identities.advancePendingRelocations(f.runtimes);
        var after = f.objects.current(uuid.toString()).orElseThrow();
        return new Control(pig, returned.get(), uuid, runtimeId, sourceKey, destination,
            f.br, destinationOwner, ticketOwner.get(), ticketBinding.get(),
            pendingAtReturn, ticketCalls.get(), List.copyOf(events),
            f.directory.resolve(unrelated).orElseThrow().region(),
            (int) boundary.deferredCount(), (int) boundary.placedCount(), (int) boundary.droppedCount(),
            boundary.pendingCount(), pendingBefore, f.identities.pendingRelocationCount(),
            after.owner(), after.routingKey());
    }

    private record Control(Pig pig, Entity returned, UUID uuid, int runtimeId,
                           RoutingKey sourceKey, RoutingKey destination,
                           RegionId sourceOwner, RegionId destinationOwner,
                           RegionId ownerAtTicket, RoutingKey bindingAtTicket,
                           int pendingRelocations, int ticketCalls, List<String> events,
                           RegionId unrelatedOwnerAfterEnclosure,
                           int deferredTickets, int placedTickets, int droppedTickets, int pendingTickets,
                           int pendingRelocationsBeforeCut, int pendingRelocationsAfterCut,
                           RegionId ownerAfterCut, RoutingKey bindingAfterCut) { }
}
