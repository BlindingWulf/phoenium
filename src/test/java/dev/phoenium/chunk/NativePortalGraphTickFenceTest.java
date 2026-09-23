package dev.phoenium.chunk;

import dev.phoenium.live.MobPortalTransitionManager;
import dev.phoenium.service.RegionalServiceKind;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ServerLevelData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The same pinned ServerLevel tick method heads are transformed by the test agent. */
class NativePortalGraphTickFenceTest {
    @BeforeAll static void bootstrap() { NativeItemPortalTransferTest.bootstrap(); }
    @AfterEach void reset() {
        NativePortalGraphTestHooks.graphTickGate = (entity, passenger) -> false;
        PortalDestinationTestHooks.mobs = null;
    }

    @Test void delayedDestinationPausesCapturedRootAndRiderButNotUnrelatedTicks() throws Exception {
        var fixture = new NativeItemPortalTransferTest.Fixture();
        var manager = new MobPortalTransitionManager(fixture.authority, fixture.objects);
        NativePortalDestinationPreparationTest.installNativeLookup(fixture);
        ServerLevelData data = (ServerLevelData) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[]{ServerLevelData.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getLevelName" -> "graph-tick-fence";
                case "getDifficulty" -> Difficulty.NORMAL;
                default -> null;
            });
        fixture.levelData = data;
        FullDecisionWitnessSupport.set(fixture.overworld, ServerLevel.class, "serverLevelData", data);
        FullDecisionWitnessSupport.set(fixture.nether, ServerLevel.class, "serverLevelData", data);
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
        var rootPosition = pig.position();
        var childPosition = chicken.position();
        int rootTicks = pig.tickCount, childTicks = chicken.tickCount;
        fixture.ready = false;
        assertEquals(0, manager.advance(), "destination readiness must hold the graph pending");
        assertEquals(1, manager.pendingCount());
        assertTrue(manager.graphTickSuspended(pig, false));
        assertTrue(manager.graphTickSuspended(chicken, true));
        AtomicInteger unrelatedCalls = new AtomicInteger();
        Pig unrelated = new Pig(EntityTypes.PIG, fixture.overworld) {
            @Override public void tick() { unrelatedCalls.incrementAndGet(); }
        };
        unrelated.setPos(8.5, 65, 8.5);
        assertFalse(manager.graphTickSuspended(unrelated, false));
        Method nativePassengerTick = ServerLevel.class.getDeclaredMethod("tickPassenger", Entity.class, Entity.class);
        nativePassengerTick.setAccessible(true);
        NativePortalGraphTestHooks.graphTickGate = manager::graphTickSuspended;
        fixture.dispatcher.executeAndDrain(fixture.from, RegionalServiceKind.CHUNK_GAMEPLAY, ignored -> {
            fixture.overworld.tickNonPassenger(pig);
            try { nativePassengerTick.invoke(fixture.overworld, pig, chicken); }
            catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
            fixture.overworld.tickNonPassenger(unrelated);
        });
        assertEquals(rootTicks, pig.tickCount, "retained graph root received a native tick");
        assertEquals(childTicks, chicken.tickCount, "retained graph rider received a native ride tick");
        assertEquals(rootPosition, pig.position());
        assertEquals(childPosition, chicken.position());
        assertSame(pig, chicken.getVehicle());
        assertEquals(1, unrelatedCalls.get(), "unrelated entity tick was suppressed");
        assertEquals(1, unrelated.tickCount);
        fixture.ready = true;
        assertEquals(1, manager.advance(), "same captured graph must transfer after readiness resumes");
        Entity newPig = fixture.visible.get(fixture.nether).get(pig.getUUID());
        Entity newChicken = fixture.visible.get(fixture.nether).get(chicken.getUUID());
        assertNotNull(newPig); assertNotNull(newChicken);
        assertSame(newPig, newChicken.getVehicle());
        assertFalse(manager.graphTickSuspended(pig, false));
        assertFalse(manager.graphTickSuspended(chicken, true));
    }
}
