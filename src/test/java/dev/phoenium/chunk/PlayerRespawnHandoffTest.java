package dev.phoenium.chunk;

import com.mojang.authlib.GameProfile;
import java.util.*;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
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
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.entity.EntityInLevelCallback;
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

    @Test void nativeTwentyTickDeathRetirementStillAdmitsTheExactRetainedSession() throws Exception {
        try (var f = new Fixture()) {
            ServerPlayer old = f.player;
            f.nativeDeathTicks();
            assertEquals(20, old.deathTime);
            assertEquals(Entity.RemovalReason.KILLED, old.getRemovalReason());
            assertNull(f.world.getEntity(f.uuid));
            assertNull(f.world.getEntity(old.getId()));
            assertSame(old, f.players.getFirst());
            assertSame(old, f.uuids.get(f.uuid));
            assertSame(old, f.listener.player);
            var handoff = f.open();
            f.oldRemoval(handoff); f.prepare(handoff); f.insert(handoff, null);
            f.publishList(handoff); f.publishUuid(handoff); handoff.commit(handoff.replacement());
            f.converge(handoff.replacement()); f.assertCurrent(f.player);
            assertEquals(1, f.removals.get(old));
        }
    }
    @Test void respawnBeforeNativeDeathRetirementKeepsThePresentOpening() throws Exception {
        try (var f = new Fixture()) {
            f.nativeDeathTicks(19);
            assertFalse(f.player.isRemoved()); assertSame(f.player, f.world.getEntity(f.uuid));
            var handoff = f.open(); f.oldRemoval(handoff); f.prepare(handoff); f.insert(handoff, null);
            f.publishList(handoff); f.publishUuid(handoff); handoff.commit(handoff.replacement());
            f.converge(handoff.replacement()); f.assertCurrent(f.player);
        }
    }
    @Test void absenceWithoutCompletedNativeDeathAndUnrelatedRemovalAreRejected() throws Exception {
        try (var f = new Fixture()) {
            var old = f.player; f.lookup.remove(old);
            assertTrue(precheck(f, old, old, 1, true, -1).contains("result=NATIVE_ABSENCE_WITHOUT_COMPLETED_DEATH"));
            assertThrows(ChunkProtocolException.class, f::open);
        }
        try (var f = new Fixture()) {
            f.nativeDeathTicks(19); f.player.remove(Entity.RemovalReason.KILLED);
            assertFalse(deathRemovalCompleted(f.player));
            assertThrows(ChunkProtocolException.class, f::open);
        }
    }
    @Test void deathReceiptDoesNotAuthorizeConflictDisconnectOrNewerConnection() throws Exception {
        try (var f = new Fixture()) {
            f.nativeDeathTicks(); var impostor = f.newPlayer(f.listener); f.lookup.add(impostor);
            assertTrue(precheck(f, f.player, f.player, 1, true, -1).contains("result=WORLD_UUID_CONFLICT"));
            assertThrows(ChunkProtocolException.class, f::open);
        }
        try (var f = new Fixture()) {
            f.nativeDeathTicks();
            try (var turn = f.service.scoreboardAccess()) { f.service.scoreboard.remove(f.player, true); }
            assertThrows(ChunkProtocolException.class, f::open);
        }
        try (var f = new Fixture()) {
            f.nativeDeathTicks(); var stale = f.player;
            try (var turn = f.service.scoreboardAccess()) { f.service.scoreboard.remove(stale, true); }
            f.reconnect();
            try (var turn = f.service.scoreboardAccess()) {
                assertThrows(ChunkProtocolException.class, () -> f.service.scoreboard.openRespawn(f, stale));
            }
        }
    }
    @Test void deathRetiredFailurePrefixesRemainExactAndReconnectSafe() throws Exception {
        for (boolean afterInsertion : new boolean[] {false, true}) try (var f = new Fixture()) {
            f.nativeDeathTicks(); var old = f.player; var h = f.open();
            var failure = new IllegalStateException("injected death-retired prefix");
            assertSame(failure, assertThrows(IllegalStateException.class, () -> {
                f.oldRemoval(h); f.prepare(h); if (afterInsertion) f.insert(h, null);
                throw failure;
            }));
            h.rollback(); h.rollback(); assertEquals(1, f.removals.get(old)); f.assertEmpty();
            var valid = f.reconnect(); h.rollback(); f.assertCurrent(valid);
        }
    }
    @Test void twoNativeDeathCyclesRetainSessionAndNeverReplayOldRemoval() throws Exception {
        try (var f = new Fixture()) {
            var first = f.deathRetiredSuccess(); var second = f.deathRetiredSuccess();
            assertNotSame(first.oldPlayer, second.oldPlayer);
            assertNotSame(first.replacement(), second.replacement());
            assertEquals(first.sessionId, second.sessionId);
            assertEquals(1, f.removals.get(first.oldPlayer));
            assertEquals(1, f.removals.get(second.oldPlayer));
            f.assertCurrent(second.replacement());
            first.rollback(); f.assertCurrent(second.replacement());
        }
    }
    @Test void overlappingRequestCannotStartAfterDeathRetiredOpen() throws Exception {
        try (var f = new Fixture()) {
            f.nativeDeathTicks(); var h = f.open();
            assertThrows(ChunkProtocolException.class, f::open);
            h.rollback(); f.assertEmpty();
        }
    }
    @Test void disconnectAfterCommittedDeathRespawnAllowsSameUuidReconnect() throws Exception {
        try (var f = new Fixture()) {
            var handoff = f.deathRetiredSuccess(); var committed = f.player;
            assertEquals(State.COMMITTED, handoff.state());
            f.disconnectCurrent();
            var next = f.reconnect();
            assertNotSame(committed, next); f.assertCurrent(next);
            handoff.rollback(); f.assertCurrent(next);
        }
    }
    @Test void diagnosticNamesMissingPredicateAndCannotMaskAdmissionFailure() throws Exception {
        String previous = System.getProperty("phoenium.test.diagnostics");
        System.setProperty("phoenium.test.diagnostics", "true");
        try (var f = new Fixture()) {
            f.lookup.remove(f.player);
            String report = precheck(f, f.player, f.player, 1, true, -1);
            assertTrue(report.contains("worldUuid=ABSENT"));
            assertTrue(report.contains("worldId=ABSENT"));
            assertTrue(report.contains("playerList=true"));
            assertTrue(report.contains("result=NATIVE_ABSENCE_WITHOUT_COMPLETED_DEATH"));
            var original = assertThrows(ChunkProtocolException.class, f::open);
            assertTrue(original.getMessage().contains("old publication is not current"));
            assertTrue(((Incarnation) f.player).phoenium$respawnPrecheckLogged());
        } finally {
            if (previous == null) System.clearProperty("phoenium.test.diagnostics");
            else System.setProperty("phoenium.test.diagnostics", previous);
        }
    }

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
                new Answer(ServerLevel.class.getMethod("broadcastEntityEvent", Entity.class, byte.class), args -> null),
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
            var deathRemoved = new AtomicBoolean();
            var precheckLogged = new AtomicBoolean();
            ServerPlayer[] exact = new ServerPlayer[1];
            ServerPlayer result = stub(ServerPlayer.class, List.of(
                new Answer(ServerPlayer.class.getMethod("level"), args -> world),
                new Answer(ServerPlayer.class.getMethod("getGameProfile"), args -> new GameProfile(uuid, "RespawnWitness")),
                new Answer(ServerPlayer.class.getMethod("getScoreboardName"), args -> "RespawnWitness"),
                new Answer(ServerPlayer.class.getMethod("canBeSeenAsEnemy"), args -> true),
                new Answer(ServerPlayer.class.getMethod("isCreative"), args -> false),
                new Answer(ServerPlayer.class.getMethod("isSpectator"), args -> false),
                new Answer(ServerPlayer.class.getMethod("remove", Entity.RemovalReason.class), args -> {
                    exact[0].setRemoved((Entity.RemovalReason) args[0]); return null;
                }),
                new Answer(ServerPlayer.class.getMethod("onRemoval", Entity.RemovalReason.class), args -> null),
                new Answer(Incarnation.class.getMethod("phoenium$respawnRetired"), args -> retired.get()),
                new Answer(Incarnation.class.getMethod("phoenium$respawnRetired", boolean.class), args -> { retired.set((boolean) args[0]); return null; }),
                new Answer(Incarnation.class.getMethod("phoenium$deathRemovalCompleted"), args -> deathRemoved.get()),
                new Answer(Incarnation.class.getMethod("phoenium$deathRemovalCompleted", boolean.class), args -> { deathRemoved.set((boolean) args[0]); return null; }),
                new Answer(Incarnation.class.getMethod("phoenium$respawnPrecheckLogged"), args -> precheckLogged.get()),
                new Answer(Incarnation.class.getMethod("phoenium$respawnPrecheckLogged", boolean.class), args -> { precheckLogged.set((boolean) args[0]); return null; })), Incarnation.class);
            exact[0] = result;
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
        void nativeDeathTicks() throws Exception { nativeDeathTicks(20); }
        void nativeDeathTicks(int ticks) throws Exception {
            ServerPlayer dying = player;
            EntityInLevelCallback callback = (EntityInLevelCallback) java.lang.reflect.Proxy.newProxyInstance(
                EntityInLevelCallback.class.getClassLoader(), new Class<?>[] { EntityInLevelCallback.class },
                (proxy, method, args) -> {
                    if (method.getName().equals("onRemove")) {
                        assertEquals(Entity.RemovalReason.KILLED, args[0]);
                        lookup.remove(dying); worldPlayers.removeIf(value -> value == dying);
                        tracked.remove(dying); stored.remove(dying); removals.merge(dying, 1, Integer::sum);
                    }
                    return null;
                });
            dying.setLevelCallback(callback);
            var nativeTick = MethodHandles.privateLookupIn(LivingEntity.class, MethodHandles.lookup())
                .findSpecial(LivingEntity.class, "tickDeath", MethodType.methodType(void.class), LivingEntity.class);
            for (int tick = 0; tick < ticks; tick++) {
                try { nativeTick.invoke(dying); } catch (Throwable failure) { throw new AssertionError(failure); }
            }
            // Production invokes this at the RETURN of the same native method.
            PlayerRespawnHandoff.recordDeathRemovalCompleted(dying);
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
        PlayerRespawnHandoff deathRetiredSuccess() throws Exception {
            nativeDeathTicks(); return success();
        }
        void disconnectCurrent() {
            ServerPlayer old = player;
            try (var turn = service.scoreboardAccess()) {
                players.removeIf(value -> value == old);
                service.scoreboard.remove(old, false);
                if (uuids.get(uuid) == old) { uuids.remove(uuid); service.scoreboard.playerUuidRemoved(uuid); }
                service.scoreboard.remove(old, true);
            }
            if (lookup.getEntity(uuid) == old) lookup.remove(old);
            worldPlayers.removeIf(value -> value == old); tracked.remove(old); stored.remove(old);
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
