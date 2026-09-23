package dev.phoenium.chunk;

import dev.phoenium.live.BoatPortalTransitionManager;
import dev.phoenium.ownership.OwnershipContext;
import dev.phoenium.service.RegionalServiceKind;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.entity.vehicle.boat.AbstractChestBoat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.storage.loot.LootTable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static dev.phoenium.chunk.FullDecisionWitnessSupport.set;
import static org.junit.jupiter.api.Assertions.*;

/** Native passenger-free Boat/Raft transfer and exact source-owned suffix ordering. */
class NativeBoatPortalLifecycleTest {
    @BeforeAll static void bootstrap() { NativeItemPortalTransferTest.bootstrap(); }
    @AfterEach void clear() { BoatPortalTestHooks.suffix = null; }

    @ParameterizedTest
    @CsvSource({
        "oak_boat,false,none", "oak_boat,true,none",
        "bamboo_raft,false,none", "bamboo_raft,true,none",
        "oak_chest_boat,false,items", "oak_chest_boat,true,items",
        "oak_chest_boat,false,loot", "oak_chest_boat,true,loot",
        "bamboo_chest_raft,false,items", "bamboo_chest_raft,true,items",
        "bamboo_chest_raft,false,loot", "bamboo_chest_raft,true,loot"
    })
    void standaloneBoatReleasesOwnerBeforeDestinationLookupAndResumesSourceTail(
            String typeId, boolean returning, String storage) throws Exception {
        var fixture = new NativeItemPortalTransferTest.Fixture();
        NativePortalDestinationPreparationTest.installNativeLookup(fixture);
        var registries = NativePortalDestinationPreparationTest.dynamicRegistries();
        set(fixture.overworld, Level.class, "registryAccess", registries);
        set(fixture.nether, Level.class, "registryAccess", registries);
        ServerLevel source = returning ? fixture.nether : fixture.overworld;
        ServerLevel destination = returning ? fixture.overworld : fixture.nether;
        var type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse("minecraft:" + typeId));
        AbstractBoat boat = (AbstractBoat) type.create(source, EntitySpawnReason.COMMAND);
        assertNotNull(boat);
        ResourceKey<LootTable> lootTable = ResourceKey.create(Registries.LOOT_TABLE,
            Identifier.parse("minecraft:chests/simple_dungeon"));
        if (boat instanceof AbstractChestBoat chest) {
            if (storage.equals("items")) {
                chest.setItem(0, new ItemStack(Items.DIAMOND, 7));
                chest.setItem(26, new ItemStack(Items.SUGAR_CANE, 3));
            } else if (storage.equals("loot")) {
                chest.setContainerLootTable(lootTable);
                chest.setContainerLootTableSeed(123456789L);
            } else fail("chest boat case needs an explicit storage state");
        } else assertEquals("none", storage);
        boat.setPos(.44, 65.31, .02);
        fixture.publish(boat, fixture.key(source));
        NativePortalDestinationPreparationTest.setPortal(boat, args -> {
            assertTrue(OwnershipContext.current().isEmpty());
            ((Portal) Blocks.NETHER_PORTAL).getPortalDestination(source, boat, BlockPos.ZERO);
            return fixture.transition(destination);
        });
        var manager = new BoatPortalTransitionManager(fixture.authority, fixture.objects);
        BoatPortalTestHooks.suffix = retained -> {
            assertSame(boat, retained);
            fixture.owner(fixture.key(source));
            fixture.order.add("boat-suffix");
        };
        AtomicInteger reads = new AtomicInteger();
        fixture.chunkRead = args -> {
            reads.incrementAndGet();
            assertSame(ChunkStatus.EMPTY, args[2]);
            assertEquals(true, args[3]);
            assertTrue(OwnershipContext.current().isEmpty());
            return null;
        };
        fixture.dispatcher.executeAndDrain(fixture.key(source), RegionalServiceKind.CHUNK_GAMEPLAY, ignored -> {
            manager.begin(boat);
            try {
                assertTrue(manager.suspendDestination(boat, boat.portalProcess));
                assertTrue(manager.complete(boat));
            } finally {
                manager.finish(boat);
            }
        });
        assertEquals(0, reads.get(), "destination lookup ran before source owner release");
        assertEquals(1, manager.pendingCount());
        assertEquals(1, manager.advance());
        assertTrue(reads.get() > 0);
        assertTrue(boat.isRemoved());
        assertNotSame(boat, fixture.replacement);
        assertEquals(boat.getUUID(), fixture.replacement.getUUID());
        assertSame(fixture.replacement, fixture.visible.get(destination).get(boat.getUUID()));
        assertEquals(boat.getClass(), fixture.replacement.getClass(), "registered chest/raft class changed");
        if (fixture.replacement instanceof AbstractChestBoat chest) {
            if (storage.equals("items")) {
                assertTrue(chest.getItem(0).is(Items.DIAMOND));
                assertEquals(7, chest.getItem(0).getCount());
                assertTrue(chest.getItem(26).is(Items.SUGAR_CANE));
                assertEquals(3, chest.getItem(26).getCount());
                assertNull(chest.getContainerLootTable());
            } else {
                assertEquals(lootTable, chest.getContainerLootTable());
                assertEquals(123456789L, chest.getContainerLootTableSeed());
                assertTrue(chest.getItemStacks().get(0).isEmpty(),
                    "checking latent loot must not unpack the table");
            }
        }
        assertEquals(1, Collections.frequency(fixture.order, "insert"));
        assertEquals(1, Collections.frequency(fixture.order, "base-suffix"));
        assertEquals(1, Collections.frequency(fixture.order, "boat-suffix"));
        assertTrue(fixture.order.indexOf("post") < fixture.order.indexOf("base-suffix"));
        assertTrue(fixture.order.indexOf("base-suffix") < fixture.order.indexOf("boat-suffix"));
        assertEquals(0, manager.pendingCount());
    }
}
