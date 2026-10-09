package dev.phoenium.live;

import dev.phoenium.ownership.OwnershipContext;
import dev.phoenium.region.RegionId;
import dev.phoenium.service.RegionalServiceKind;
import dev.phoenium.topology.RoutingKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.LoggerFactory;

/**
 * Pinned 26.2 {@code Entity.placePortalTicket} registers a timed PORTAL ticket for the chunk at the
 * entity's new position. A same-dimension End gateway runs it from {@code teleportSameDimension}
 * under the owner of the entity's <em>source</em> chunk, immediately after a position change that
 * may land in a chunk another region owns. The ticket request is non-blocking (it only registers
 * chunk demand), so the only thing wrong is the owner: it must run under the owner of the ticket
 * chunk's route.
 *
 * <p>This boundary is deliberately the only change. A call whose current owner already owns the
 * ticket chunk, an owner-free call, and a call with no published route all keep the native inline
 * behaviour and timing. A genuinely foreign-owned ticket is retained as an immutable
 * (entity, level, position) fact and replayed through the same native method at the next
 * owner-free boundary under the destination owner. No owner ever waits for another owner, the
 * entity is never touched, and no source or destination state is re-derived: a stale fact (closed
 * runtime, inactive or different world) is dropped rather than applied.
 */
public final class PortalTicketBoundary implements AutoCloseable {
    /** Replays the native ticket for an already captured position under the destination owner. */
    public interface Replay { void place(Entity entity, BlockPos position); }

    /** A foreign-owner ticket request is bounded by gateway traversals, not by tick count. */
    static final int LIMIT = 4096;

    private final NativePortalTransfer.Authority authority;
    private final Function<RoutingKey, Optional<RegionId>> owners;
    private final Replay replay;
    private final ConcurrentLinkedQueue<Pending> pending = new ConcurrentLinkedQueue<>();
    private final AtomicLong sequence = new AtomicLong(), deferred = new AtomicLong(),
        placed = new AtomicLong(), dropped = new AtomicLong();
    private volatile boolean closed;

    public PortalTicketBoundary(NativePortalTransfer.Authority authority,
            Function<RoutingKey, Optional<RegionId>> owners, Replay replay) {
        this.authority = Objects.requireNonNull(authority, "authority");
        this.owners = Objects.requireNonNull(owners, "owners");
        this.replay = Objects.requireNonNull(replay, "replay");
    }

    /**
     * Called at the head of the native ticket method. Returns true only when the request was
     * retained for the destination owner; false means the caller continues the native body now.
     */
    public boolean place(Entity entity, BlockPos position) {
        OwnershipContext.Active context = OwnershipContext.current().orElse(null);
        if (context == null || closed || !authority.running()
                || !(entity.level() instanceof ServerLevel level) || !authority.worldActive(level)) return false;
        ChunkPos chunk = ChunkPos.containing(position);
        RoutingKey key = new RoutingKey(authority.worldId(level), chunk.x(), chunk.z());
        RegionId owner = owners.apply(key).orElse(null);
        if (owner == null || owner.equals(context.region())) {
            traceInline(entity, key, position, context.region(), owner);
            return false;
        }
        if (pending.size() >= LIMIT)
            throw new IllegalStateException("foreign portal ticket backlog exceeded " + LIMIT);
        Pending request = new Pending(sequence.incrementAndGet(), entity, level, key, position, context.region());
        pending.add(request);
        deferred.incrementAndGet();
        trace("DEFERRED", request, owner);
        return true;
    }

