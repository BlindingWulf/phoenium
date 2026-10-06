package dev.phoenium.chunk;

import dev.phoenium.core.*;
import dev.phoenium.failure.FailureBarrier;
import dev.phoenium.mixin.RegionalChunkMapAccess;
import dev.phoenium.mixin.RegionalTrackedEntityAccess;
import dev.phoenium.ownership.*;
import dev.phoenium.region.*;
import dev.phoenium.scheduler.*;
import dev.phoenium.service.*;
import dev.phoenium.topology.*;
import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.SectionPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.*;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.world.entity.Entity;
import org.junit.jupiter.api.*;
import static dev.phoenium.chunk.FullDecisionWitnessSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** The actual RegionalTrackingService and owner scheduler consume the handoff receipt. */
final class PlayerRespawnTrackingTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
    @Test void preparedConnectionAdmitsThroughStrictRegionalAddBeforeListenerAssignment() throws Exception {
        try (var f = new PlayerRespawnHandoffTest.Fixture()) {
            var h = f.open(); f.oldRemoval(h); var next = f.prepare(h);
            var tracking = new TrackingFixture(f);
            f.trackingAdd = player -> tracking.service.addEntity(tracking.map, player, () -> {
                assertSame(f.player, f.listener.player);
                assertTrue(OwnershipContext.current().isPresent());
                tracking.source.put(player.getId(), new Tracker(player));
                tracking.service.sourceAdded(tracking.source, player);
            });
            tracking.installRemoval(f);
            f.insert(h, null); assertEquals(1, tracking.service.measurements().tracked());
            f.publishList(h); f.publishUuid(h); h.commit(next); f.converge(next); f.assertCurrent(next);
            assertFalse(tracking.barrier.tripped()); assertFalse(tracking.runtime.leases().hasActiveLease());
        }
    }
    @Test void partiallyInsertedNativeTrackerRollsBackAfterLeaseReleaseWithoutPoisoningScheduler() throws Exception {
        try (var f = new PlayerRespawnHandoffTest.Fixture()) {
            var h = f.open(); f.oldRemoval(h); f.prepare(h);
            var tracking = new TrackingFixture(f); var injected = new IllegalStateException("native tracker pairing failed");
            f.trackingAdd = player -> tracking.service.addEntity(tracking.map, player, () -> {
                tracking.source.put(player.getId(), new Tracker(player)); throw injected;
            });
            tracking.installRemoval(f);
            assertSame(injected, assertThrows(IllegalStateException.class, () -> f.insert(h, null)));
            assertFalse(tracking.barrier.tripped(), "respawn must reach its rollback coordinator with scheduler cleanup still available");
            assertEquals(1, tracking.service.measurements().tracked(), "failed native prefix must remain represented until cleanup");
            assertTrue(OwnershipContext.current().isEmpty()); assertFalse(tracking.runtime.leases().hasActiveLease());
            h.rollback(); h.rollback(); f.assertEmpty();
            assertEquals(0, tracking.service.measurements().tracked()); assertTrue(tracking.source.isEmpty());
            f.assertCurrent(f.reconnect()); assertFalse(tracking.barrier.tripped());
        }
    }
    @Test void changedIncarnationCannotUseEvenAnActiveReplacementSessionReceipt() throws Exception {
        try (var f = new PlayerRespawnHandoffTest.Fixture()) {
            var h = f.open(); f.oldRemoval(h); var next = f.prepare(h);
            var tracking = new TrackingFixture(f);
            f.trackingAdd = player -> {
                var access = (FullEntityFacts.Access) player;
                var before = access.phoenium$referenceFact();
                access.phoenium$referenceFact(new FullEntityFacts.Fact(player, f.server, f.world, before.incarnation() + 1,
                    before.revision(), before.uuid(), before.runtimeId(), before.type(), before.dispatch(), before.scoreboardName(),
                    before.removed(), before.enemy(), before.creative(), before.spectator()));
                try { assertThrows(ChunkProtocolException.class, () -> tracking.service.addEntity(tracking.map, player, () -> fail("stale body ran"))); }
                finally { access.phoenium$referenceFact(before); }
                throw new IllegalStateException("injected stale admission");
            };
            assertThrows(IllegalStateException.class, () -> f.insert(h, null)); h.rollback(); f.assertEmpty();
            assertEquals(0, tracking.service.measurements().tracked()); assertFalse(tracking.barrier.tripped());
        }
    }
    @Test void failureAfterReversePairingRemovesTheExactRetainedConnectionFromOtherTrackers() throws Exception {
        try (var f = new PlayerRespawnHandoffTest.Fixture()) {
            var h = f.open(); f.oldRemoval(h); f.prepare(h); var tracking = new TrackingFixture(f);
            Entity peer = stub(Entity.class, List.of()); UUID peerUuid = UUID.randomUUID();
            set(peer, Entity.class, "uuid", peerUuid); set(peer, Entity.class, "id", 43);
            set(peer, Entity.class, "level", f.world); set(peer, Entity.class, "type", net.minecraft.world.entity.EntityTypes.PIG);
            set(peer, Entity.class, "position", net.minecraft.world.phys.Vec3.ZERO);
            set(peer, Entity.class, "chunkPosition", new net.minecraft.world.level.ChunkPos(0, 0));
            set(peer, Entity.class, "passengers", com.google.common.collect.ImmutableList.of());
            set(peer, Entity.class, "phoenium$incarnation", FullEntityFacts.nextIncarnation());
            var peerTracker = new Tracker(peer);
            try (var turn = f.service.referenceAccess()) {
                f.lookup.add(peer); tracking.source.put(43, peerTracker);
                tracking.objects.admit(peerUuid.toString(), OwnedObjectKind.ENTITY, new RoutingKey(tracking.world, 0, 0));
                tracking.service.reconcileSource(tracking.source); tracking.service.initialize();
            }
            var injected = new IllegalStateException("reverse pairing failed after seenBy.add");
            f.trackingAdd = player -> tracking.service.addEntity(tracking.map, player, () -> {
                tracking.source.put(player.getId(), new Tracker(player));
                tracking.service.addPlayer(peerTracker, player, () -> { peerTracker.seen.add(player.connection); throw injected; });
            });
            tracking.installRemoval(f);
            assertSame(injected, assertThrows(IllegalStateException.class, () -> f.insert(h, null)));
            assertTrue(peerTracker.seen.contains(f.listener)); h.rollback();
            assertTrue(peerTracker.seen.isEmpty()); assertSame(peer, f.lookup.getEntity(peerUuid));
            assertNull(f.lookup.getEntity(f.uuid)); assertEquals(1, tracking.source.size());
            assertSame(peerTracker, tracking.source.get(43)); assertEquals(1, tracking.service.measurements().tracked());
            assertFalse(tracking.barrier.tripped()); assertTrue(f.players.isEmpty());
            assertEquals(0L, f.service.scoreboard.measurements().get("sessions"));
        }
    }
    static final class TrackingFixture {
        final WorldId world = new WorldId("respawn-tracking");
        final RegionId region = new RegionId(world, "owner");
        final RegionRuntime runtime = new RegionRuntime(region, new OwnerEpoch(0));
        final RegionDirectory directory = new RegionDirectory();
        final FailureBarrier barrier = new FailureBarrier();
        final ObjectOwnershipRegistry objects = new ObjectOwnershipRegistry(directory);
        final Int2ObjectLinkedOpenHashMap<Object> source = new Int2ObjectLinkedOpenHashMap<>();
        final ChunkMap map;
        final RegionalTrackingService service;
        final RegionalServiceDispatcher dispatcher;
        TrackingFixture(PlayerRespawnHandoffTest.Fixture f) throws Exception {
            runtime.transitionTo(RegionLifecycle.READY);
            var generation = new TopologyGeneration(1);
            directory.publish(generation, Map.of(), Map.of(world, new RegionRoute(region, generation, runtime.ownerEpoch())));
            var runtimes = Map.of(region, runtime); var scheduler = new RegionScheduler(runtimes, directory, barrier);
            var gateway = new OwnerGateway(directory, runtimes, scheduler);
            dispatcher = new RegionalServiceDispatcher("respawn-test", 1, directory, runtimes, gateway, scheduler);
            dispatcher.installClosureEncloser(keys -> true);
            map = stub(ChunkMap.class, List.of(
                new Answer(RegionalChunkMapAccess.class.getMethod("phoenium$getTrackedEntities"), args -> source),
                new Answer(RegionalChunkMapAccess.class.getMethod("phoenium$getServerViewDistance"), args -> 10)), RegionalChunkMapAccess.class);
            var cache = allocate(ServerChunkCache.class); set(cache, ServerChunkCache.class, "chunkMap", map);
            set(f.world, ServerLevel.class, "chunkSource", cache);
            service = new RegionalTrackingService(world, f.world, directory, dispatcher, objects);
        }
        void installRemoval(PlayerRespawnHandoffTest.Fixture f) {
            f.trackingRemove = player -> service.removeEntity(map, player, () -> {
                for (Object value : List.copyOf(source.values())) {
                    Tracker tracker = (Tracker) value;
                    service.removePlayer(tracker, player, () -> tracker.seen.remove(player.connection));
                }
                source.remove(player.getId()); service.sourceRemoved(source, player);
            });
        }
    }
    static final class Tracker implements RegionalTrackedEntityAccess {
        final Entity entity;
        final Set<ServerPlayerConnection> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Tracker(Entity entity) { this.entity = entity; }
        public Entity phoenium$getEntity() { return entity; }
        public ServerEntity phoenium$getServerEntity() { return null; }
        public SectionPos phoenium$getLastSectionPos() { return SectionPos.of(0, 0, 0); }
        public void phoenium$setLastSectionPos(SectionPos pos) { }
        public Set<ServerPlayerConnection> phoenium$getSeenBy() { return seen; }
        public void phoenium$invokeUpdatePlayer(ServerPlayer player) { seen.add(player.connection); }
    }
}
