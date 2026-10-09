package dev.phoenium.service;

import dev.phoenium.chunk.FullEntityFacts;
import dev.phoenium.chunk.FullFinishingScoreboard;
import dev.phoenium.chunk.FullFinishingServices;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

/** An original nearby-broadcast invocation consumes current owner-published player
 * coordinates and the existing live connection directory. The original player-list
 * order, dimension/distance conditions and synchronous send calls remain unchanged.
 * This scope grants no authority over a recipient entity and retains no history. */
public final class RegionalBlockEventBroadcast implements AutoCloseable {
    private static final ThreadLocal<RegionalBlockEventBroadcast> CURRENT = new ThreadLocal<>();
    private final RegionalBlockEventBroadcast parent;
    private final FullFinishingServices services;
    private final FullFinishingScoreboard.TrackingCut connections;
    private final Map<ServerPlayer, FullFinishingScoreboard.TrackingSession> sessions = new IdentityHashMap<>();
    private ServerPlayer selected;
    private FullEntityFacts.PublishedPosition projection;
    private boolean deathRetired;
    private boolean closed;

    private RegionalBlockEventBroadcast(ServerLevel level) {
        services = FullFinishingServices.forServer(level.getServer());
        FullFinishingServices.requireServer(level.getServer(), FullFinishingServices.Kind.CONNECTIONS);
        connections = services.scoreboard.trackingCut(List.copyOf(level.getServer().getPlayerList().getPlayers()));
        connections.sessions().forEach(session -> sessions.put(session.player(), session));
        parent = CURRENT.get();
        CURRENT.set(this);
    }

    public static RegionalBlockEventBroadcast open(ServerLevel level) { return new RegionalBlockEventBroadcast(level); }
    public static RegionalBlockEventBroadcast current() { return CURRENT.get(); }

    /** Called at the original level() read, before vanilla's dimension condition. */
    public ServerLevel level(ServerPlayer player) {
        requireOpen();
        selected = player;
        projection = dev.phoenium.chunk.PlayerRespawnHandoff.deathPosition(player);
        deathRetired = projection != null;
        if(!deathRetired) projection = services.entityFacts.publishedPosition(player);
        requireSelected(player);
        return projection.candidate().fact().world();
    }

    public double x(ServerPlayer player) { requireSelected(player); return projection.position().x(); }
    public double y(ServerPlayer player) { requireSelected(player); return projection.position().y(); }
    public double z(ServerPlayer player) { requireSelected(player); return projection.position().z(); }

    /** Revalidate the current live stream immediately before the original submission. */
    public void send(ServerGamePacketListenerImpl listener, Runnable original) {
        requireSelected(selected);
        if (sessions.get(selected).listener() != listener)
            throw new IllegalStateException("block-event broadcast changed its selected connection");
        original.run();
    }

    private void requireSelected(ServerPlayer player) {
        requireOpen();
        if (player == null || selected != player || projection == null
                || (deathRetired ? dev.phoenium.chunk.PlayerRespawnHandoff.deathPosition(player)!=projection
                    : !services.entityFacts.validTrackingCandidate(projection.candidate())))
            throw new IllegalStateException("block-event recipient observation became stale");
        services.scoreboard.requireTrackingSession(connections,
            Objects.requireNonNull(sessions.get(player), "block-event recipient outside original live list"));
    }
    private void requireOpen() {
        if (closed || CURRENT.get() != this) throw new IllegalStateException("expired block-event broadcast scope");
    }
    @Override public void close() {
        requireOpen(); closed = true;
        if (parent == null) CURRENT.set(null); else CURRENT.set(parent);
        selected = null; projection = null; sessions.clear();
    }
}
