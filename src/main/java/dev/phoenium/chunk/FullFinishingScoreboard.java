package dev.phoenium.chunk;

import io.netty.buffer.Unpooled;
import java.util.*;
import java.util.function.Supplier;
import net.minecraft.network.*;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/**
 * The existing scoreboard's ordered synchronous service journal and connection
 * lifecycle. It observes original effects and send calls; it never replays a
 * mutation or changes an original membership return value/exception.
 */
public final class FullFinishingScoreboard {
    public interface ConnectionAccess { Connection phoenium$serviceConnection(); }
    private enum State { INITIALIZING, LIVE, CLOSED }
    public enum SendState { UNUSED, PENDING, ACCEPTED, UNCERTAIN, TERMINAL }
    private final FullFinishingServices service;
    private final MinecraftServer server;
    private final IdentityHashMap<Connection, Session> sessions = new IdentityHashMap<>();
    private final List<Session> order = new ArrayList<>();
    private final Map<UUID, ServerPlayer> byUuid = new HashMap<>();
    private final ThreadLocal<Membership> membership = new ThreadLocal<>();
    private final ThreadLocal<Broadcast> broadcast = new ThreadLocal<>();
    private long nextSession, nextRespawn, sequence, accepted, uncertain, committed, completed, directoryRevision;
    private int catchup;
    FullFinishingScoreboard(FullFinishingServices service) { this.service = service; server = service.server(); }

