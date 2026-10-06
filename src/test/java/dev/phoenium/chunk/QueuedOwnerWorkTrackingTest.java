package dev.phoenium.chunk;

import dev.phoenium.ownership.OwnedObjectKind;
import dev.phoenium.ownership.OwnershipContext;
import dev.phoenium.service.RegionalServiceKind;
import dev.phoenium.topology.RoutingKey;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static dev.phoenium.chunk.FullDecisionWitnessSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Stage 7A census W3/W4. Owner work accepted before an owner-free ChunkMap.removeEntity or
 * ChunkMap.move (for example another player's queued movement packet) settles before that
 * call's tracker selection cut, exactly as ChunkMap.addEntity already does. Otherwise the call's
 * own drain delivers the older work after the cut, and the strict re-check of an untouched
 * tracker fails and stops the server. */
final class QueuedOwnerWorkTrackingTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void respawnRemovalSettlesQueuedPeerMovementBeforeItsTrackerCut() throws Exception {
        try (var f = new PlayerRespawnHandoffTest.Fixture()) {
            var paired = Paired.respawnedNextTo(f);
            var h = f.open();
            var moved = paired.queuePeerMovement();
            paired.tracking.installRemoval(f);
            assertDoesNotThrow(() -> f.oldRemoval(h));
            assertEquals(1, moved.get(), "queued owner work ran exactly once");
            assertFalse(paired.tracking.barrier.tripped());
            assertFalse(paired.peerTracker.seen.contains(f.listener), "removed player left the peer tracker");
            assertTrue(OwnershipContext.current().isEmpty());
        }
    }

    @Test void ownerFreeMoveSettlesQueuedPeerMovementBeforeItsTrackerCut() throws Exception {
        try (var f = new PlayerRespawnHandoffTest.Fixture()) {
            var paired = Paired.respawnedNextTo(f);
            var moved = paired.queuePeerMovement();
            var updates = new AtomicInteger();
            // An owner-free ChunkMap.move inside a server task (e.g. ServerPlayer.setCamera from /spectate).
            assertDoesNotThrow(() -> {
                try (var turn = f.service.scoreboardAccess()) {
                    paired.tracking.service.movePlayer(paired.tracking.map, f.player,
                        () -> paired.tracking.service.movedPlayerUpdate(paired.peerTracker, f.player, updates::incrementAndGet));
                }
            });
            assertEquals(1, moved.get(), "queued owner work ran exactly once");
            assertEquals(1, updates.get(), "the selected peer tracker received its original updatePlayer");
            assertFalse(paired.tracking.barrier.tripped());
        }
    }

    /** Census W7: the owner-free entity-management save (unload or autosave storeChunkSections)
     * takes its section selection and receipts, then its own drain must not deliver an older
     * queued movement of a selected entity before the strict storage re-check. */
    @Test void ownerFreeEntityStorageSaveSettlesQueuedMovementBeforeItsSelection() throws Exception {
        try (var f = new PlayerRespawnHandoffTest.Fixture()) {
            var paired = Paired.respawnedNextTo(f);
            // The save path derives its routing world from the native dimension key.
            set(f.world, net.minecraft.world.level.Level.class, "dimension", net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION,
                net.minecraft.resources.Identifier.fromNamespaceAndPath("phoenium", "respawn-tracking")));
            assertEquals(paired.tracking.world.value(), f.world.dimension().identifier().toString());
            var moved = paired.queuePeerMovement();
            var services = new dev.phoenium.service.RegionalWorldServices(paired.tracking.directory,
                paired.tracking.dispatcher, paired.tracking.objects);
            var saved = new AtomicInteger();
            assertDoesNotThrow(() -> {
                try (var turn = f.service.referenceAccess()) {
                    assertTrue(services.storeEntitySections(f.world, net.minecraft.world.level.ChunkPos.pack(0, 0),
                        () -> List.of(paired.peer), () -> saved.incrementAndGet() > 0));
                }
            });
            assertEquals(1, moved.get(), "queued owner work ran exactly once");
            assertEquals(1, saved.get(), "the original save ran once on the settled selection");
            assertFalse(paired.tracking.barrier.tripped());
            assertTrue(OwnershipContext.current().isEmpty());
        }
    }

    /** A live player (after one respawn) currently seen by one tracked peer pig at chunk 0,0. */
    private record Paired(PlayerRespawnTrackingTest.TrackingFixture tracking, Entity peer,
                          PlayerRespawnTrackingTest.Tracker peerTracker) {
        static Paired respawnedNextTo(PlayerRespawnHandoffTest.Fixture f) throws Exception {
            var h = f.open(); f.oldRemoval(h); var next = f.prepare(h);
            var tracking = new PlayerRespawnTrackingTest.TrackingFixture(f);
            Entity peer = stub(Entity.class, List.of()); UUID peerUuid = UUID.randomUUID();
            set(peer, Entity.class, "uuid", peerUuid); set(peer, Entity.class, "id", 43);
            set(peer, Entity.class, "level", f.world); set(peer, Entity.class, "type", net.minecraft.world.entity.EntityTypes.PIG);
            set(peer, Entity.class, "position", net.minecraft.world.phys.Vec3.ZERO);
            set(peer, Entity.class, "chunkPosition", new net.minecraft.world.level.ChunkPos(0, 0));
            set(peer, Entity.class, "passengers", com.google.common.collect.ImmutableList.of());
            set(peer, Entity.class, "phoenium$incarnation", FullEntityFacts.nextIncarnation());
            var peerTracker = new PlayerRespawnTrackingTest.Tracker(peer);
            try (var turn = f.service.referenceAccess()) {
                f.lookup.add(peer); tracking.source.put(43, peerTracker);
                tracking.objects.admit(peerUuid.toString(), OwnedObjectKind.ENTITY, new RoutingKey(tracking.world, 0, 0));
                tracking.service.reconcileSource(tracking.source); tracking.service.initialize();
            }
            f.trackingAdd = player -> tracking.service.addEntity(tracking.map, player, () -> {
                tracking.source.put(player.getId(), new PlayerRespawnTrackingTest.Tracker(player));
                tracking.service.addPlayer(peerTracker, player, () -> peerTracker.seen.add(player.connection));
                tracking.service.sourceAdded(tracking.source, player);
            });
            f.insert(h, null); f.publishList(h); f.publishUuid(h); h.commit(next); f.converge(next); f.trackingAdd = null;
            assertTrue(peerTracker.seen.contains(f.listener)); assertFalse(tracking.barrier.tripped());
            return new Paired(tracking, peer, peerTracker);
        }

        /** The peer's own owner publishes a movement (a new route revision) in queued owner work,
         * as an admitted movement packet does between server ticks. */
        AtomicInteger queuePeerMovement() {
            var moved = new AtomicInteger();
            tracking.dispatcher.submit(new RoutingKey(tracking.world, 0, 0), RegionalServiceKind.TRACKING_UPDATE, ignored -> {
                FullEntityFacts.routed(peer); moved.incrementAndGet();
            });
            return moved;
        }
    }
}
