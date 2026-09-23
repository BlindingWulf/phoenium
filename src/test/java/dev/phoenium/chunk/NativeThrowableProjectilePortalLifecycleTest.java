package dev.phoenium.chunk;

import dev.phoenium.live.ThrowableProjectilePortalTransitionManager;
import dev.phoenium.service.RegionalServiceKind;
import java.util.Collections;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.projectile.ThrowableProjectile;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEgg;
import net.minecraft.world.entity.projectile.throwableitemprojectile.Snowball;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownExperienceBottle;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownLingeringPotion;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownSplashPotion;
import net.minecraft.world.phys.BlockHitResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.*;

class NativeThrowableProjectilePortalLifecycleTest {
    @BeforeAll static void bootstrap() { NativeItemPortalTransferTest.bootstrap(); }
    @AfterEach void resetHooks() {
        NativePortalTestHooks.projectile = entity -> { };
        NativePortalTestHooks.hit = (entity, result) -> { };
    }

    @Test void selectionIsExactAndExcludesPearlArrowAndHurtingProjectile() {
        for (Class<?> accepted : new Class<?>[]{ThrownEgg.class, Snowball.class,
                ThrownSplashPotion.class, ThrownLingeringPotion.class, ThrownExperienceBottle.class})
            assertTrue(ThrowableProjectilePortalTransitionManager.reviewedCaller(accepted), accepted::getName);
        for (Class<?> excluded : new Class<?>[]{ThrownEnderpearl.class, Arrow.class,
                LargeFireball.class, ThrowableProjectile.class})
            assertFalse(ThrowableProjectilePortalTransitionManager.reviewedCaller(excluded), excluded::getName);
        assertFalse(ThrowableProjectilePortalTransitionManager.reviewedCaller(null));
    }

    @ParameterizedTest @CsvSource({
        "egg,true", "egg,false", "snowball,true", "snowball,false",
        "splash_potion,true", "splash_potion,false", "lingering_potion,true", "lingering_potion,false",
        "experience_bottle,true", "experience_bottle,false"
    })
    void retainedNativeTransferPreservesOwnerAndEachTickSuffixOnce(String type, boolean forward) throws Exception {
        var fixture = new NativeItemPortalTransferTest.Fixture();
        var manager = new ThrowableProjectilePortalTransitionManager(fixture.authority, fixture.objects);
        var sourceLevel = forward ? fixture.overworld : fixture.nether;
        var destinationLevel = forward ? fixture.nether : fixture.overworld;
        var sourceKey = forward ? fixture.from : fixture.to;
        var entityType = switch (type) {
            case "egg" -> EntityTypes.EGG;
            case "snowball" -> EntityTypes.SNOWBALL;
            case "splash_potion" -> EntityTypes.SPLASH_POTION;
            case "lingering_potion" -> EntityTypes.LINGERING_POTION;
            case "experience_bottle" -> EntityTypes.EXPERIENCE_BOTTLE;
            default -> throw new IllegalArgumentException(type);
        };
        var projectile = (ThrowableProjectile) entityType.create(sourceLevel, EntitySpawnReason.COMMAND);
        assertNotNull(projectile);
        projectile.setPos(.5, 66, 2.5);
        fixture.publish(projectile, sourceKey);
        var hit = BlockHitResult.miss(projectile.position(), Direction.UP, BlockPos.ZERO);
        NativePortalTestHooks.projectile = entity -> {
            assertSame(projectile, entity); fixture.owner(sourceKey); fixture.order.add("projectile-suffix");
        };
        NativePortalTestHooks.hit = (entity, result) -> {
            assertSame(projectile, entity); assertSame(hit, result);
            fixture.owner(sourceKey); fixture.order.add("throwable-suffix");
        };
        NativePortalDestinationPreparationTest.setPortal(projectile, args -> fixture.transition(destinationLevel));
        fixture.dispatcher.executeAndDrain(sourceKey, RegionalServiceKind.CHUNK_GAMEPLAY, ignored -> {
            manager.begin(projectile);
            manager.hitResult(projectile, hit);
            assertTrue(manager.suspendDestination(projectile, projectile.portalProcess));
            assertTrue(manager.complete(projectile));
            manager.finish(projectile);
        });
        assertEquals(1, manager.pendingCount());
        assertEquals(1, manager.advance());
        assertEquals(0, manager.pendingCount());
        assertTrue(projectile.isRemoved());
        assertSame(destinationLevel, fixture.replacement.level());
        assertEquals(projectile.getUUID(), fixture.replacement.getUUID());
        assertEquals(1, fixture.objects.liveCount());
        assertFalse(fixture.visible.get(sourceLevel).containsKey(projectile.getUUID()));
        assertSame(fixture.replacement, fixture.visible.get(destinationLevel).get(projectile.getUUID()));
        assertEquals(1, Collections.frequency(fixture.order, "projectile-suffix"));
        assertEquals(1, Collections.frequency(fixture.order, "throwable-suffix"));
        assertTrue(fixture.order.indexOf("projectile-suffix") < fixture.order.indexOf("throwable-suffix"));
    }

    @Test void destinationCannotSuspendWithoutThePrecedingHitResult() throws Exception {
        var fixture = new NativeItemPortalTransferTest.Fixture();
        var manager = new ThrowableProjectilePortalTransitionManager(fixture.authority, fixture.objects);
        var egg = EntityTypes.EGG.create(fixture.overworld, EntitySpawnReason.COMMAND);
        assertNotNull(egg);
        egg.setPos(.5, 66, 2.5);
        fixture.publish(egg, fixture.from);
        NativePortalDestinationPreparationTest.setPortal(egg, args -> fixture.transition(fixture.nether));
        fixture.dispatcher.executeAndDrain(fixture.from, RegionalServiceKind.CHUNK_GAMEPLAY, ignored -> {
            manager.begin(egg);
            assertFalse(manager.suspendDestination(egg, egg.portalProcess));
            assertFalse(manager.complete(egg));
            manager.finish(egg);
        });
        assertEquals(0, manager.pendingCount());
        assertFalse(egg.isRemoved());
        assertEquals(1, fixture.objects.liveCount());
    }
}