    /** Called before the actual initial scoreboard snapshot, before vanilla's list insertion. */
    public void initialSync(ServerPlayer player) {
        require(); Session session = session(player, true);
        session.state = State.INITIALIZING; session.syncGeneration++; session.snapshot = sequence;
        catchup -= session.catchup.size(); session.catchup.clear(); directoryRevision++;
    }
    public void failedPlacement(ServerPlayer player) { remove(player, true); }
    /** Actual List.add edge also covers respawn, which retains the Connection session. */
    public void registered(ServerPlayer player) {
        require(); Session session = session(player, true);
        if (session.respawn != null) session.respawn.registered(player);
        else if (PlayerRespawnHandoff.retired(player)) throw new ChunkProtocolException("retired respawn incarnation cannot register");
        session.player = player; session.listener = player.connection;
        session.uuid = player.getUUID(); session.name = player.getGameProfile().name();
        order.remove(session); order.add(session);
        while (!session.catchup.isEmpty()) {
            Delta delta = session.catchup.removeFirst(); catchup--;
            try { session.listener.send(delta.packet); accepted++; session.lastAccepted = delta.sequence; }
            catch (Throwable failure) { uncertain++; session.state = State.CLOSED; throw failure; }
        }
        session.state = State.LIVE; directoryRevision++; service.changed(FullFinishingServices.Kind.CONNECTIONS);
    }
    /** Exact List.remove edge: a respawn removes addressability but keeps its session. */
    public void remove(ServerPlayer player, boolean disconnect) {
        require(); Session session = session(player, false);
        if (session == null || session.player != player) return;
        order.remove(session); directoryRevision++;
        if (disconnect) {
            if (player instanceof PlayerRespawnHandoff.Incarnation access) {
                access.phoenium$deathRemovalCompleted(false);
                access.phoenium$creditsRemovalCompleted(false);
            }
            session.state = State.CLOSED; catchup -= session.catchup.size(); session.catchup.clear();
            sessions.remove(session.connection); session.player = null; session.listener = null;
        }
        service.changed(FullFinishingServices.Kind.CONNECTIONS);
    }
    public ServerPlayer lookupName(String name) {
        require();
        for (Session session : order) if (session.name.equalsIgnoreCase(name)) return session.player;
        return null;
    }
    public ServerPlayer lookupPlayer(UUID id) {
        require();
        return byUuid.get(id);
    }
    public void playerUuidPublished(UUID id, ServerPlayer player) {
        require(); FullEntityFacts.publish(player, true); byUuid.put(id, player); directoryRevision++;
        service.changed(FullFinishingServices.Kind.CONNECTIONS);
        PlayerRespawnHandoff handoff = respawn(player);
        if (handoff != null) handoff.uuidPublished(id, player);
    }
    public void playerUuidRemoved(UUID id) {
        require(); byUuid.remove(id); directoryRevision++; service.changed(FullFinishingServices.Kind.CONNECTIONS);
    }
    /** Retains the exact live connection/listener identities selected by the original player
     * list. This is a validation receipt only; packet production remains on the entity owner and
     * the original listener performs the send in its original synchronous order. */
    public TrackingCut trackingCut(List<ServerPlayer> actualPlayers) {
        return trackingCut(actualPlayers, null);
    }
    /** Read-only exact session receipt for a retained native pearl hit. No session is created. */
    public long portalSession(ServerPlayer player) {
        require();
        if (player == null || !(player.connection instanceof ConnectionAccess access)) return -1;
        Session session = sessions.get(access.phoenium$serviceConnection());
        return session != null && session.state == State.LIVE && session.player == player
            && session.listener == player.connection && session.listener.player == player
            && session.respawn == null && !session.awaitingRespawnListener
            && byUuid.get(player.getUUID()) == player && !PlayerRespawnHandoff.retired(player)
                ? session.id : -1;
    }
    public TrackingCut trackingCut(List<ServerPlayer> actualPlayers, PlayerRespawnHandoff.Tracking transition) {
        FullFinishingServices.requireServer(server, FullFinishingServices.Kind.CONNECTIONS);
        var result = new ArrayList<TrackingSession>(actualPlayers.size());
        var unique = Collections.newSetFromMap(new IdentityHashMap<ServerPlayer, Boolean>());
        for (ServerPlayer player : actualPlayers) {
            if (!unique.add(player)) throw FullFinishingServices.failure(
                new ChunkProtocolException("tracking player directory contains a duplicate alias"));
            Session session = session(player, false);
            boolean transitioning = transition != null && transition.player == player;
            if (transitioning) transition.require(player, session == null ? -1 : session.id);
            if (session == null || session.state != State.LIVE
                    || (transitioning ? session.respawn != transition.handoff()
                        : session.player != player || session.respawn != null && !session.respawn.stillCurrent(player)
                            || PlayerRespawnHandoff.retired(player))
                    || session.listener != player.connection)
                throw FullFinishingServices.failure(
                    new ChunkProtocolException("tracking player lacks a current live connection session"));
            result.add(new TrackingSession(session.id, player, session.listener, session.uuid, transitioning ? transition : null));
        }
        return new TrackingCut(directoryRevision, List.copyOf(result));
    }

    /** S9-D4: the revision a {@link TrackingCut} taken now would carry; checked like trackingCut's own access. */
    public long directoryRevision() {
        FullFinishingServices.requireServer(server, FullFinishingServices.Kind.CONNECTIONS);
        return directoryRevision;
    }

    public void requireTrackingSession(TrackingCut cut, TrackingSession receipt) {
        FullFinishingServices.requireServer(server, FullFinishingServices.Kind.CONNECTIONS);
        if (cut.directoryRevision != directoryRevision || !cut.contains(receipt))
            throw FullFinishingServices.failure(new ChunkProtocolException("tracking connection cut became stale"));
        // S9-D3: a session can pass the identity checks below only if its listener is receipt.listener, and a
        // listener's transport connection is final, so the only possible match is the session keyed by that
        // connection. This is the same answer as scanning every live session for receipt.sessionId, in O(1).
        Session session = receipt.listener instanceof ConnectionAccess access
            ? sessions.get(access.phoenium$serviceConnection()) : null;
        if (session != null && session.id != receipt.sessionId) session = null;
        if (receipt.transition != null) receipt.transition.require(receipt.player, receipt.sessionId);
        if (session == null || session.state != State.LIVE
                || (receipt.transition == null ? session.player != receipt.player
                    || session.respawn != null && !session.respawn.stillCurrent(receipt.player)
                    || PlayerRespawnHandoff.retired(receipt.player) : session.respawn != receipt.transition.handoff())
                || session.listener != receipt.listener || !Objects.equals(session.uuid, receipt.uuid)
                || receipt.player.connection != receipt.listener)
            throw FullFinishingServices.failure(new ChunkProtocolException("tracking connection identity changed"));
    }

