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
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static dev.phoenium.chunk.FullDecisionWitnessSupport.set;
import static org.junit.jupiter.api.Assertions.*;

/** Pinned 26.2 native EndGatewayBlock transition and Entity.teleportSameDimension control. */
class NativeEndGatewaySameDimensionTest {
    @BeforeAll static void bootstrap() { NativeItemPortalTransferTest.bootstrap(); }
    @AfterEach void resetTicket() {
        NativePortalTestHooks.ticket = entity -> entity.placePortalTicket(BlockPos.containing(entity.position()));
    }

    @Test void sameOwnerNativeGatewayKeepsOneObjectAndPlacesTicketAfterMove() throws Exception {
        var result = nativeGateway(false, false);
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

    @Test void nativeCrossOwnerGatewayTicketPrecedesOwnerFreeRelocationCut() throws Exception {
        var result = nativeGateway(true, false);
        assertEquals(List.of("move", "ticket"), result.events());
        assertSame(result.pig(), result.returned(), "native same-dimension teleport returns this");
        assertEquals(result.uuid(), result.returned().getUUID());
        assertEquals(result.runtimeId(), result.returned().getId());
        assertEquals(new Vec3(160.5, 65.0, 160.5), result.returned().position());
        assertEquals(1, result.ticketCalls(), "the exact PLACE_PORTAL_TICKET callback ran");
        assertEquals(result.sourceOwner(), result.ownerAtTicket(),
            "native callback still runs inside the source owner turn");
        assertNotEquals(result.destinationOwner(), result.ownerAtTicket(),
            "explicit A/B topology exposes a wrong-owner native gateway callback");
        assertEquals(result.sourceKey(), result.bindingAtTicket(),
            "the same-object relocation remains uncommitted when the native callback fires");
        assertEquals(1, result.pendingRelocations());
    }

    /** Intentionally red opt-in acceptance assertion; keep its XML outside the default suite. */
    @Test
    @EnabledIfEnvironmentVariable(named = "PHOENIUM_GATEWAY_STRICT", matches = "true")
    void strictCrossOwnerGatewayTicketMustRunUnderDestinationOwner() throws Exception {
        var result = nativeGateway(true, false);
        assertEquals(result.destinationOwner(), result.ownerAtTicket(),
            "native End gateway fires PLACE_PORTAL_TICKET under A before the same-object A/B relocation cut");
    }

    @Test void ownerFreeTopologyEnclosureMakesTicketOwnedButMigratesUnrelatedRegion() throws Exception {
        var result = nativeGateway(true, true);
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

    private static Control nativeGateway(boolean crossOwner, boolean enclose) throws Exception {
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
        return new Control(pig, returned.get(), uuid, runtimeId, sourceKey, destination,
            f.br, destinationOwner, ticketOwner.get(), ticketBinding.get(),
            f.identities.pendingRelocationCount(), ticketCalls.get(), List.copyOf(events),
            f.directory.resolve(unrelated).orElseThrow().region());
    }

    private record Control(Pig pig, Entity returned, UUID uuid, int runtimeId,
                           RoutingKey sourceKey, RoutingKey destination,
                           RegionId sourceOwner, RegionId destinationOwner,
                           RegionId ownerAtTicket, RoutingKey bindingAtTicket,
                           int pendingRelocations, int ticketCalls, List<String> events,
                           RegionId unrelatedOwnerAfterEnclosure) { }
}
