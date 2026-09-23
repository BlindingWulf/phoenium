package dev.phoenium.chunk;

import dev.phoenium.live.MobPortalTransitionManager;
import dev.phoenium.service.RegionalServiceKind;
import java.util.Collections;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Original 26.2 Entity.teleport recursion with retained per-node source/destination effects. */
class NativePortalGraphLifecycleTest {
    @BeforeAll static void bootstrap() { NativeItemPortalTransferTest.bootstrap(); }
    @AfterEach void clear() { PortalDestinationTestHooks.mobs = null; }

    @Test void pigAndChickenUseNativeChildFirstTransferAndOrderedReattachment() throws Exception {
        var fixture = new NativeItemPortalTransferTest.Fixture();
        var manager = new MobPortalTransitionManager(fixture.authority, fixture.objects);
        NativePortalDestinationPreparationTest.installNativeLookup(fixture);
        var levelData = (net.minecraft.world.level.storage.ServerLevelData)
            java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{net.minecraft.world.level.storage.ServerLevelData.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getLevelName" -> "graph-test";
                    case "getDifficulty" -> net.minecraft.world.Difficulty.NORMAL;
                    default -> null;
                });
        fixture.levelData = levelData;
        FullDecisionWitnessSupport.set(fixture.overworld, net.minecraft.server.level.ServerLevel.class,
            "serverLevelData", levelData);
        FullDecisionWitnessSupport.set(fixture.nether, net.minecraft.server.level.ServerLevel.class,
            "serverLevelData", levelData);
        var registries = NativePortalDestinationPreparationTest.dynamicRegistries();
        FullDecisionWitnessSupport.set(fixture.overworld, Level.class, "registryAccess", registries);
        FullDecisionWitnessSupport.set(fixture.nether, Level.class, "registryAccess", registries);
        var pig = EntityTypes.PIG.create(fixture.overworld, EntitySpawnReason.COMMAND);
        var chicken = EntityTypes.CHICKEN.create(fixture.overworld, EntitySpawnReason.COMMAND);
        assertNotNull(pig); assertNotNull(chicken);
        pig.setPos(.44, 65.31, .02); chicken.setPos(.44, 66.1, .02);
        fixture.publish(pig, fixture.from); fixture.publish(chicken, fixture.from);
        NativePortalDestinationPreparationTest.setPortal(pig, args -> fixture.transition(fixture.nether));
        fixture.dispatcher.executeAndDrain(fixture.from, RegionalServiceKind.CHUNK_GAMEPLAY, ignored -> {
            assertTrue(chicken.startRiding(pig, true, false));
            manager.begin(pig);
            assertTrue(manager.suspendDestination(pig, pig.portalProcess));
            assertTrue(manager.complete(pig));
            manager.finish(pig);
        });
        assertEquals(1, manager.pendingCount());
        assertEquals(1, manager.advance());
        assertEquals(0, manager.pendingCount());
        assertTrue(pig.isRemoved()); assertTrue(chicken.isRemoved());
        Entity newPig = fixture.visible.get(fixture.nether).get(pig.getUUID());
        Entity newChicken = fixture.visible.get(fixture.nether).get(chicken.getUUID());
        assertNotNull(newPig); assertNotNull(newChicken);
        assertNotSame(pig, newPig); assertNotSame(chicken, newChicken);
        assertSame(newPig, newChicken.getVehicle());
        assertEquals(java.util.List.of(newChicken), newPig.getPassengers());
        assertEquals(2, fixture.objects.liveCount());
        assertFalse(fixture.visible.get(fixture.overworld).containsKey(pig.getUUID()));
        assertFalse(fixture.visible.get(fixture.overworld).containsKey(chicken.getUUID()));
        assertEquals(2, Collections.frequency(fixture.order, "source-retire"));
        assertEquals(2, Collections.frequency(fixture.order, "insert"));
        assertEquals(2, Collections.frequency(fixture.order, "post"));
        assertTrue(fixture.order.indexOf("source-retire") < fixture.order.indexOf("insert"));
        assertTrue(fixture.order.lastIndexOf("insert") < fixture.order.lastIndexOf("post"));
    }
}
