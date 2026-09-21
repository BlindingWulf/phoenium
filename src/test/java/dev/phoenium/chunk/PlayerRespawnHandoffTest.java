package dev.phoenium.chunk;

import com.mojang.authlib.GameProfile;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.SharedConstants;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.entity.EntityLookup;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static dev.phoenium.chunk.FullDecisionWitnessSupport.*;
import static dev.phoenium.chunk.PlayerRespawnHandoff.*;
import static org.junit.jupiter.api.Assertions.*;

/** Executes the production handoff, scoreboard/session ledger, service scopes, and native entity
 * lookup. Only world effects/network endpoints are controlled; identity equality is vanilla's. */
final class PlayerRespawnHandoffTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
    enum FailurePoint { OPEN, OLD_REMOVED, PREPARED, UUID_CLAIMED, CALLBACK_INSTALLED,
        WORLD_VISIBLE, TRACKING_ENTERED, TRACKING_ADMITTED, WORLD_INSERTED, LIST_PUBLISHED, UUID_PUBLISHED }

    @Test void successfulReplacementKeepsOneSessionAndCommitsOnlyAfterEveryPublication() throws Exception {
        try (var f = new Fixture()) {
            var old = f.player; var h = f.open(); f.oldRemoval(h); var replacement = f.prepare(h);
            assertEquals(old, replacement, "vanilla reuses the runtime id; equals is not incarnation identity");
            assertNotSame(old, replacement);
            assertSame(old, f.listener.player, "vanilla listener has not assigned the return value yet");
            f.insert(h, null); f.publishList(h); f.publishUuid(h);
            h.commit(replacement);
            assertEquals(State.COMMITTED, h.state()); assertTrue(retired(old)); assertFalse(retired(replacement));
            f.assertCurrent(replacement); f.converge(replacement);
            assertNull(f.service.scoreboard.respawn(replacement)); f.assertNoAuthority();
        }
    }
    @Test void directoryOperationsStillFailWithoutTheirRealServiceEnvelope() throws Exception {
        try (var f = new Fixture()) {
            assertThrows(ChunkProtocolException.class, () -> f.service.scoreboard.remove(f.player, false));
            assertThrows(ChunkProtocolException.class, () -> f.service.scoreboard.registered(f.player));
            var h = f.open(); f.oldRemoval(h); f.prepare(h); f.insert(h, null); f.publishList(h); f.publishUuid(h);
            h.commit(h.replacement()); f.converge(h.replacement()); f.assertNoAuthority();
        }
    }
    @Test void preparedReplacementNeedsAnExactAddReceiptAndNeverTheListenersOldPlayerField() throws Exception {
        try (var f = new Fixture()) {
            var h = f.open(); f.oldRemoval(h); var next = f.prepare(h);
            try (var turn = f.service.referenceAccess()) {
                assertThrows(ChunkProtocolException.class, () -> f.service.scoreboard.trackingCut(List.of(next)));
                assertThrows(ChunkProtocolException.class, () -> h.beginTracking(next, Action.ADD));
                assertThrows(ChunkProtocolException.class, () -> h.beginTracking(f.player, Action.ADD));
            }
            f.insert(h, null); assertSame(f.player, f.listener.player);
            h.rollback(); f.assertEmpty();
        }
    }
    @Test void oldRetirementAndExpiredTrackingReceiptsCannotExecuteTwice() throws Exception {
        try (var f = new Fixture()) {
            var h = f.open(); f.oldRemoval(h);
            assertEquals(1, f.removals.get(f.player));
            assertThrows(ChunkProtocolException.class, () -> h.beginTracking(f.player, Action.REMOVE));
            try (var turn = f.service.referenceAccess()) {
                assertThrows(ChunkProtocolException.class, () -> f.service.scoreboard.requireTrackingSession(f.lastCut, f.lastReceipt));
            }
            h.rollback(); h.rollback(); assertEquals(1, f.removals.get(f.player)); f.assertEmpty();
        }
    }
    @Test void oldPlayerRemainsCurrentWhileVanillaFindsTheRespawnPositionBeforeRetirement() throws Exception {
        try (var f = new Fixture()) {
            var h = f.open(); FullFinishingScoreboard.TrackingCut cut;
            try (var turn = f.service.referenceAccess()) {
                cut = f.service.scoreboard.trackingCut(List.of(f.player));
                f.service.scoreboard.requireTrackingSession(cut, cut.sessions().getFirst());
            }
            f.oldRemoval(h);
            try (var turn = f.service.referenceAccess()) {
                assertThrows(ChunkProtocolException.class, () -> f.service.scoreboard.requireTrackingSession(cut, cut.sessions().getFirst()));
                assertThrows(ChunkProtocolException.class, () -> f.service.scoreboard.trackingCut(List.of(f.player)));
            }
            h.rollback(); f.assertEmpty();
        }
    }
    @ParameterizedTest @EnumSource(FailurePoint.class)
    void eachFailurePrefixRollsBackAndSameUuidReconnects(FailurePoint point) throws Exception {
        try (var f = new Fixture()) {
            var h = f.open();
            var injected = new IllegalStateException("injected " + point);
            assertSame(injected, assertThrows(IllegalStateException.class, () -> {
                if (point == FailurePoint.OPEN) throw injected;
                f.oldRemoval(h); if (point == FailurePoint.OLD_REMOVED) throw injected;
                f.prepare(h); if (point == FailurePoint.PREPARED) throw injected;
                f.injection = injected; f.insert(h, point);
                if (point == FailurePoint.WORLD_INSERTED) throw injected;
                f.publishList(h); if (point == FailurePoint.LIST_PUBLISHED) throw injected;
                f.publishUuid(h); if (point == FailurePoint.UUID_PUBLISHED) throw injected;
            }));
            h.rollback(); h.rollback(); f.assertEmpty();
            ServerPlayer next = f.reconnect(); f.assertCurrent(next);
            // Delayed cleanup for failed N cannot remove same-UUID/same-runtime-id N+1.
            h.rollback(); f.assertCurrent(next); assertFalse(retired(next));
            f.assertNoAuthority();
        }
    }
    @Test void disconnectDuringTrackingDefersAbortUntilTheAdmittedBodyUnwinds() throws Exception {
        try (var f = new Fixture()) {
            var h = f.open(); f.oldRemoval(h); f.prepare(h); f.disconnectInTracking = true;
            assertThrows(ChunkProtocolException.class, () -> f.insert(h, null));
            h.rollback(); f.assertEmpty(); f.assertCurrent(f.reconnect());
        }
    }
    @Test void missingFinalUuidPublicationCannotCommit() throws Exception {
        try (var f = new Fixture()) {
            var h = f.open(); f.oldRemoval(h); f.prepare(h); f.insert(h, null); f.publishList(h);
            assertThrows(ChunkProtocolException.class, () -> h.commit(h.replacement()));
            h.rollback(); f.assertEmpty();
        }
    }
    @Test void unrelatedSameUuidObjectCannotUseTransitionReceipt() throws Exception {
        try (var f = new Fixture()) {
            var h = f.open(); f.oldRemoval(h); var next = f.prepare(h); var impostor = f.newPlayer(f.listener);
            assertThrows(ChunkProtocolException.class, () -> h.beginTracking(impostor, Action.ADD));
            try (var turn = f.service.referenceAccess()) {
                assertThrows(ChunkProtocolException.class, () -> f.service.scoreboard.trackingCut(List.of(impostor)));
            }
            assertNotSame(next, impostor); h.rollback(); f.assertEmpty();
        }
    }
    @Test void twoSequentialRespawnsReuseSessionButAdvanceHandoffAndIncarnation() throws Exception {
        try (var f = new Fixture()) {
            var first = f.success(); var second = f.success();
            assertEquals(first.sessionId, second.sessionId); assertTrue(second.generation > first.generation);
            assertNotSame(first.replacement(), second.replacement()); assertTrue(retired(first.replacement()));
            f.assertCurrent(second.replacement()); first.rollback(); f.assertCurrent(second.replacement());
        }
    }
    @Test void worldCloseAbortsExactTransitionBeforeWorldRetires() throws Exception {
        try (var f = new Fixture()) {
            var h = f.open(); f.oldRemoval(h); f.prepare(h); f.insert(h, null);
            FullFinishingServices.passengerWorldClosing(f.world);
            assertEquals(State.ROLLED_BACK, h.state()); f.assertEmpty(); f.assertNoAuthority();
        }
    }
    @Test void serverCloseAbortsAndCannotRecreateAStaleSession() throws Exception {
        var f = new Fixture(); var h = f.open(); f.oldRemoval(h); f.prepare(h); f.insert(h, null);
        FullFinishingServices.stopped(f.server);
        assertEquals(State.ROLLED_BACK, h.state()); assertEquals(0, f.lookup.count());
        assertTrue(f.tracked.isEmpty()); assertTrue(f.players.isEmpty()); assertTrue(f.uuids.isEmpty());
        assertThrows(ChunkProtocolException.class, () -> FullFinishingServices.forServer(f.server)); h.rollback();
    }
    @Test void replacementCannotCommitWithAnUnconvergedListenerValidation() throws Exception {
        try (var f = new Fixture()) {
            var h = f.open(); f.oldRemoval(h); f.prepare(h); f.insert(h, null); f.publishList(h); f.publishUuid(h); h.commit(h.replacement());
            try (var turn = f.service.scoreboardAccess()) {
                assertThrows(ChunkProtocolException.class, () -> f.service.scoreboard.respawnListenerConverged(h.replacement()));
            }
            f.converge(h.replacement()); f.assertCurrent(h.replacement());
        }
    }

    static final class Fixture implements Directory, AutoCloseable {
        final UUID uuid = UUID.randomUUID();
        final MinecraftServer server;
        final FullFinishingServices service;
        final ServerLevel world;
        final List<ServerPlayer> players = new ArrayList<>(), worldPlayers = new ArrayList<>();
        final Map<UUID, ServerPlayer> uuids = new HashMap<>();
        final EntityLookup<Entity> lookup = new EntityLookup<>();
        final Set<ServerPlayer> tracked = Collections.newSetFromMap(new IdentityHashMap<>());
        final Map<ServerPlayer, Integer> removals = new IdentityHashMap<>();
        final Set<ServerPlayer> stored = Collections.newSetFromMap(new IdentityHashMap<>());
        ServerPlayer player;
        ServerGamePacketListenerImpl listener;
        FullFinishingScoreboard.TrackingCut lastCut;
        FullFinishingScoreboard.TrackingSession lastReceipt;
        IllegalStateException injection;
        boolean disconnectInTracking;
        java.util.function.Consumer<ServerPlayer> trackingAdd, trackingRemove;
        Fixture() throws Exception {
            server = stub(MinecraftServer.class, List.of(new Answer(MinecraftServer.class.getMethod("getScaledTrackingDistance", int.class), args -> args[0])));
            service = FullFinishingServices.forServer(server);
            world = stub(ServerLevel.class, List.of(
                new Answer(ServerLevel.class.getMethod("getServer"), args -> server),
                new Answer(ServerLevel.class.getMethod("getEntity", UUID.class), args -> lookup.getEntity((UUID) args[0])),
                new Answer(ServerLevel.class.getMethod("getEntity", int.class), args -> lookup.getEntity((int) args[0])),
                new Answer(ServerLevel.class.getMethod("players"), args -> worldPlayers),
                new Answer(ServerLevel.class.getMethod("removePlayerImmediately", ServerPlayer.class, Entity.RemovalReason.class), args -> {
                    removeWorld((ServerPlayer) args[0]); return null;
                })));
            reconnect();
        }
        @Override public List<ServerPlayer> phoenium$respawnPlayers() { return players; }
        @Override public Map<UUID, ServerPlayer> phoenium$respawnUuids() { return uuids; }
        ServerGamePacketListenerImpl newListener() throws Exception {
            var connection = allocate(Connection.class);
            return stub(ServerGamePacketListenerImpl.class, List.of(
                new Answer(FullFinishingScoreboard.ConnectionAccess.class.getMethod("phoenium$serviceConnection"), args -> connection),
                new Answer(ServerGamePacketListenerImpl.class.getMethod("disconnect", Component.class), args -> null)),
                FullFinishingScoreboard.ConnectionAccess.class);
        }
        ServerPlayer newPlayer(ServerGamePacketListenerImpl connection) throws Exception {
            var retired = new AtomicBoolean();
            ServerPlayer result = stub(ServerPlayer.class, List.of(
                new Answer(ServerPlayer.class.getMethod("level"), args -> world),
                new Answer(ServerPlayer.class.getMethod("getGameProfile"), args -> new GameProfile(uuid, "RespawnWitness")),
                new Answer(ServerPlayer.class.getMethod("getScoreboardName"), args -> "RespawnWitness"),
                new Answer(ServerPlayer.class.getMethod("canBeSeenAsEnemy"), args -> true),
                new Answer(ServerPlayer.class.getMethod("isCreative"), args -> false),
                new Answer(ServerPlayer.class.getMethod("isSpectator"), args -> false),
                new Answer(Incarnation.class.getMethod("phoenium$respawnRetired"), args -> retired.get()),
                new Answer(Incarnation.class.getMethod("phoenium$respawnRetired", boolean.class), args -> { retired.set((boolean) args[0]); return null; })), Incarnation.class);
            result.connection = connection;
            set(result, Entity.class, "uuid", uuid); set(result, Entity.class, "id", 42);
            set(result, Entity.class, "type", EntityTypes.PLAYER);
            set(result, Entity.class, "level", world);
            set(result, Entity.class, "passengers", com.google.common.collect.ImmutableList.of());
            set(result, Entity.class, "phoenium$incarnation", FullEntityFacts.nextIncarnation());
            set(result, Entity.class, "position", net.minecraft.world.phys.Vec3.ZERO);
            set(result, Entity.class, "blockPosition", net.minecraft.core.BlockPos.ZERO);
            set(result, Entity.class, "chunkPosition", new net.minecraft.world.level.ChunkPos(0, 0));
            return result;
        }
        ServerPlayer reconnect() throws Exception {
            listener = newListener(); player = newPlayer(listener); listener.player = player;
            try (var turn = service.scoreboardAccess()) {
                service.scoreboard.initialSync(player); players.add(player); service.scoreboard.registered(player);
                uuids.put(uuid, player); service.scoreboard.playerUuidPublished(uuid, player);
                lookup.add(player); worldPlayers.add(player); tracked.add(player); stored.add(player);
            }
            return player;
        }
        PlayerRespawnHandoff open() {
            try (var turn = service.scoreboardAccess()) { return service.scoreboard.openRespawn(this, player); }
        }
        void oldRemoval(PlayerRespawnHandoff h) {
            try (var turn = service.scoreboardAccess()) {
                assertTrue(players.removeIf(value -> value == player)); service.scoreboard.remove(player, false); h.oldListRemoved(player);
            }
            assertNoAuthority(); h.removeOldWorld(world, player, () -> world.removePlayerImmediately(player, Entity.RemovalReason.KILLED));
        }
        ServerPlayer prepare(PlayerRespawnHandoff h) throws Exception { var next = newPlayer(listener); h.prepare(next); assertNoAuthority(); return next; }
        void fail(FailurePoint selected, FailurePoint now) { if (selected == now) throw injection; }
        void insert(PlayerRespawnHandoff h, FailurePoint point) {
            ServerPlayer next = h.replacement();
            h.insertWorld(world, next, () -> {
                stored.add(next); var storage = h.storageClaimed(next, () -> stored.remove(next)); fail(point, FailurePoint.UUID_CLAIMED);
                storage.callbackInstalled(); fail(point, FailurePoint.CALLBACK_INSTALLED);
                lookup.add(next); fail(point, FailurePoint.WORLD_VISIBLE);
                try (var receipt = h.beginTracking(next, Action.ADD)) {
                    fail(point, FailurePoint.TRACKING_ENTERED);
                    var cut = service.scoreboard.trackingCut(List.of(next), receipt);
                    service.scoreboard.requireTrackingSession(cut, cut.sessions().getFirst());
                    if (trackingAdd != null) trackingAdd.accept(next);
                    tracked.add(next); fail(point, FailurePoint.TRACKING_ADMITTED);
                    if (disconnectInTracking) h.requestAbort(); receipt.succeeded();
                }
                worldPlayers.add(next);
            });
        }
        void removeWorld(ServerPlayer target) {
            var h = service.scoreboard.respawn(target); assertNotNull(h);
            try (var receipt = h.beginTracking(target, Action.REMOVE)) {
                if (!receipt.alreadyRemoved) {
                    lastCut = service.scoreboard.trackingCut(List.of(target), receipt); lastReceipt = lastCut.sessions().getFirst();
                    service.scoreboard.requireTrackingSession(lastCut, lastReceipt);
                    if (trackingRemove != null) trackingRemove.accept(target);
                    tracked.remove(target); removals.merge(target, 1, Integer::sum); receipt.succeeded();
                }
            }
            if (lookup.getEntity(uuid) == target) lookup.remove(target);
            worldPlayers.removeIf(value -> value == target); stored.remove(target);
        }
        void publishList(PlayerRespawnHandoff h) {
            try (var turn = service.scoreboardAccess()) { players.add(h.replacement()); service.scoreboard.registered(h.replacement()); }
        }
        void publishUuid(PlayerRespawnHandoff h) {
            try (var turn = service.scoreboardAccess()) { uuids.put(uuid, h.replacement()); service.scoreboard.playerUuidPublished(uuid, h.replacement()); }
        }
        void converge(ServerPlayer replacement) {
            listener.player = replacement;
            try (var turn = service.scoreboardAccess()) { service.scoreboard.respawnListenerConverged(replacement); }
            player = replacement;
        }
        PlayerRespawnHandoff success() throws Exception {
            var h = open(); oldRemoval(h); prepare(h); insert(h, null); publishList(h); publishUuid(h); h.commit(h.replacement()); converge(h.replacement()); return h;
        }
        void assertCurrent(ServerPlayer current) {
            assertEquals(1, players.size()); assertSame(current, players.getFirst()); assertSame(current, uuids.get(uuid));
            assertEquals(1, lookup.count()); assertSame(current, lookup.getEntity(uuid)); assertEquals(1, tracked.size()); assertTrue(tracked.contains(current));
            try (var turn = service.scoreboardAccess()) {
                assertSame(current, service.scoreboard.lookupPlayer(uuid)); assertSame(current, service.scoreboard.lookupName("RespawnWitness"));
                var cut = service.scoreboard.trackingCut(List.of(current)); service.scoreboard.requireTrackingSession(cut, cut.sessions().getFirst());
            }
            assertEquals(1L, service.scoreboard.measurements().get("sessions"));
        }
        void assertEmpty() {
            assertEquals(0, lookup.count()); assertTrue(players.isEmpty()); assertTrue(uuids.isEmpty());
            assertTrue(worldPlayers.isEmpty()); assertTrue(tracked.isEmpty()); assertTrue(stored.isEmpty());
            assertEquals(0L, service.scoreboard.measurements().get("sessions"));
            try (var turn = service.scoreboardAccess()) { assertNull(service.scoreboard.lookupPlayer(uuid)); assertNull(service.scoreboard.lookupName("RespawnWitness")); }
        }
        void assertNoAuthority() { assertEquals(0L, service.measurements().get("active")); }
        @Override public void close() { FullFinishingServices.stopped(server); }
    }
}
