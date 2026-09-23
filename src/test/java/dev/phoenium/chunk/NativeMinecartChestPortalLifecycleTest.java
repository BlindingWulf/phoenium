package dev.phoenium.chunk;

import dev.phoenium.live.MinecartPortalTransitionManager;
import dev.phoenium.service.RegionalServiceKind;
import java.util.List;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.vehicle.minecart.MinecartChest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** Exact chest-cart continuation: inventory/loot restore and source-owned abstract tick tail. */
class NativeMinecartChestPortalLifecycleTest {
    @BeforeAll static void bootstrap() { NativeItemPortalTransferTest.bootstrap(); }

    @AfterEach void clear() {
        NativeMinecartChestTestHooks.afterAbstract = ignored -> { };
        NativeMinecartTestHooks.afterOuter = (ignored, rot, pos) -> { };
        PortalDestinationTestHooks.mobs = null;
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void nonemptyChestRetainsNativeReplacementAndOnlyInheritedSourceTail(boolean forward) throws Exception {
        var fixture = new NativeItemPortalTransferTest.Fixture();
        var manager = new MinecartPortalTransitionManager(fixture.authority, fixture.objects);
        var sourceLevel = forward ? fixture.overworld : fixture.nether;
        var destinationLevel = forward ? fixture.nether : fixture.overworld;
        var sourceKey = fixture.key(sourceLevel);
        MinecartChest cart = EntityTypes.CHEST_MINECART.create(sourceLevel, EntitySpawnReason.COMMAND);
        assertNotNull(cart);
        cart.setPos(.5, 66, .5);
        cart.setItem(2, new ItemStack(Items.DIAMOND, 7));
        cart.setItem(26, new ItemStack(Items.IRON_INGOT, 13));
        fixture.publish(cart, sourceKey);
        NativeMinecartChestTestHooks.afterAbstract = source -> {
            assertSame(cart, source);
            fixture.owner(sourceKey);
            assertEquals(7, source.getItem(2).getCount());
            fixture.order.add("minecart-chest-abstract-tail");
        };
        NativeMinecartTestHooks.afterOuter = (source, rot, pos) -> fail("plain cart outer tail on chest");
        NativePortalDestinationPreparationTest.setPortal(cart,
            ignored -> fixture.transition(destinationLevel));
        capture(fixture, manager, cart, sourceKey);
        assertEquals(1, manager.advance());
        assertEquals(0, manager.pendingCount());
        assertTrue(cart.isRemoved());
        assertInstanceOf(MinecartChest.class, fixture.replacement);
        MinecartChest replacement = (MinecartChest) fixture.replacement;
        assertSame(destinationLevel, replacement.level());
        assertEquals(cart.getUUID(), replacement.getUUID());
        assertNotSame(cart, replacement);
        assertTrue(ItemStack.matches(new ItemStack(Items.DIAMOND, 7), replacement.getItem(2)));
        assertTrue(ItemStack.matches(new ItemStack(Items.IRON_INGOT, 13), replacement.getItem(26)));
        assertEquals(1, fixture.objects.liveCount());
        assertFalse(fixture.visible.get(sourceLevel).containsKey(cart.getUUID()));
        assertSame(replacement, fixture.visible.get(destinationLevel).get(cart.getUUID()));
        assertEquals(List.of("source-retire", "insert", "post", "minecart-chest-abstract-tail"), fixture.order);
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void ungeneratedLootTableAndSeedRemainIntactWithoutUnpacking(boolean forward) throws Exception {
        var fixture = new NativeItemPortalTransferTest.Fixture();
        var manager = new MinecartPortalTransitionManager(fixture.authority, fixture.objects);
        var sourceLevel = forward ? fixture.overworld : fixture.nether;
        var destinationLevel = forward ? fixture.nether : fixture.overworld;
        var sourceKey = fixture.key(sourceLevel);
        MinecartChest cart = EntityTypes.CHEST_MINECART.create(sourceLevel, EntitySpawnReason.COMMAND);
        assertNotNull(cart);
        cart.setPos(.5, 66, .5);
        cart.setLootTable(BuiltInLootTables.SIMPLE_DUNGEON, 0x1234L);
        fixture.publish(cart, sourceKey);
        NativeMinecartChestTestHooks.afterAbstract = source -> {
            fixture.owner(sourceKey);
            assertSame(BuiltInLootTables.SIMPLE_DUNGEON, source.getContainerLootTable());
            fixture.order.add("minecart-chest-abstract-tail");
        };
        NativePortalDestinationPreparationTest.setPortal(cart,
            ignored -> fixture.transition(destinationLevel));
        capture(fixture, manager, cart, sourceKey);
        assertEquals(1, manager.advance());
        MinecartChest replacement = assertInstanceOf(MinecartChest.class, fixture.replacement);
        assertEquals(BuiltInLootTables.SIMPLE_DUNGEON, replacement.getContainerLootTable());
        assertEquals(0x1234L, replacement.getContainerLootTableSeed());
        assertEquals(List.of("source-retire", "insert", "post", "minecart-chest-abstract-tail"), fixture.order);
    }

    @Test void changedContentsBeforeNativeResumeCancelTheCapturedGeneration() throws Exception {
        var fixture = new NativeItemPortalTransferTest.Fixture();
        var manager = new MinecartPortalTransitionManager(fixture.authority, fixture.objects);
        MinecartChest cart = EntityTypes.CHEST_MINECART.create(fixture.overworld, EntitySpawnReason.COMMAND);
        assertNotNull(cart);
        cart.setPos(.5, 66, .5);
        cart.setItem(2, new ItemStack(Items.DIAMOND, 7));
        fixture.publish(cart, fixture.from);
        NativePortalDestinationPreparationTest.setPortal(cart,
            ignored -> fixture.transition(fixture.nether));
        capture(fixture, manager, cart, fixture.from);
        fixture.dispatcher.executeAndDrain(fixture.from, RegionalServiceKind.CHUNK_GAMEPLAY,
            ignored -> cart.setItem(2, new ItemStack(Items.DIAMOND, 8)));
        assertEquals(0, manager.advance());
        assertEquals(0, manager.pendingCount());
        assertFalse(cart.isRemoved());
        assertNull(fixture.replacement);
        assertSame(cart, fixture.visible.get(fixture.overworld).get(cart.getUUID()));
    }

    @Test void nativeNoDestinationContinuesOnlyOriginalChestUnderSourceOwner() throws Exception {
        var fixture = new NativeItemPortalTransferTest.Fixture();
        var manager = new MinecartPortalTransitionManager(fixture.authority, fixture.objects);
        MinecartChest cart = EntityTypes.CHEST_MINECART.create(fixture.overworld, EntitySpawnReason.COMMAND);
        assertNotNull(cart);
        cart.setPos(.5, 66, .5);
        fixture.publish(cart, fixture.from);
        NativeMinecartChestTestHooks.afterAbstract = source -> {
            fixture.owner(fixture.from);
            assertSame(cart, source);
            fixture.order.add("minecart-chest-abstract-tail");
        };
        NativePortalDestinationPreparationTest.setPortal(cart, ignored -> null);
        capture(fixture, manager, cart, fixture.from);
        assertEquals(1, manager.advance());
        assertFalse(cart.isRemoved());
        assertNull(fixture.replacement);
        assertEquals(List.of("minecart-chest-abstract-tail"), fixture.order);
    }

    @Test void furnaceSubclassRemainsExcludedUntilItsConcreteFuelTailIsRetained() throws Exception {
        var fixture = new NativeItemPortalTransferTest.Fixture();
        var manager = new MinecartPortalTransitionManager(fixture.authority, fixture.objects);
        var furnace = EntityTypes.FURNACE_MINECART.create(fixture.overworld, EntitySpawnReason.COMMAND);
        assertNotNull(furnace);
        furnace.setPos(.5, 66, .5);
        fixture.publish(furnace, fixture.from);
        NativePortalDestinationPreparationTest.setPortal(furnace,
            ignored -> fixture.transition(fixture.nether));
        fixture.dispatcher.executeAndDrain(fixture.from, RegionalServiceKind.CHUNK_GAMEPLAY, ignored -> {
            manager.begin(furnace);
            assertFalse(manager.suspendDestination(furnace, furnace.portalProcess));
            manager.finish(furnace);
        });
        assertEquals(0, manager.pendingCount());
    }

    private static void capture(NativeItemPortalTransferTest.Fixture fixture,
            MinecartPortalTransitionManager manager, MinecartChest cart,
            dev.phoenium.topology.RoutingKey sourceKey) {
        fixture.dispatcher.executeAndDrain(sourceKey, RegionalServiceKind.CHUNK_GAMEPLAY, ignored -> {
            manager.begin(cart);
            assertTrue(manager.suspendDestination(cart, cart.portalProcess));
            assertTrue(manager.complete(cart));
            manager.finish(cart);
        });
        assertEquals(1, manager.pendingCount());
    }
}
