package dev.phoenium.chunk;

import dev.phoenium.service.RegionalServiceKind;
import dev.phoenium.live.HurtingProjectilePortalTransitionManager;
import dev.phoenium.live.NativePortalRestoreAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.phys.BlockHitResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

/** Reproduces the preserved Nether producer entering Overworld tracking. */
class NativeFireballPortalLifecycleTest {
    @BeforeAll static void bootstrap() { NativeItemPortalTransferTest.bootstrap(); }
    @AfterEach void resetHooks() {
        NativePortalTestHooks.projectile = entity -> { };
        NativePortalTestHooks.hurting = entity -> { };
        NativePortalTestHooks.windCharge = entity -> { };
    }

    @Test void unretainedLargeFireballReachesDestinationTrackingUnderNetherOwner() throws Exception {
        var fixture = new NativeItemPortalTransferTest.Fixture();
        var fireball = EntityTypes.FIREBALL.create(fixture.nether, EntitySpawnReason.COMMAND);
        assertNotNull(fireball);
        fireball.setPos(.73, 66.19, -.34);
        fixture.publish(fireball, fixture.to);

        var failure = assertThrows(IllegalStateException.class, () ->
            fixture.dispatcher.executeAndDrain(fixture.to, RegionalServiceKind.CHUNK_GAMEPLAY,
                ignored -> fireball.teleport(fixture.transition(fixture.overworld))));

        assertTrue(failure.getMessage().contains("foreign owner")
                || failure.getMessage().contains("Current owner")
                || failure.getMessage().contains("current owner"), failure::toString);
    }

