package dev.phoenium.chunk;

import dev.phoenium.live.GatewayExitBoundary;
import dev.phoenium.ownership.OwnershipContext;
import dev.phoenium.service.RegionalServiceKind;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.level.block.entity.TheEndGatewayBlockEntity;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static dev.phoenium.chunk.FullDecisionWitnessSupport.set;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Real native EndGatewayBlock.getPortalDestination / TheEndGatewayBlockEntity.findExitPosition. The only controlled
 * endpoint is the level's block read, which waits on an unfinished chunk exactly as ServerChunkCache.getChunkBlocking
 * does inside an owner (ChunkSynchronousAccessGuard) and answers freely owner-free.
 */
class GatewayExitBoundaryTest {
    @BeforeAll static void bootstrap() { NativeItemPortalTransferTest.bootstrap(); }
    @AfterEach void reset() { GatewayExitTestHooks.boundary = null; }

    private static final BlockPos ENTRY = new BlockPos(96, 65, 8), EXIT = new BlockPos(160, 65, 160);

    private static final class Scene {
        final NativeItemPortalTransferTest.Fixture f;
        final Pig pig;
        final TheEndGatewayBlockEntity gateway;
        final AtomicInteger ownerFreeReads = new AtomicInteger(), ownedReads = new AtomicInteger();
        final List<String> tickets = new ArrayList<>();
        boolean arrivalReady;
        final GatewayExitBoundary boundary;

        Scene(boolean exact, boolean exitKnown) throws Exception {
            f = new NativeItemPortalTransferTest.Fixture(Level.END);
            NativePortalDestinationPreparationTest.installNativeLookup(f);
            set(f.nether, Level.class, "registryAccess", NativePortalDestinationPreparationTest.dynamicRegistries());
            set(f.nether, ServerLevel.class, "environmentAttributes",
                net.minecraft.world.attribute.EnvironmentAttributeSystem.builder().build());
            pig = EntityTypes.PIG.create(f.nether, EntitySpawnReason.COMMAND);
            pig.setPos(96.5, 65.0, 8.5);
            f.publish(pig, f.to);
            gateway = new TheEndGatewayBlockEntity(ENTRY, Blocks.END_GATEWAY.defaultBlockState());
            if (exitKnown) gateway.setExitPosition(EXIT, exact);
            f.blockEntities.put(ENTRY, gateway);
            f.blockRead = pos -> {
                if (OwnershipContext.current().isPresent()) {
                    ownedReads.incrementAndGet();
                    // ServerChunkCache.getChunkBlocking inside an owner while the chunk is not resident
                    ChunkSynchronousAccessGuard.beforeWait(() -> false);
                } else ownerFreeReads.incrementAndGet();
                return Blocks.AIR.defaultBlockState();
            };
            boundary = new GatewayExitBoundary(f.authority, new GatewayExitBoundary.Arrival() {
                @Override public boolean ready(ServerLevel level, Vec3 position) { return arrivalReady; }
                @Override public void demand(ServerLevel level, Vec3 position) { tickets.add(BlockPos.containing(position).toShortString()); }
            });
        }

        boolean mayArm(Entity entity) { return arm(f.nether, entity); }
        boolean arm(ServerLevel level, Entity entity) {
            boolean[] allowed = new boolean[1];
            f.dispatcher.executeAndDrain(f.to, RegionalServiceKind.ENTITY_PORTAL_SOURCE,
                ignored -> allowed[0] = boundary.mayArm(level, ENTRY, entity));
            return allowed[0];
        }
        TeleportTransition destinationUnderOwner() {
            TeleportTransition[] result = new TeleportTransition[1];
            f.dispatcher.executeAndDrain(f.to, RegionalServiceKind.ENTITY_PORTAL_SOURCE,
                ignored -> result[0] = ((Portal) Blocks.END_GATEWAY).getPortalDestination(f.nether, pig, ENTRY));
            return result[0];
        }
    }

    @Test void unrepairedNativeLookupBlocksOnAnUnfinishedChunkInsideTheOwner() throws Exception {
        var s = new Scene(false, true);
        GatewayExitTestHooks.boundary = null;   // no boundary: the pre-repair native behaviour
        assertThrows(ChunkProtocolException.class, s::destinationUnderOwner);
        assertEquals(1, s.ownedReads.get(), "the first owned block read is the blocking one");
    }

