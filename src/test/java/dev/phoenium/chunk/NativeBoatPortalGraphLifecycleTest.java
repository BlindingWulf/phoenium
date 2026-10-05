package dev.phoenium.chunk;

import dev.phoenium.live.BoatPortalTransitionManager;
import dev.phoenium.ownership.OwnershipContext;
import dev.phoenium.service.RegionalServiceKind;
import dev.phoenium.core.OwnerEpoch;
import dev.phoenium.core.TopologyGeneration;
import dev.phoenium.topology.RegionRoute;
import dev.phoenium.topology.RoutingKey;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.Map;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.ServerLevelData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static dev.phoenium.chunk.FullDecisionWitnessSupport.set;
import static dev.phoenium.chunk.FullDecisionWitnessSupport.stub;
import static org.junit.jupiter.api.Assertions.*;

/** Exact oak boat plus one pig, with native child-first transfer and owner-held tick suffix. */
class NativeBoatPortalGraphLifecycleTest {
    @BeforeAll static void bootstrap() { NativeItemPortalTransferTest.bootstrap(); }
    @AfterEach void clear() {
        BoatPortalTestHooks.suffix = null;
        NativePortalGraphTestHooks.graphTickGate = (entity, passenger) -> false;
        NativePortalGraphTestHooks.beforePassengerTransition = ignored -> { };
        PortalDestinationTestHooks.mobs = null;
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void exactBoatPigGraphTransfersInBothNetherDirections(boolean returning) throws Exception {
        var fixture = new NativeItemPortalTransferTest.Fixture();
        installWorldFacts(fixture);
        ServerLevel source = returning ? fixture.nether : fixture.overworld;
        ServerLevel destination = returning ? fixture.overworld : fixture.nether;
        var sourceKey = fixture.key(source);
        var boat = EntityTypes.OAK_BOAT.create(source, EntitySpawnReason.COMMAND);
        var pig = EntityTypes.PIG.create(source, EntitySpawnReason.COMMAND);
        assertNotNull(boat); assertNotNull(pig);
        boat.setPos(.44, 65.31, .02); pig.setPos(.44, 66.1, .02);
        fixture.publish(boat, sourceKey); fixture.publish(pig, sourceKey);
        NativePortalDestinationPreparationTest.setPortal(boat, args -> {
            assertTrue(OwnershipContext.current().isEmpty());
            return fixture.transition(destination);
        });
        var manager = new BoatPortalTransitionManager(fixture.authority, fixture.objects);
        BoatPortalTestHooks.suffix = retained -> {
            assertSame(boat, retained);
            fixture.owner(sourceKey);
            fixture.order.add("boat-suffix");
        };
        fixture.dispatcher.executeAndDrain(sourceKey, RegionalServiceKind.CHUNK_GAMEPLAY, ignored -> {
            assertTrue(pig.startRiding(boat, true, false));
            manager.begin(boat);
            try {
                assertTrue(manager.suspendDestination(boat, boat.portalProcess));
                assertTrue(manager.complete(boat));
            } finally { manager.finish(boat); }
        });
        assertEquals(1, manager.pendingCount());
        assertTrue(manager.graphTickSuspended(boat, false));
        assertTrue(manager.graphTickSuspended(pig, true));
        assertFalse(manager.graphTickSuspended(pig, false));
        fixture.ready = false;
        assertEquals(0, manager.advance(), "graph must wait for both destination members");
        assertSame(boat, pig.getVehicle());
        assertEquals(java.util.List.of(pig), boat.getPassengers());
        fixture.ready = true;
        assertEquals(1, manager.advance());
        assertEquals(0, manager.pendingCount());
        assertFalse(manager.graphTickSuspended(boat, false));
        assertFalse(manager.graphTickSuspended(pig, true));
        assertTrue(boat.isRemoved()); assertTrue(pig.isRemoved());
        Entity arrivedBoat = fixture.visible.get(destination).get(boat.getUUID());
        Entity arrivedPig = fixture.visible.get(destination).get(pig.getUUID());
        assertNotNull(arrivedBoat); assertNotNull(arrivedPig);
        assertNotSame(boat, arrivedBoat); assertNotSame(pig, arrivedPig);
        assertSame(arrivedBoat, arrivedPig.getVehicle());
        assertEquals(java.util.List.of(arrivedPig), arrivedBoat.getPassengers());
        assertFalse(fixture.visible.get(source).containsKey(boat.getUUID()));
        assertFalse(fixture.visible.get(source).containsKey(pig.getUUID()));
        assertEquals(2, fixture.objects.liveCount());
        assertEquals(2, Collections.frequency(fixture.order, "source-retire"));
        assertEquals(2, Collections.frequency(fixture.order, "insert"));
        assertEquals(2, Collections.frequency(fixture.order, "post"));
        assertEquals(1, Collections.frequency(fixture.order, "base-suffix"));
        assertEquals(1, Collections.frequency(fixture.order, "boat-suffix"));
        assertTrue(fixture.order.lastIndexOf("post") < fixture.order.indexOf("base-suffix"));
        assertTrue(fixture.order.indexOf("base-suffix") < fixture.order.indexOf("boat-suffix"));
    }

    /** The first reviewed bound (one oak boat, one pig) is gone: any non-player graph is captured like the original. */
    @Test void chestBoatPassengerGraphIsCapturedAndCancelsCleanlyWhenUnretained() throws Exception {
        var fixture = new NativeItemPortalTransferTest.Fixture();
        installWorldFacts(fixture);
        var boat = EntityTypes.OAK_CHEST_BOAT.create(fixture.overworld, EntitySpawnReason.COMMAND);
        var pig = EntityTypes.PIG.create(fixture.overworld, EntitySpawnReason.COMMAND);
        assertNotNull(boat); assertNotNull(pig);
        boat.setPos(.44, 65.31, .02); pig.setPos(.44, 66.1, .02);
        fixture.publish(boat, fixture.from); fixture.publish(pig, fixture.from);
        NativePortalDestinationPreparationTest.setPortal(boat,
            args -> fixture.transition(fixture.nether));
        var manager = new BoatPortalTransitionManager(fixture.authority, fixture.objects);
        fixture.dispatcher.executeAndDrain(fixture.from, RegionalServiceKind.CHUNK_GAMEPLAY, ignored -> {
            assertTrue(pig.startRiding(boat, true, false));
            manager.begin(boat);
            try {
                assertTrue(manager.suspendDestination(boat, boat.portalProcess));
                assertEquals(1, manager.pendingCount());
            } finally { manager.finish(boat); }
        });
        assertEquals(0, manager.pendingCount(), "an unretained prefix is cancelled by finish");
        assertSame(boat, pig.getVehicle());
        assertFalse(boat.isRemoved()); assertFalse(pig.isRemoved());
    }

    @Test void exactChildRouteCanFollowNativeSameOwnerDismount() throws Exception {
        var fixture = new NativeItemPortalTransferTest.Fixture();
        installWorldFacts(fixture);
        RoutingKey moved = new RoutingKey(fixture.a, 1, 0);
        var generation = new TopologyGeneration(2);
        fixture.directory.publish(generation, Map.of(
            fixture.from, new RegionRoute(fixture.ar, generation, new OwnerEpoch(0)),
            moved, new RegionRoute(fixture.ar, generation, new OwnerEpoch(0)),
            fixture.to, new RegionRoute(fixture.br, generation, new OwnerEpoch(0))));
        var boat = EntityTypes.OAK_BOAT.create(fixture.overworld, EntitySpawnReason.COMMAND);
        var pig = EntityTypes.PIG.create(fixture.overworld, EntitySpawnReason.COMMAND);
        assertNotNull(boat); assertNotNull(pig);
        boat.setPos(15.98, 65.31, .02); pig.setPos(15.98, 66.1, .02);
        fixture.publish(boat, fixture.from); fixture.publish(pig, fixture.from);
        NativePortalDestinationPreparationTest.setPortal(boat,
            args -> fixture.transition(fixture.nether));
        NativePortalGraphTestHooks.beforePassengerTransition = child -> {
            assertSame(pig, child);
            fixture.owner(fixture.from);
            pig.setPos(16.02, 66.1, .02);
            fixture.objects.updateRoutingKey(pig.getUUID().toString(), moved,
                fixture.objects.current(pig.getUUID().toString()).orElseThrow().objectEpoch());
        };
        var manager = new BoatPortalTransitionManager(fixture.authority, fixture.objects);
        BoatPortalTestHooks.suffix = retained -> {
            assertSame(boat, retained);
            fixture.owner(fixture.from);
        };
        fixture.dispatcher.executeAndDrain(fixture.from, RegionalServiceKind.CHUNK_GAMEPLAY, ignored -> {
            assertTrue(pig.startRiding(boat, true, false));
            manager.begin(boat);
            try { assertTrue(manager.suspendDestination(boat, boat.portalProcess));
                assertTrue(manager.complete(boat)); }
            finally { manager.finish(boat); }
        });
        assertEquals(1, manager.advance());
        Entity arrivedBoat = fixture.visible.get(fixture.nether).get(boat.getUUID());
        Entity arrivedPig = fixture.visible.get(fixture.nether).get(pig.getUUID());
        assertNotNull(arrivedBoat); assertNotNull(arrivedPig);
        assertSame(arrivedBoat, arrivedPig.getVehicle());
        assertTrue(boat.isRemoved()); assertTrue(pig.isRemoved());
        assertFalse(fixture.visible.get(fixture.overworld).containsKey(pig.getUUID()));
        assertEquals(2, fixture.objects.liveCount());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void postEjectionFailureRepairsOnlyExactUnpublishedSourceGraph(boolean publishSameOwnerRoute)
            throws Exception {
        var fixture = new NativeItemPortalTransferTest.Fixture();
        installWorldFacts(fixture);
        RoutingKey moved = new RoutingKey(fixture.a, 1, 0);
        var generation = new TopologyGeneration(2);
        fixture.directory.publish(generation, Map.of(
            fixture.from, new RegionRoute(fixture.ar, generation, new OwnerEpoch(0)),
            moved, new RegionRoute(fixture.ar, generation, new OwnerEpoch(0)),
            fixture.to, new RegionRoute(fixture.br, generation, new OwnerEpoch(0))));
        var boat = EntityTypes.OAK_BOAT.create(fixture.overworld, EntitySpawnReason.COMMAND);
        var pig = EntityTypes.PIG.create(fixture.overworld, EntitySpawnReason.COMMAND);
        assertNotNull(boat); assertNotNull(pig);
        boat.setPos(15.98, 65.31, .02); pig.setPos(15.98, 66.1, .02);
        fixture.publish(boat, fixture.from); fixture.publish(pig, fixture.from);
        NativePortalDestinationPreparationTest.setPortal(boat, args ->
            fixture.transition(fixture.nether).withPosition(new net.minecraft.world.phys.Vec3(15.99, 65, 0)));
        NativePortalGraphTestHooks.beforePassengerTransition = child -> {
            assertSame(pig, child);
            fixture.owner(fixture.from);
            pig.setPos(16.02, 66.1, .02);
            if (publishSameOwnerRoute)
                fixture.objects.updateRoutingKey(pig.getUUID().toString(), moved,
                    fixture.objects.current(pig.getUUID().toString()).orElseThrow().objectEpoch());
        };
        var manager = new BoatPortalTransitionManager(fixture.authority, fixture.objects);
        fixture.dispatcher.executeAndDrain(fixture.from, RegionalServiceKind.CHUNK_GAMEPLAY, ignored -> {
            assertTrue(pig.startRiding(boat, true, false));
            manager.begin(boat);
            try { assertTrue(manager.suspendDestination(boat, boat.portalProcess));
                assertTrue(manager.complete(boat)); }
            finally { manager.finish(boat); }
        });
        IllegalStateException failure = assertThrows(IllegalStateException.class, manager::advance);
        assertEquals(0, manager.pendingCount());
        assertFalse(boat.isRemoved()); assertFalse(pig.isRemoved());
        assertNull(fixture.visible.get(fixture.nether).get(boat.getUUID()));
        assertNull(fixture.visible.get(fixture.nether).get(pig.getUUID()));
        if (publishSameOwnerRoute) {
            assertTrue(failure.getMessage().contains("prepared destination owner"));
            assertSame(boat, pig.getVehicle());
            assertEquals(java.util.List.of(pig), boat.getPassengers());
            assertEquals(moved, fixture.objects.current(pig.getUUID().toString()).orElseThrow().routingKey());
        } else {
            assertTrue(failure.getMessage().contains("ejection crossed source ownership"));
            assertNull(pig.getVehicle());
            assertTrue(java.util.Arrays.stream(failure.getSuppressed())
                .anyMatch(suppressed -> suppressed.getMessage().contains("PARTIAL_PORTAL_GRAPH")));
        }
    }

    private static void installWorldFacts(NativeItemPortalTransferTest.Fixture fixture) throws Exception {
        NativePortalDestinationPreparationTest.installNativeLookup(fixture);
        // Native boat ejection asks the source chunk for water before dismount.
        // The generic portal fixture has no chunk because standalone entities
        // never take this branch; provide only that exact environmental read.
        LevelChunk dryChunk = stub(LevelChunk.class, java.util.List.of(
            new FullDecisionWitnessSupport.Answer(
                LevelChunk.class.getMethod("getFluidState", net.minecraft.core.BlockPos.class),
                args -> Fluids.EMPTY.defaultFluidState())));
        fixture.chunkRead = args -> dryChunk;
        ServerLevelData data = (ServerLevelData) Proxy.newProxyInstance(
            NativeBoatPortalGraphLifecycleTest.class.getClassLoader(),
            new Class<?>[]{ServerLevelData.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getLevelName" -> "boat-graph-test";
                case "getDifficulty" -> Difficulty.NORMAL;
                default -> null;
            });
        fixture.levelData = data;
        set(fixture.overworld, ServerLevel.class, "serverLevelData", data);
        set(fixture.nether, ServerLevel.class, "serverLevelData", data);
        var registries = NativePortalDestinationPreparationTest.dynamicRegistries();
        set(fixture.overworld, Level.class, "registryAccess", registries);
        set(fixture.nether, Level.class, "registryAccess", registries);
    }
}
