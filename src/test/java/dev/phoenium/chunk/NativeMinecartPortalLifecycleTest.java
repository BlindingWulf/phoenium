package dev.phoenium.chunk;

import dev.phoenium.live.MinecartPortalTransitionManager;
import dev.phoenium.service.RegionalServiceKind;
import java.util.List;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.vehicle.minecart.Minecart;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** Exact empty plain Minecart native replacement in both Nether directions. */
class NativeMinecartPortalLifecycleTest {
    @BeforeAll static void bootstrap() { NativeItemPortalTransferTest.bootstrap(); }

    @AfterEach void clear() {
        NativeMinecartTestHooks.afterAbstract = ignored -> { };
        NativeMinecartTestHooks.afterOuter = (ignored, rot, pos) -> { };
        PortalDestinationTestHooks.mobs = null;
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void emptyPlainMinecartRetainsNativeReplacementAndBothSourceTails(boolean forward) throws Exception {
        var fixture = new NativeItemPortalTransferTest.Fixture();
        var manager = new MinecartPortalTransitionManager(fixture.authority, fixture.objects);
        var sourceLevel = forward ? fixture.overworld : fixture.nether;
        var destinationLevel = forward ? fixture.nether : fixture.overworld;
        var sourceKey = forward ? fixture.from : fixture.to;
        Minecart cart = EntityTypes.MINECART.create(sourceLevel, EntitySpawnReason.COMMAND);
        assertNotNull(cart);
        cart.setPos(.5, 66, .5);
        fixture.publish(cart, sourceKey);
        double initialYRot = cart.getYRot();
        var initialPos = cart.position();
        NativeMinecartTestHooks.afterAbstract = source -> {
            assertSame(cart, source); fixture.owner(sourceKey); fixture.order.add("minecart-abstract-tail");
        };
        NativeMinecartTestHooks.afterOuter = (source, rot, pos) -> {
            assertSame(cart, source); fixture.owner(sourceKey);
            assertEquals(initialYRot, rot); assertSame(initialPos, pos);
            fixture.order.add("minecart-outer-tail");
        };
        NativePortalDestinationPreparationTest.setPortal(cart,
            args -> fixture.transition(destinationLevel));
        fixture.dispatcher.executeAndDrain(sourceKey, RegionalServiceKind.CHUNK_GAMEPLAY, ignored -> {
            manager.begin(cart);
            assertTrue(manager.suspendDestination(cart, cart.portalProcess));
            assertTrue(manager.complete(cart));
            manager.finish(cart);
        });
        assertEquals(1, manager.pendingCount());
        assertEquals(1, manager.advance());
        assertEquals(0, manager.pendingCount());
        assertTrue(cart.isRemoved());
        assertNotNull(fixture.replacement);
        assertSame(destinationLevel, fixture.replacement.level());
        assertEquals(cart.getUUID(), fixture.replacement.getUUID());
        assertNotSame(cart, fixture.replacement);
        assertEquals(1, fixture.objects.liveCount());
        assertFalse(fixture.visible.get(sourceLevel).containsKey(cart.getUUID()));
        assertSame(fixture.replacement, fixture.visible.get(destinationLevel).get(cart.getUUID()));
        assertEquals(List.of("source-retire", "insert", "post", "minecart-abstract-tail",
            "minecart-outer-tail"), fixture.order);
    }
}
