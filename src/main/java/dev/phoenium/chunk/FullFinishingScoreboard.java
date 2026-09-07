package dev.phoenium.chunk;

import io.netty.buffer.Unpooled;
import java.util.*;
import java.util.function.Supplier;
import net.minecraft.network.*;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
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
    private long nextSession, sequence, accepted, uncertain, committed, completed, directoryRevision;
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
    }
    public void playerUuidRemoved(UUID id) {
        require(); byUuid.remove(id); directoryRevision++; service.changed(FullFinishingServices.Kind.CONNECTIONS);
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
        int slots = Math.max(4, Math.multiplyExact(occurrences, 4));
        var recipients = List.copyOf(order);
        return new Operation(directoryRevision, slots, recipients);
    }
    public final class Operation {
        private final long directoryCut;
        private final int capacity;
        private final List<Membership> entries = new ArrayList<>();
        private final List<Session> recipients;
        private boolean closed;
        private Operation(long cut, int slots, List<Session> recipients) {
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
            "accepted", accepted, "uncertain", uncertain, "committed", committed, "completed", completed); }
    void close() {
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
        long snapshot, syncGeneration, lastAccepted;
        final ArrayDeque<Delta> catchup = new ArrayDeque<>();
        Session(long id, Connection connection, ServerGamePacketListenerImpl listener, ServerPlayer player, UUID uuid, String name) {
            this.id = id; this.connection = connection; this.listener = listener; this.player = player; this.uuid = uuid; this.name = name;
        }
    }
    private record Delta(long sequence, ClientboundSetPlayerTeamPacket packet) { }
}