    @Test void retainedLargeFireballReturnsWithOneOverworldIdentityAndNativeSuffixOrder() throws Exception {
        var fixture = new NativeItemPortalTransferTest.Fixture();
        var manager = new HurtingProjectilePortalTransitionManager(fixture.authority, fixture.objects);
        var fireball = EntityTypes.FIREBALL.create(fixture.nether, EntitySpawnReason.COMMAND);
        assertNotNull(fireball);
        var owner = new net.minecraft.world.entity.item.ItemEntity(fixture.nether,
            .25, 66, .25, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE));
        fireball.setOwner(owner);
        var ownerReference = ((NativePortalRestoreAccess) fireball)
            .phoenium$capturePortalRestoreReference();
        assertNotNull(ownerReference);
        fireball.setPos(.73, 66.19, -.34);
        fixture.publish(fireball, fixture.to);
        NativePortalTestHooks.projectile = entity -> {
            assertSame(fireball, entity); fixture.owner(fixture.to); fixture.order.add("projectile-suffix");
        };
        NativePortalTestHooks.hurting = entity -> {
            assertSame(fireball, entity); fixture.owner(fixture.to); fixture.order.add("hurting-suffix");
        };
        var hit = BlockHitResult.miss(fireball.position(), Direction.UP, BlockPos.ZERO);
        NativePortalDestinationPreparationTest.setPortal(fireball, args -> {
            assertTrue(dev.phoenium.ownership.OwnershipContext.current().isEmpty());
            return fixture.transition(fixture.overworld);
        });
        fixture.dispatcher.executeAndDrain(fixture.to, RegionalServiceKind.CHUNK_GAMEPLAY, ignored -> {
            manager.begin(fireball);
            manager.hitResult(fireball, hit);
            assertTrue(manager.suspendDestination(fireball, fireball.portalProcess));
            assertTrue(manager.complete(fireball));
            manager.finish(fireball);
        });
        assertEquals(1, manager.pendingCount());
        assertEquals(1, manager.advance());
        assertEquals(0, manager.pendingCount());
        assertTrue(fireball.isRemoved());
        Entity replacement = fixture.replacement;
        assertNotNull(replacement);
        assertSame(ownerReference, ((NativePortalRestoreAccess) replacement)
            .phoenium$capturePortalRestoreReference(),
            "Projectile.restoreFrom must retain the exact native owner reference after the split load");
        assertSame(fixture.overworld, replacement.level());
        assertEquals(fireball.getUUID(), replacement.getUUID());
        assertEquals(1, fixture.objects.liveCount());
        assertFalse(fixture.visible.get(fixture.nether).containsKey(fireball.getUUID()));
        assertSame(replacement, fixture.visible.get(fixture.overworld).get(fireball.getUUID()));
        assertEquals(java.util.List.of("source-retire", "insert", "post", "base-suffix",
            "projectile-suffix", "hurting-suffix"), fixture.order);
    }

    @ParameterizedTest @CsvSource({
        "fireball,true", "fireball,false", "small_fireball,true", "small_fireball,false",
        "dragon_fireball,true", "dragon_fireball,false", "wither_skull,true", "wither_skull,false",
        "wind_charge,true", "wind_charge,false", "breeze_wind_charge,true", "breeze_wind_charge,false"
    })
    void reviewedHurtingProjectileClassesUseOneRetainedLifecycleInBothNetherDirections(
            String type, boolean forward) throws Exception {
        var fixture = new NativeItemPortalTransferTest.Fixture();
        var manager = new HurtingProjectilePortalTransitionManager(fixture.authority, fixture.objects);
        var sourceLevel = forward ? fixture.overworld : fixture.nether;
        var destinationLevel = forward ? fixture.nether : fixture.overworld;
        var sourceKey = forward ? fixture.from : fixture.to;
        var entityType = switch (type) {
            case "fireball" -> EntityTypes.FIREBALL;
            case "small_fireball" -> EntityTypes.SMALL_FIREBALL;
            case "dragon_fireball" -> EntityTypes.DRAGON_FIREBALL;
            case "wither_skull" -> EntityTypes.WITHER_SKULL;
            case "wind_charge" -> EntityTypes.WIND_CHARGE;
            case "breeze_wind_charge" -> EntityTypes.BREEZE_WIND_CHARGE;
            default -> throw new IllegalArgumentException(type);
        };
        var projectile = (net.minecraft.world.entity.projectile.hurtingprojectile.AbstractHurtingProjectile)
            entityType.create(sourceLevel, EntitySpawnReason.COMMAND);
        assertNotNull(projectile);
        projectile.setPos(.5, 66, 2.5);
        fixture.publish(projectile, sourceKey);
        NativePortalTestHooks.projectile = entity -> {
            assertSame(projectile, entity); fixture.owner(sourceKey); fixture.order.add("projectile-suffix");
        };
        NativePortalTestHooks.hurting = entity -> {
            assertSame(projectile, entity); fixture.owner(sourceKey); fixture.order.add("hurting-suffix");
        };
        NativePortalTestHooks.windCharge = entity -> {
            assertSame(projectile, entity); fixture.owner(sourceKey); fixture.order.add("wind-charge-suffix");
        };
        NativePortalDestinationPreparationTest.setPortal(projectile, args -> fixture.transition(destinationLevel));
        var hit = BlockHitResult.miss(projectile.position(), Direction.UP, BlockPos.ZERO);
        fixture.dispatcher.executeAndDrain(sourceKey, RegionalServiceKind.CHUNK_GAMEPLAY, ignored -> {
            manager.begin(projectile);
            manager.hitResult(projectile, hit);
            assertTrue(manager.suspendDestination(projectile, projectile.portalProcess));
            assertTrue(manager.complete(projectile));
            manager.finish(projectile);
        });
        assertEquals(1, manager.advance());
        assertEquals(0, manager.pendingCount());
        assertTrue(projectile.isRemoved());
        assertSame(destinationLevel, fixture.replacement.level());
        assertEquals(projectile.getUUID(), fixture.replacement.getUUID());
        assertEquals(1, fixture.objects.liveCount());
        assertFalse(fixture.visible.get(sourceLevel).containsKey(projectile.getUUID()));
        assertSame(fixture.replacement, fixture.visible.get(destinationLevel).get(projectile.getUUID()));
        assertEquals(type.equals("wind_charge") ? 1 : 0,
            java.util.Collections.frequency(fixture.order,"wind-charge-suffix"));
        assertEquals(1, java.util.Collections.frequency(fixture.order,"projectile-suffix"));
        assertEquals(1, java.util.Collections.frequency(fixture.order,"hurting-suffix"));
    }
}