    @Test void nonExactGatewayIsPreparedOwnerFreeThenArmedAndConsumedOnce() throws Exception {
        var s = new Scene(false, true);
        GatewayExitTestHooks.boundary = s.boundary;
        assertFalse(s.mayArm(s.pig), "no prepared exit: arming is deferred, not attempted");
        assertEquals(1, s.boundary.pendingCount());
        assertFalse(s.mayArm(s.pig));
        assertEquals(1, s.boundary.pendingCount(), "a repeated request is not queued twice");
        assertEquals(0, s.ownedReads.get() + s.ownerFreeReads.get(), "nothing was read inside the owner turn");
        assertEquals(1, s.boundary.advance());
        assertTrue(s.ownerFreeReads.get() > 0, "the native lookup ran owner-free");
        assertEquals(0, s.ownedReads.get());
        assertEquals(1, s.tickets.size(), "the arrival chunk is demanded once");
        assertFalse(s.mayArm(s.pig), "the arrival is not entity-ticking yet");
        s.arrivalReady = true;
        assertTrue(s.mayArm(s.pig));
        int before = s.ownerFreeReads.get();
        TeleportTransition transition = s.destinationUnderOwner();
        assertNotNull(transition);
        assertEquals(0, s.ownedReads.get(), "consumption performs no chunk read under the owner");
        assertEquals(before, s.ownerFreeReads.get());
        assertSame(TeleportTransition.PLACE_PORTAL_TICKET, transition.postTeleportTransition());
        assertEquals(1, s.boundary.consumedTotal());
        assertNull(s.destinationUnderOwner(), "a prepared exit is consumed once; a second lookup never blocks");
        assertEquals(1, s.boundary.missingTotal());
        assertEquals(0, s.ownedReads.get());
    }

    @Test void exactGatewayWithAKnownExitNeedsNoPreparation() throws Exception {
        var s = new Scene(true, true);
        GatewayExitTestHooks.boundary = s.boundary;
        assertTrue(s.mayArm(s.pig));
        assertEquals(0, s.boundary.pendingCount());
        assertEquals(Vec3.atBottomCenterOf(EXIT), s.destinationUnderOwner().position());
        assertEquals(0, s.boundary.consumedTotal(), "the native exact path is untouched");
    }

    @Test void ownerFreeCallersAndPlayersKeepTheNativePath() throws Exception {
        var s = new Scene(false, true);
        GatewayExitTestHooks.boundary = s.boundary;
        assertTrue(s.boundary.mayArm(s.f.nether, ENTRY, s.pig), "owner-free arming is native");
        assertNotNull(((Portal) Blocks.END_GATEWAY).getPortalDestination(s.f.nether, s.pig, ENTRY),
            "owner-free lookup runs the native search");
        assertTrue(s.ownerFreeReads.get() > 0);
        assertEquals(0, s.boundary.pendingCount());
    }

    @Test void aGatewayThatIsCoolingDownOrAnIneligibleEntityIsNeverQueued() throws Exception {
        var s = new Scene(false, true);
        var cooling = new TheEndGatewayBlockEntity(ENTRY, Blocks.END_GATEWAY.defaultBlockState());
        cooling.setExitPosition(EXIT, false);
        set(cooling, TheEndGatewayBlockEntity.class, "teleportCooldown", 5);
        s.f.blockEntities.put(ENTRY, cooling);
        assertTrue(s.mayArm(s.pig), "native does nothing while cooling down; the boundary stays out of it");
        assertEquals(0, s.boundary.pendingCount());
    }

    @Test void aStalePreparedExitIsNotConsumedAndIsRequestedAgain() throws Exception {
        var s = new Scene(false, true);
        GatewayExitTestHooks.boundary = s.boundary;
        s.arrivalReady = true;
        assertFalse(s.mayArm(s.pig));
        s.boundary.advance();
        assertTrue(s.mayArm(s.pig));
        s.f.gameTime += GatewayExitBoundary_FRESH + 1;
        assertFalse(s.mayArm(s.pig), "an aged prepared exit no longer arms");
        assertEquals(1, s.boundary.pendingCount(), "it is requested again");
        assertNull(s.destinationUnderOwner(), "an aged prepared exit is never consumed");
        assertEquals(0, s.ownedReads.get());
        assertEquals(1, s.boundary.advance(), "the refreshed lookup runs owner-free");
        assertTrue(s.mayArm(s.pig));
    }
    private static final long GatewayExitBoundary_FRESH = 200;

    @Test void worldClosingAndShutdownDiscardPendingAndPreparedExits() throws Exception {
        var s = new Scene(false, true);
        GatewayExitTestHooks.boundary = s.boundary;
        assertFalse(s.mayArm(s.pig));
        s.boundary.worldClosing(s.f.nether);
        assertEquals(0, s.boundary.pendingCount());
        assertEquals(0, s.boundary.advance());
        assertFalse(s.mayArm(s.pig));
        s.boundary.close();
        assertEquals(0, s.boundary.pendingCount());
        assertTrue(s.mayArm(s.pig), "a closed boundary never defers");
    }

    @Test void advanceRejectsRetainedOwnerAuthority() throws Exception {
        var s = new Scene(false, true);
        s.f.dispatcher.executeAndDrain(s.f.to, RegionalServiceKind.ENTITY_PORTAL_SOURCE,
            ignored -> assertThrows(IllegalStateException.class, s.boundary::advance));
    }
}
