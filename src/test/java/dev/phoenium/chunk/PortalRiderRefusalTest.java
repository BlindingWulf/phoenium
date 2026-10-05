package dev.phoenium.chunk;

import dev.phoenium.live.PortalRiderRefusal;
import dev.phoenium.service.RegionalServiceKind;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Portal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static dev.phoenium.chunk.FullDecisionWitnessSupport.set;
import static org.junit.jupiter.api.Assertions.*;

/** The rider policy: a non-player vehicle carrying a player in an unsupported shape (nested or mixed) declines the portal exactly as native does for null. */
class PortalRiderRefusalTest {
    @BeforeAll static void bootstrap() { NativeItemPortalTransferTest.bootstrap(); }
    @AfterEach void reset() { if (PortalDestinationTestHooks.mobs != null) PortalDestinationTestHooks.mobs.close(); PortalDestinationTestHooks.mobs = null; }

    private static void ride(Entity vehicle, Entity... passengers) throws Exception {
        set(vehicle, Entity.class, "passengers", com.google.common.collect.ImmutableList.copyOf(passengers));
    }
    private static ServerPlayer allocatedPlayer() throws Exception {
        var player = (ServerPlayer) MinecraftLightReadFixture.allocate(ServerPlayer.class);
        set(player, Entity.class, "passengers", com.google.common.collect.ImmutableList.of());
        return player;
    }

    @Test void policyRefusesOnlyNonPlayerVehiclesThatCarryAPlayerAnywhereInTheirTree() throws Exception {
        var f = new NativeItemPortalTransferTest.Fixture();
        NativePortalDestinationPreparationTest.installNativeLookup(f);
        set(f.overworld, Level.class, "registryAccess", NativePortalDestinationPreparationTest.dynamicRegistries());
        set(f.overworld, net.minecraft.server.level.ServerLevel.class, "environmentAttributes",
            net.minecraft.world.attribute.EnvironmentAttributeSystem.builder().build());
        Pig pig = (Pig) EntityTypes.PIG.create(f.overworld, EntitySpawnReason.COMMAND);
        Pig inner = (Pig) EntityTypes.PIG.create(f.overworld, EntitySpawnReason.COMMAND);
        ServerPlayer player = allocatedPlayer();
        assertFalse(PortalRiderRefusal.refuses(pig), "an empty mount keeps the native path");
        ride(pig, inner);
        assertFalse(PortalRiderRefusal.refuses(pig), "non-player riders are handled by the graph machinery");
        ride(pig, player);
        assertFalse(PortalRiderRefusal.refuses(pig), "a mount carrying only leaf players is carried natively");
        ride(pig, player, allocatedPlayer());
        assertFalse(PortalRiderRefusal.refuses(pig), "several leaf players");
        ride(pig, inner); ride(inner, player);
        assertTrue(PortalRiderRefusal.refuses(pig), "nested rider");
        ride(pig, player, inner); ride(inner);
        assertTrue(PortalRiderRefusal.refuses(pig), "player mixed with a non-player passenger");
        assertFalse(PortalRiderRefusal.refuses(player), "a player is never refused here; it uses its own manager");
    }

    @Test void aMountCarryingAPlayerKeepsTickingWithoutLookupOrTransfer() throws Exception {
        var f = new NativeItemPortalTransferTest.Fixture();
        var manager = new dev.phoenium.live.MobPortalTransitionManager(f.authority, f.objects);
        PortalDestinationTestHooks.mobs = manager;
        NativePortalDestinationPreparationTest.installNativeLookup(f);
        var registries = NativePortalDestinationPreparationTest.dynamicRegistries();
        set(f.overworld, Level.class, "registryAccess", registries); set(f.nether, Level.class, "registryAccess", registries);
        var attributes = net.minecraft.world.attribute.EnvironmentAttributeSystem.builder().build();
        set(f.overworld, net.minecraft.server.level.ServerLevel.class, "environmentAttributes", attributes);
        set(f.nether, net.minecraft.server.level.ServerLevel.class, "environmentAttributes", attributes);
        Pig pig = (Pig) EntityTypes.PIG.create(f.overworld, EntitySpawnReason.COMMAND);
        pig.setPos(.4, 65, .1); f.publish(pig, f.from);
        Pig inner = (Pig) EntityTypes.PIG.create(f.overworld, EntitySpawnReason.COMMAND);
        ride(pig, inner); ride(inner, allocatedPlayer());
        var lookups = new AtomicInteger();
        NativePortalDestinationPreparationTest.setPortal(pig, args -> { lookups.incrementAndGet(); return f.transition(f.nether); });
        long cooldownBefore = pig.getPortalCooldown();
        long refusedBefore = PortalRiderRefusal.refusedTotal();
        f.dispatcher.executeAndDrain(f.from, RegionalServiceKind.CHUNK_GAMEPLAY, ignored -> pig.tick());
        assertEquals(0, lookups.get(), "no destination lookup for a rider graph");
        assertEquals(0, manager.pendingCount(), "nothing was captured");
        assertEquals(0, manager.advance());
        assertFalse(pig.isRemoved());
        assertFalse(f.order.contains("insert"));
        assertEquals(PortalRiderRefusal.refusedTotal(), refusedBefore + 1);
        assertTrue(pig.getPortalCooldown() > cooldownBefore, "native had already set the portal cooldown before the lookup");
        assertEquals(false, FullDecisionWitnessSupport.field(pig, Entity.class, "firstTick"),
            "Entity.baseTick ran to its end: it clears firstTick as its last native effect");
    }
}
