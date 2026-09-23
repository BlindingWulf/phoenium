package dev.phoenium.chunk;

import dev.phoenium.live.CrossDimensionReplacementBoundary;
import dev.phoenium.live.NativePortalRestoreAccess;
import dev.phoenium.live.NativePortalTransfer;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static dev.phoenium.chunk.FullDecisionWitnessSupport.set;
import static org.junit.jupiter.api.Assertions.*;

/** Native save/load plus the three concrete 26.2 restoreFrom reference overrides. */
class NativeRestoreReferenceSplitTest {
    @BeforeAll static void bootstrap() { NativeItemPortalTransferTest.bootstrap(); }

    @Test void itemThrowerKeepsTheExactLiveReferenceAfterDestinationLoad() throws Exception {
        var fixture = new NativeItemPortalTransferTest.Fixture();
        var item = new ItemEntity(fixture.overworld, .5, 65, .5, new ItemStack(Items.STONE));
        var thrower = new ItemEntity(fixture.overworld, 1.5, 65, .5, new ItemStack(Items.DIRT));
        item.setThrower(thrower);
        var reference = ((NativePortalRestoreAccess) item).phoenium$capturePortalRestoreReference();
        assertNotNull(reference);
        assertSame(thrower, item.getOwner());
        var replacement = transfer(fixture, item);
        assertInstanceOf(ItemEntity.class, replacement);
        assertSame(reference, ((NativePortalRestoreAccess) replacement).phoenium$capturePortalRestoreReference());
    }

    @Test void primedTntOwnerKeepsTheExactReferenceAfterDestinationLoad() throws Exception {
        var fixture = new NativeItemPortalTransferTest.Fixture();
        var tnt = EntityTypes.TNT.create(fixture.overworld, EntitySpawnReason.COMMAND);
        assertNotNull(tnt);
        var reference = EntityReference.of(UUID.randomUUID());
        ((NativePortalRestoreAccess) tnt).phoenium$installPortalRestoreReference(reference);
        assertSame(reference, ((NativePortalRestoreAccess) tnt).phoenium$capturePortalRestoreReference());
        var replacement = transfer(fixture, tnt);
        assertInstanceOf(PrimedTnt.class, replacement);
        assertSame(reference, ((NativePortalRestoreAccess) replacement).phoenium$capturePortalRestoreReference());
    }

    @Test void vexOwnerKeepsTheExactReferenceAfterDestinationLoad() throws Exception {
        var fixture = new NativeItemPortalTransferTest.Fixture();
        configureMobWorlds(fixture);
        var vex = EntityTypes.VEX.create(fixture.overworld, EntitySpawnReason.COMMAND);
        assertNotNull(vex);
        var owner = EntityTypes.VEX.create(fixture.overworld, EntitySpawnReason.COMMAND);
        assertNotNull(owner);
        vex.setOwner(owner);
        var reference = ((NativePortalRestoreAccess) vex).phoenium$capturePortalRestoreReference();
        assertNotNull(reference);
        assertSame(owner, vex.getOwnerReference().getEntity(fixture.overworld, net.minecraft.world.entity.LivingEntity.class));
        var replacement = transfer(fixture, vex);
        assertInstanceOf(Vex.class, replacement);
        assertSame(reference, ((NativePortalRestoreAccess) replacement).phoenium$capturePortalRestoreReference());
    }

    private static void configureMobWorlds(NativeItemPortalTransferTest.Fixture fixture) throws Exception {
        NativePortalDestinationPreparationTest.installNativeLookup(fixture);
        fixture.levelData = (net.minecraft.world.level.storage.LevelData) java.lang.reflect.Proxy.newProxyInstance(
            NativeRestoreReferenceSplitTest.class.getClassLoader(),
            new Class<?>[]{net.minecraft.world.level.storage.ServerLevelData.class},
            (proxy, method, args) -> method.getName().equals("getDifficulty") ? Difficulty.NORMAL : null);
        RegistryAccess registries = NativePortalDestinationPreparationTest.dynamicRegistries();
        set(fixture.overworld, Level.class, "registryAccess", registries);
        set(fixture.nether, Level.class, "registryAccess", registries);
        var environment = EnvironmentAttributeSystem.builder().build();
        set(fixture.overworld, net.minecraft.server.level.ServerLevel.class, "environmentAttributes", environment);
        set(fixture.nether, net.minecraft.server.level.ServerLevel.class, "environmentAttributes", environment);
    }

    private static Entity transfer(NativeItemPortalTransferTest.Fixture fixture, Entity source) {
        source.setPos(.5, 65, .5);
        fixture.publish(source, fixture.from);
        var binding = fixture.objects.current(source.getUUID().toString()).orElseThrow();
        var boundary = new CrossDimensionReplacementBoundary();
        var token = boundary.begin(new CrossDimensionReplacementBoundary.SourceIdentity(
            binding.objectId(), ((FullEntityFacts.Access) source).phoenium$entityIncarnation(), binding.incarnation()));
        boundary.prepared(token);
        var transfer = new NativePortalTransfer(fixture.authority, fixture.objects, boundary, token,
            source, binding, fixture.transition(fixture.nether), () -> true);
        Entity replacement = transfer.run();
        assertNotNull(replacement);
        assertNotSame(source, replacement);
        assertSame(fixture.nether, replacement.level());
        assertEquals(source.getUUID(), replacement.getUUID());
        assertTrue(source.isRemoved());
        assertEquals(1, fixture.objects.liveCount());
        assertSame(replacement, fixture.visible.get(fixture.nether).get(source.getUUID()));
        return replacement;
    }
}