    /** Owner-free boundary. Returns the number of tickets placed under their destination owner. */
    /** S9-D11: {@link #advance()} would settle nothing now. */
    public boolean quiet() { return pending.isEmpty(); }
    public int advance() {
        if (OwnershipContext.current().isPresent())
            throw new IllegalStateException("portal ticket coordinator retained owner authority");
        if (pending.isEmpty()) return 0; // S9-D11: nothing requested
        int count = 0;
        // Snapshot the queue: a replay that itself requests a ticket is handled at the next boundary.
        List<Pending> batch = new ArrayList<>(pending.size());
        for (Pending request; (request = pending.poll()) != null; ) batch.add(request);
        RuntimeException failure = null;
        for (Pending request : batch) {
            RegionId owner = owners.apply(request.key()).orElse(null);
            if (closed || owner == null || !authority.running() || !authority.worldActive(request.level())
                    || request.entity().level() != request.level()) {
                dropped.incrementAndGet();
                trace("DROPPED", request, owner);
                continue;
            }
            // Catch inside the owner body, as NativePortalTransfer does: an exception that escapes it
            // would trip the shared failure barrier and strand every other retained request.
            RuntimeException[] thrown = new RuntimeException[1];
            try {
                authority.execute(request.key(), RegionalServiceKind.ENTITY_PORTAL_DESTINATION, () -> {
                    try {
                        RegionId actual = OwnershipContext.current().orElseThrow().region();
                        if (!actual.equals(owners.apply(request.key()).orElse(null)))
                            throw new IllegalStateException("portal ticket executed under a non-owner " + actual);
                        replay.place(request.entity(), request.position());
                    } catch (RuntimeException caught) { thrown[0] = caught; }
                });
            } catch (RuntimeException escaped) { thrown[0] = thrown[0] == null ? escaped : thrown[0]; }
            if (thrown[0] == null) {
                placed.incrementAndGet();
                count++;
                trace("PLACED", request, owner);
            } else {
                dropped.incrementAndGet();
                trace("FAILED", request, owner);
                if (failure == null) failure = thrown[0];
                else if (failure != thrown[0]) failure.addSuppressed(thrown[0]);
            }
        }
        if (failure != null) throw failure;
        return count;
    }

    public int pendingCount() { return pending.size(); }
    public long deferredCount() { return deferred.get(); }
    public long placedCount() { return placed.get(); }
    public long droppedCount() { return dropped.get(); }

    /** Pending requests describe only transient chunk demand; shutdown abandons them. */
    @Override public void close() {
        closed = true;
        for (Pending request; (request = pending.poll()) != null; ) {
            dropped.incrementAndGet();
            trace("DROPPED_SHUTDOWN", request, null);
        }
    }

    /** Discard demand tied to a closing world. */
    public void worldClosing(ServerLevel level) {
        pending.removeIf(request -> {
            if (request.level() != level) return false;
            dropped.incrementAndGet();
            trace("DROPPED_WORLD_CLOSED", request, null);
            return true;
        });
    }

    private static void traceInline(Entity entity, RoutingKey key, BlockPos position, RegionId caller, RegionId owner) {
        if (!Boolean.getBoolean("phoenium.test.diagnostics")) return;
        try {
            LoggerFactory.getLogger(PortalTicketBoundary.class).info(
                "PHOENIUM_PORTAL_TICKET phase=INLINE uuid={} world={} chunk={},{} position={} callerOwner={} chunkOwner={}",
                entity.getUUID(), key.world(), key.x(), key.z(), position.toShortString(), caller,
                owner == null ? "UNROUTED" : owner);
        } catch (Throwable ignored) { /* Diagnostics never replace the native outcome. */ }
    }

    private static void trace(String phase, Pending request, RegionId destinationOwner) {
        if (!Boolean.getBoolean("phoenium.test.diagnostics")) return;
        try {
            LoggerFactory.getLogger(PortalTicketBoundary.class).info(
                "PHOENIUM_PORTAL_TICKET phase={} sequence={} uuid={} world={} chunk={},{} position={} requestedUnder={} destinationOwner={} currentOwner={}",
                phase, request.sequence(), request.entity().getUUID(), request.key().world(),
                request.key().x(), request.key().z(), request.position().toShortString(), request.requestedUnder(),
                destinationOwner == null ? "UNAVAILABLE" : destinationOwner,
                OwnershipContext.current().map(context -> context.region().toString()).orElse("NONE"));
        } catch (Throwable ignored) { /* Diagnostics never replace the native outcome. */ }
    }

    private record Pending(long sequence, Entity entity, ServerLevel level, RoutingKey key,
                           BlockPos position, RegionId requestedUnder) { }
}
