package dev.phoenium.chunk;

import dev.phoenium.live.ShulkerBulletPortalTransitionManager;
import dev.phoenium.service.RegionalServiceKind;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/** Both exact 26.2 ShulkerBullet.tick portal cuts use a retained native replacement. */
class NativeShulkerBulletPortalLifecycleTest {
    @BeforeAll static void bootstrap() { NativeItemPortalTransferTest.bootstrap(); }
    @AfterEach void clear() {
        NativePortalTestHooks.projectile = ignored -> { };
        NativeShulkerBulletTestHooks.afterProjectile = ignored -> { };
        NativeShulkerBulletTestHooks.directSuffix = (ignored, target, hit, movement) -> { };
        PortalDestinationTestHooks.mobs = null;
    }

    @ParameterizedTest @CsvSource({ "true,true", "true,false", "false,true", "false,false" })
    void inheritedAndDirectCutsRetainBothNetherDirections(boolean direct, boolean forward) throws Exception {
        var fixture = new NativeItemPortalTransferTest.Fixture();
        var manager = new ShulkerBulletPortalTransitionManager(fixture.authority, fixture.objects);
        var sourceLevel = forward ? fixture.overworld : fixture.nether;
        var destinationLevel = forward ? fixture.nether : fixture.overworld;
        var sourceKey = forward ? fixture.from : fixture.to;
        var bullet = EntityTypes.SHULKER_BULLET.create(sourceLevel, EntitySpawnReason.COMMAND);
        assertNotNull(bullet);
        bullet.setPos(.5, 66, 2.5);
        bullet.setDeltaMovement(0, 0, -.25);
        fixture.publish(bullet, sourceKey);
        Entity target = EntityTypes.SHULKER_BULLET.create(sourceLevel, EntitySpawnReason.COMMAND);
        assertNotNull(target);
        var hit = BlockHitResult.miss(bullet.position(), Direction.UP, BlockPos.ZERO);
        Vec3 movement = bullet.getDeltaMovement();
        NativePortalTestHooks.projectile = entity -> {
            assertSame(bullet, entity); fixture.owner(sourceKey); fixture.order.add("projectile-suffix");
        };
        NativeShulkerBulletTestHooks.afterProjectile = entity -> {
            assertSame(bullet, entity); fixture.owner(sourceKey); fixture.order.add("shulker-after-projectile");
        };
        NativeShulkerBulletTestHooks.directSuffix = (entity, capturedTarget, capturedHit, capturedMotion) -> {
            assertSame(bullet, entity); fixture.owner(sourceKey);
            assertSame(target, capturedTarget); assertSame(hit, capturedHit);
            assertEquals(movement, capturedMotion);
            fixture.order.add("shulker-direct-suffix");
        };
        NativePortalDestinationPreparationTest.setPortal(bullet, args -> fixture.transition(destinationLevel));
        fixture.dispatcher.executeAndDrain(sourceKey, RegionalServiceKind.CHUNK_GAMEPLAY, ignored -> {
            manager.begin(bullet);
            if (direct) manager.directLocals(bullet, target, hit, movement);
            assertTrue(manager.suspendDestination(bullet, bullet.portalProcess));
            assertTrue(manager.complete(bullet));
            manager.finish(bullet);
        });
        assertEquals(1, manager.pendingCount());
        assertEquals(1, manager.advance());
        assertEquals(0, manager.pendingCount());
        assertTrue(bullet.isRemoved());
        assertNotNull(fixture.replacement);
        assertSame(destinationLevel, fixture.replacement.level());
        assertEquals(bullet.getUUID(), fixture.replacement.getUUID());
        assertEquals(1, fixture.objects.liveCount());
        assertFalse(fixture.visible.get(sourceLevel).containsKey(bullet.getUUID()));
        assertSame(fixture.replacement, fixture.visible.get(destinationLevel).get(bullet.getUUID()));
        assertEquals(direct ? List.of("source-retire", "insert", "post", "shulker-direct-suffix")
            : List.of("source-retire", "insert", "post", "base-suffix", "projectile-suffix",
                "shulker-after-projectile"), fixture.order);
    }
}