    public record TrackingCut(long directoryRevision, List<TrackingSession> sessions, Set<TrackingSession> members) {
        /** {@code members} is always derived from {@code sessions}: the exact receipt objects, by identity (S9-D3). */
        public TrackingCut {
            sessions = List.copyOf(sessions);
            Set<TrackingSession> exact = Collections.newSetFromMap(new IdentityHashMap<>(sessions.size() * 2));
            exact.addAll(sessions);
            members = Collections.unmodifiableSet(exact);
        }
        public TrackingCut(long directoryRevision, List<TrackingSession> sessions) { this(directoryRevision, sessions, Set.of()); }
        /** O(1) form of {@code sessions().stream().anyMatch(value -> value == receipt)}. */
        public boolean contains(TrackingSession receipt) { return members.contains(receipt); }
        // value semantics stay those of the original two-component record; members is a derived index
        @Override public boolean equals(Object other) {
            return other instanceof TrackingCut cut && directoryRevision == cut.directoryRevision && sessions.equals(cut.sessions);
        }
        @Override public int hashCode() { return 31 * Long.hashCode(directoryRevision) + sessions.hashCode(); }
        @Override public String toString() { return "TrackingCut[directoryRevision=" + directoryRevision + ", sessions=" + sessions + "]"; }
    }
    public record TrackingSession(long sessionId, ServerPlayer player,
                                  ServerGamePacketListenerImpl listener, UUID uuid, PlayerRespawnHandoff.Tracking transition) { }

