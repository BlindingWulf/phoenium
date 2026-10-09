package dev.phoenium.live;

import dev.phoenium.core.WorldId;
import dev.phoenium.ownership.OwnershipContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.TheEndGatewayBlockEntity;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

/**
 * Pinned 26.2 {@code TheEndGatewayBlockEntity.getPortalPosition} either creates the exit gateway
 * (exit unknown, End) or searches the exit's chunks ({@code findExitPosition}, any non-exact gateway)
 * with blocking {@code Level.getChunk} reads. A non-player entity reaches it from
 * {@code Entity.handlePortal} inside its owner tick, where an unfinished chunk dependency is a
 * protocol failure (preserved installed run g3-natural-w1).
 *
 * <p>Two narrow cuts on {@code EndGatewayBlock} keep the native code and result:
 * <ul>
 * <li><b>arming gate</b> ({@code entityInside}): for an entity that would arm a gateway whose exit must
 *     be searched or created, and which has no fresh prepared exit, arming is deferred by returning
 *     without touching the entity or the gateway cooldown, and a preparation request is queued. The
 *     entity is still inside next tick, so native arming happens as soon as the exit is prepared.</li>
 * <li><b>consumption</b> ({@code getPortalDestination}): under an owner, the prepared position replaces
 *     the blocking search. Owner-free callers (players) and gateways that need no search run natively.</li>
 * </ul>
 * The coordinator runs the unmodified native method owner-free, where blocking chunk loads and the
 * exit-island creation are legal. No owner waits for another owner.
 */
public final class GatewayExitBoundary implements AutoCloseable {
    /** A prepared exit is consumed within a tick or two of arming; this bounds how long a stale one lives. */
    static final long FRESH_TICKS = 200;
    static final int LIMIT = 1024;

    private record Key(WorldId world, long pos) { }
    private record Request(ServerLevel level, BlockPos pos, TheEndGatewayBlockEntity gateway) { }
    private record Prepared(Vec3 position, long gameTime) { }

    /**
     * Native same-dimension teleport continues the entity's tick at the arrival, so that chunk must be
     * entity-ticking before the entity is armed. The native implementation requests the same timed PORTAL
     * ticket that Entity.placePortalTicket requests after the move, early.
     */
    public interface Arrival {
        boolean ready(ServerLevel level, Vec3 position);
        void demand(ServerLevel level, Vec3 position);
        Arrival NATIVE = new Arrival() {
            @Override public boolean ready(ServerLevel level, Vec3 position) {
                var arrival = BlockPos.containing(position);
                return level.isPositionEntityTicking(arrival)
                    && level.areEntitiesActuallyLoadedAndTicking(net.minecraft.world.level.ChunkPos.containing(arrival));
            }
            @Override public void demand(ServerLevel level, Vec3 position) {
                level.getChunkSource().addTicketWithRadius(net.minecraft.server.level.TicketType.PORTAL,
                    net.minecraft.world.level.ChunkPos.containing(BlockPos.containing(position)), 3);
            }
        };
    }

    private final NativePortalTransfer.Authority authority;
    private final Arrival arrival;
    private final Map<Key, Request> requests = new ConcurrentHashMap<>();
    private final Map<Key, Prepared> prepared = new ConcurrentHashMap<>();
    private final AtomicLong requested = new AtomicLong(), preparedCount = new AtomicLong(),
        consumed = new AtomicLong(), missing = new AtomicLong();
    private volatile boolean closed;

    public GatewayExitBoundary(NativePortalTransfer.Authority authority) { this(authority, Arrival.NATIVE); }

    public GatewayExitBoundary(NativePortalTransfer.Authority authority, Arrival arrival) {
        this.authority = Objects.requireNonNull(authority, "authority");
        this.arrival = Objects.requireNonNull(arrival, "arrival");
    }

    private boolean arrivalReady(Prepared prepared, ServerLevel level) {
        return prepared.position() == null || arrival.ready(level, prepared.position());
    }

    private void demandArrival(Prepared prepared, ServerLevel level) {
        if (prepared.position() != null) arrival.demand(level, prepared.position());
    }

    /** True when the native exit lookup would read chunks or create the exit gateway. */
    static boolean needsPreparation(TheEndGatewayBlockEntity gateway, ServerLevel level) {
        var access = (dev.phoenium.mixin.TheEndGatewayExitAccess) gateway;
        boolean unknown = access.phoenium$exitPortal() == null;
        return unknown ? level.dimension() == Level.END : !access.phoenium$exactTeleport();
    }