    PlayerRespawnHandoff openRespawn(PlayerRespawnHandoff.Directory directory, ServerPlayer player) {
        require(); Session session = session(player, false);
        if (Boolean.getBoolean("phoenium.test.diagnostics") && player instanceof PlayerRespawnHandoff.Incarnation access
                && !access.phoenium$respawnPrecheckLogged()) {
            access.phoenium$respawnPrecheckLogged(true);
            try {
                boolean exact = session != null && session.state == State.LIVE && session.player == player
                    && Objects.equals(session.uuid, player.getUUID())
                    && session.listener == player.connection && session.connection ==
                        ((ConnectionAccess) player.connection).phoenium$serviceConnection();
                org.slf4j.LoggerFactory.getLogger(PlayerRespawnHandoff.class).info(
                    "PHOENIUM_PLAYER_RESPAWN_PRECHECK {}",
                    PlayerRespawnHandoff.precheck(directory, player, byUuid.get(player.getUUID()), session == null ? -1 : session.id,
                        exact, session == null || session.respawn == null ? -1 : session.respawn.generation));
            } catch (Throwable diagnosticFailure) {
                // The admission decision and its original failure remain authoritative.
                org.slf4j.LoggerFactory.getLogger(PlayerRespawnHandoff.class).warn(
                    "PHOENIUM_PLAYER_RESPAWN_PRECHECK_UNAVAILABLE old={} cause={}",
                    System.identityHashCode(player), diagnosticFailure.toString());
            }
        }
        if (session == null || session.state != State.LIVE || session.player != player || session.respawn != null)
            throw new ChunkProtocolException("respawn requires one exact current live session");
        PlayerRespawnHandoff handoff = new PlayerRespawnHandoff(service, directory, player, ++nextRespawn, session.id, session.connection);
        session.respawn = handoff; directoryRevision++; return handoff;
    }
    public PlayerRespawnHandoff respawn(ServerPlayer player) {
        Session session = session(player, false);
        return session != null && session.respawn != null && session.respawn.matches(player) ? session.respawn : null;
    }
    /** Resolve the old alias while vanilla has assigned the listener but preparation has not run. */
    public PlayerRespawnHandoff requireRespawn(ServerPlayer player) {
        PlayerRespawnHandoff handoff = respawn(player);
        if (handoff == null) throw new ChunkProtocolException("respawn callback lost its exact handoff");
        return handoff;
    }
    void preparedRespawn(PlayerRespawnHandoff handoff) {
        require(); requireRespawn(handoff); directoryRevision++; service.changed(FullFinishingServices.Kind.CONNECTIONS);
    }
    void commitRespawn(PlayerRespawnHandoff handoff) {
        require(); Session session = requireRespawn(handoff);
        if (session.player != handoff.replacement() || byUuid.get(handoff.uuid) != handoff.replacement() || !order.contains(session))
            throw new ChunkProtocolException("respawn scoreboard publication incomplete");
        session.awaitingRespawnListener = true; directoryRevision++;
    }
    void abortRespawn(PlayerRespawnHandoff handoff) {
        require(); Session session = requireRespawn(handoff);
        order.remove(session); catchup -= session.catchup.size(); session.catchup.clear();
        sessions.remove(session.connection); session.state = State.CLOSED;
        session.player = null; session.listener = null; directoryRevision++;
        service.changed(FullFinishingServices.Kind.CONNECTIONS);
    }
    void detachRespawn(PlayerRespawnHandoff handoff) {
        require(); Session session = sessions.get(handoff.connection);
        if (session != null && session.id == handoff.sessionId && session.respawn == handoff) session.respawn = null;
    }
    private Session requireRespawn(PlayerRespawnHandoff handoff) {
        Session session = sessions.get(handoff.connection);
        if (session == null || session.id != handoff.sessionId || session.respawn != handoff)
            throw new ChunkProtocolException("respawn session generation changed");
        return session;
    }
    public void respawnListenerConverged(ServerPlayer player) {
        require(); Session session = session(player, false);
        if (session == null || !session.awaitingRespawnListener || session.player != player
                || session.listener.player != player || session.respawn != null || session.state != State.LIVE)
            throw new ChunkProtocolException("committed respawn listener did not converge");
        session.awaitingRespawnListener = false;
        if (Boolean.getBoolean("phoenium.test.diagnostics")) org.slf4j.LoggerFactory.getLogger(PlayerRespawnHandoff.class).info(
            "PHOENIUM_PLAYER_RESPAWN_LISTENER uuid={} session={} incarnation={} result=CONVERGED",
            session.uuid, session.id, ((FullEntityFacts.Access) player).phoenium$entityIncarnation());
    }
    public void abortRespawns(ServerLevel world) {
        for (Session session : List.copyOf(sessions.values()))
            if (session.respawn != null && (world == null || session.respawn.touches(world))) session.respawn.rollback();
    }
    private Session session(ServerPlayer player, boolean create) {
        if (!create && (player == null || player.connection == null)) return null;
        if (!(player.connection instanceof ConnectionAccess access))
            throw FullFinishingServices.failure(new ChunkProtocolException("connection identity producer is not installed"));
        Connection connection = access.phoenium$serviceConnection();
        Session found = sessions.get(connection);
        if (found == null && create) {
            found = new Session(++nextSession, connection, player.connection, player, player.getUUID(), player.getGameProfile().name());
            sessions.put(connection, found);
        } else if (found != null && found.listener != player.connection) {
            // An actual listener replacement creates a new ordered stream, even on one channel.
            catchup -= found.catchup.size(); found.catchup.clear(); order.remove(found);
            found.state = State.CLOSED;
            if (create) {
                found = new Session(++nextSession, connection, player.connection, player, player.getUUID(), player.getGameProfile().name());
                sessions.put(connection, found);
            } else return null;
        }
        return found;
    }
    /** Immutable directory cut and the finite maximum footprint are fixed before regional entry. */
    public Operation reserve(int occurrences) {
        if (occurrences < 0) throw new IllegalArgumentException("negative scoreboard occurrence capacity");
        long slots = Math.max(4L, Math.multiplyExact((long) occurrences, 4L));
        var recipients = List.copyOf(order);
        return new Operation(directoryRevision, slots, recipients);
    }
    public final class Operation {
        private final long directoryCut;
        private final long capacity;
        private final List<Membership> entries = new ArrayList<>();
        private final List<Session> recipients;
        private boolean closed;
        private Operation(long cut, long slots, List<Session> recipients) {
            directoryCut = cut; capacity = slots; this.recipients = recipients;
        }
        public boolean valid() { return !closed && directoryRevision == directoryCut; }
        public void close() {
            if (closed) return;
            for (Membership entry : entries) {
                if (!entry.terminal) throw FullFinishingServices.failure(new ChunkProtocolException("scoreboard effect left without a terminal synchronous outcome"));
                entry.member = null; entry.team = null;
            }
            entries.clear(); closed = true;
        }
        private Membership next() {
            if (closed || entries.size() == capacity)
                throw FullFinishingServices.failure(new ChunkProtocolException("scoreboard callback exceeded its prepared occurrence footprint"));
            Membership entry = new Membership(new SendState[recipients.size()], null);
            entries.add(entry); return entry;
        }
    }
    public Membership begin(String member, String team) {
        require(); FullFinishingServices.Reservation full = FullFinishingServices.currentReservation(server);
        Membership parent = membership.get();
        Membership entry = full == null ? new Membership(new SendState[order.size()], parent) : full.scoreboard().next();
        entry.parent = parent; entry.member = member; entry.team = team; entry.sequence = ++sequence;
        Arrays.fill(entry.sends, SendState.UNUSED); membership.set(entry); return entry;
    }
    public final class Membership implements AutoCloseable {
        private final SendState[] sends;
        private Membership parent;
        private String member, team;
        private long sequence;
        private int primitivePrefix;
        private boolean terminal, result;
        private Throwable failure;
        private Membership(SendState[] sends, Membership parent) { this.sends = sends; this.parent = parent; }
        public void result(boolean result) { this.result = result; }
        public void failed(Throwable failure) { this.failure = failure; }
        @Override public void close() {
            if (terminal) return;
            if (membership.get() != this) throw FullFinishingServices.failure(new ChunkProtocolException("scoreboard occurrence escaped original nesting"));
            for (int i = 0; i < sends.length; i++) if (sends[i] == SendState.UNUSED || sends[i] == SendState.PENDING) sends[i] = SendState.TERMINAL;
            terminal = true; completed++; if (parent == null) membership.remove(); else membership.set(parent);
        }
    }
    public void primitive() {
        require(); Membership entry = membership.get();
        if (entry != null) entry.primitivePrefix++;
        committed++; service.changed(FullFinishingServices.Kind.SCOREBOARD);
    }
    public Broadcast beginBroadcast(Packet<?> packet, List<ServerPlayer> actualPlayers) {
        require();
        if (!(packet instanceof ClientboundSetPlayerTeamPacket team)) {
            Broadcast ordinary = new Broadcast(broadcast.get(), null, null); broadcast.set(ordinary); return ordinary;
        }
        FullFinishingServices.Reservation full = FullFinishingServices.currentReservation(server);
        List<Session> recipients = full == null ? List.copyOf(order) : full.scoreboard().recipients;
        if (actualPlayers.size() != recipients.size())
            throw FullFinishingServices.failure(new ChunkProtocolException("connection directory does not match original broadcast list"));
        for (int i = 0; i < actualPlayers.size(); i++) if (actualPlayers.get(i) != recipients.get(i).player
                || actualPlayers.get(i).connection != recipients.get(i).listener)
            throw FullFinishingServices.failure(new ChunkProtocolException("original broadcast session order changed"));
        Membership entry = membership.get();
        // Team creation/property updates in ordinary serial phases retain a synchronous journal too.
        if (entry == null) entry = new Membership(new SendState[recipients.size()], null);
        Broadcast record = new Broadcast(broadcast.get(), entry, recipients);
        record.sequence = ++sequence; broadcast.set(record);
        for (Session session : sessions.values()) if (session.state == State.INITIALIZING && !recipients.contains(session)) {
            int nextCatchup = Math.incrementExact(catchup);
            session.catchup.addLast(new Delta(record.sequence, detached(team))); catchup = nextCatchup;
        }
        return record;
    }
    public final class Broadcast implements AutoCloseable {
        private final Broadcast parent;
        private final Membership entry;
        private final List<Session> recipients;
        private int index;
        private long sequence;
        private Broadcast(Broadcast parent, Membership entry, List<Session> recipients) { this.parent = parent; this.entry = entry; this.recipients = recipients; }
        public void send(ServerGamePacketListenerImpl listener, Runnable original) {
            if (entry == null) { original.run(); return; }
            if (index == recipients.size() || recipients.get(index).listener != listener)
                throw FullFinishingServices.failure(new ChunkProtocolException("scoreboard send left its exact recipient order"));
            Session session = recipients.get(index); int slot = index++; entry.sends[slot] = SendState.PENDING;
            try { original.run(); entry.sends[slot] = SendState.ACCEPTED; accepted++; session.lastAccepted = sequence; }
            catch (Throwable failure) { entry.sends[slot] = SendState.UNCERTAIN; uncertain++; throw failure; }
        }
        @Override public void close() {
            if (entry != null) for (int i = index; i < entry.sends.length; i++) entry.sends[i] = SendState.TERMINAL;
            if (parent == null) broadcast.remove(); else broadcast.set(parent);
        }
    }
    public void send(ServerGamePacketListenerImpl listener, Runnable original) {
        Broadcast record = broadcast.get(); if (record == null) original.run(); else record.send(listener, original);
    }
    private ClientboundSetPlayerTeamPacket detached(ClientboundSetPlayerTeamPacket packet) {
        var bytes = new RegistryFriendlyByteBuf(Unpooled.buffer(), server.registryAccess());
        try { ClientboundSetPlayerTeamPacket.STREAM_CODEC.encode(bytes, packet); return ClientboundSetPlayerTeamPacket.STREAM_CODEC.decode(bytes); }
        finally { bytes.release(); }
    }
    private void require() { FullFinishingServices.requireServer(server, FullFinishingServices.Kind.SCOREBOARD); }
    public Map<String, Long> measurements() { return Map.of("sessions", (long) sessions.size(), "catchup", (long) catchup,
            "respawns", sessions.values().stream().filter(session -> session.respawn != null).count(),
            "accepted", accepted, "uncertain", uncertain, "committed", committed, "completed", completed); }
    void close() {
        if (sessions.values().stream().anyMatch(session -> session.respawn != null))
            throw new ChunkProtocolException("server close retained an open respawn handoff");
        for (Session session : sessions.values()) {
            session.catchup.clear(); session.player = null; session.listener = null; session.state = State.CLOSED;
        }
        sessions.clear(); order.clear(); byUuid.clear(); catchup = 0;
        membership.remove(); broadcast.remove();
    }
    private static final class Session {
        final long id;
        final Connection connection;
        ServerGamePacketListenerImpl listener;
        ServerPlayer player;
        UUID uuid;
        String name;
        State state = State.INITIALIZING;
        PlayerRespawnHandoff respawn;
        boolean awaitingRespawnListener;
        long snapshot, syncGeneration, lastAccepted;
        final ArrayDeque<Delta> catchup = new ArrayDeque<>();
        Session(long id, Connection connection, ServerGamePacketListenerImpl listener, ServerPlayer player, UUID uuid, String name) {
            this.id = id; this.connection = connection; this.listener = listener; this.player = player; this.uuid = uuid; this.name = name;
        }
    }
    private record Delta(long sequence, ClientboundSetPlayerTeamPacket packet) { }
}