    /** Native entityInside head: returns false to defer arming (the caller returns without effect). */
    public boolean mayArm(Level level, BlockPos pos, Entity entity) {
        if (OwnershipContext.current().isEmpty() || closed || !authority.running()
                || !(level instanceof ServerLevel server) || entity instanceof ServerPlayer
                || !authority.worldActive(server)) return true;
        // Native arming conditions, in the pinned order: usable portal entity, gateway block entity, not cooling down.
        if (!entity.canUsePortal(false) || !(server.getBlockEntity(pos) instanceof TheEndGatewayBlockEntity gateway)
                || gateway.isCoolingDown() || !needsPreparation(gateway, server)) return true;
        Key key = new Key(authority.worldId(server), pos.asLong());
        Prepared ready = prepared.get(key);
        if (ready != null && fresh(ready, server) && arrivalReady(ready, server)) return true;
        if (requests.size() >= LIMIT && !requests.containsKey(key))
            throw new IllegalStateException("gateway exit preparation backlog exceeded " + LIMIT);
        if (requests.putIfAbsent(key, new Request(server, pos.immutable(), gateway)) == null) {
            requested.incrementAndGet();
            trace("REQUESTED", key, entity, null);
        }
        return false;
    }

    /**
     * Wraps the native {@code getPortalPosition} call. Under an owner a gateway that needs a search uses
     * the prepared exit exactly once; a missing one yields no destination instead of a blocking read.
     */
    public Vec3 portalPosition(TheEndGatewayBlockEntity gateway, ServerLevel level, BlockPos pos, Supplier<Vec3> nativeCall) {
        if (OwnershipContext.current().isEmpty() || closed || !authority.running() || !authority.worldActive(level)
                || !needsPreparation(gateway, level)) return nativeCall.get();
        Key key = new Key(authority.worldId(level), pos.asLong());
        Prepared ready = prepared.remove(key);
        if (ready != null && fresh(ready, level)) {
            consumed.incrementAndGet();
            trace("CONSUMED", key, null, ready.position());
            return ready.position();
        }
        missing.incrementAndGet();
        trace("MISSING", key, null, null);
        return null;
    }

    /** Owner-free boundary: runs the unmodified native lookup for every queued request. */
    /** S9-D11: {@link #advance()} would settle nothing now. */
    public boolean quiet() { return requests.isEmpty(); }
    public int advance() {
        if (OwnershipContext.current().isPresent())
            throw new IllegalStateException("gateway exit coordinator retained owner authority");
        if (requests.isEmpty()) return 0; // S9-D11: nothing requested
        int count = 0;
        RuntimeException failure = null;
        for (Key key : new ArrayList<>(requests.keySet())) {
            Request request = requests.remove(key);
            if (request == null) continue;
            if (closed || !authority.running() || !authority.worldActive(request.level())) continue;
            try {
                // The block entity may have been replaced since the request; resolve it again.
                if (!(request.level().getBlockEntity(request.pos()) instanceof TheEndGatewayBlockEntity gateway)
                        || !needsPreparation(gateway, request.level())) continue;
                Prepared existing = prepared.get(key);
                if (existing != null && fresh(existing, request.level())) {
                    // Exit known; only the arrival chunk is still becoming entity-ticking.
                    if (!arrivalReady(existing, request.level())) demandArrival(existing, request.level());
                    continue;
                }
                Vec3 position = gateway.getPortalPosition(request.level(), request.pos());
                Prepared fresh = new Prepared(position, request.level().getGameTime());
                demandArrival(fresh, request.level());
                prepared.put(key, fresh);
                preparedCount.incrementAndGet();
                count++;
                trace("PREPARED", key, null, position);
            } catch (RuntimeException thrown) {
                if (failure == null) failure = thrown; else failure.addSuppressed(thrown);
            }
        }
        if (failure != null) throw failure;
        return count;
    }

    private static boolean fresh(Prepared prepared, ServerLevel level) {
        long age = level.getGameTime() - prepared.gameTime();
        return age >= 0 && age <= FRESH_TICKS;
    }

    public int pendingCount() { return requests.size(); }
    public int preparedCount() { return prepared.size(); }
    public long requestedTotal() { return requested.get(); }
    public long consumedTotal() { return consumed.get(); }
    public long missingTotal() { return missing.get(); }

    public void worldClosing(ServerLevel level) {
        WorldId world = authority.worldId(level);
        requests.keySet().removeIf(key -> key.world().equals(world));
        prepared.keySet().removeIf(key -> key.world().equals(world));
    }

    @Override public void close() {
        closed = true;
        requests.clear();
        prepared.clear();
    }

    private static void trace(String phase, Key key, Entity entity, Vec3 position) {
        if (!Boolean.getBoolean("phoenium.test.diagnostics")) return;
        try {
            LoggerFactory.getLogger(GatewayExitBoundary.class).info(
                "PHOENIUM_GATEWAY_EXIT phase={} world={} pos={} entity={} position={} owner={}",
                phase, key.world(), BlockPos.of(key.pos()).toShortString(),
                entity == null ? "NONE" : entity.getUUID(), position == null ? "NONE" : position,
                OwnershipContext.current().map(context -> context.region().toString()).orElse("NONE"));
        } catch (Throwable ignored) { /* Diagnostics never replace the native outcome. */ }
    }

    /** Test seam: the requests currently queued. */
    List<BlockPos> queued() { return requests.values().stream().map(Request::pos).toList(); }
}
