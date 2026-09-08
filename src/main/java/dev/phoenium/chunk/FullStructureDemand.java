package dev.phoenium.chunk;

import dev.phoenium.ownership.OwnershipContext;
import java.util.*;
import net.minecraft.server.level.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.TicketStorage;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Finite, nonpersistent prerequisite loading for referenced structure starts. Each operation
 * owns a distinct ticket-type identity, so acknowledging it cannot remove another consumer's
 * ticket. The original query's UNKNOWN ticket remains at its original reached call site. */
public final class FullStructureDemand implements AutoCloseable {
    public interface CacheAccess {
        TicketStorage phoenium$structureDemandTickets();
        boolean phoenium$updateStructureDemand();
    }
    private final FullRegistrationRuntime runtime;
    // This in-memory type is deliberately nonpersistent and non-simulating. TicketStorage
    // compares ticket types by identity and serializes only types whose persist() is true.
    private final TicketType type = new TicketType(TicketType.NO_TIMEOUT, TicketType.FLAG_LOADING);
    private final Map<ChunkPos, Ticket> tickets = new LinkedHashMap<>();
    private boolean closed;
    FullStructureDemand(FullRegistrationRuntime runtime) { this.runtime = runtime; }

    void require(ChunkPos position) {
        runtime.requireObservationThread();
        if (OwnershipContext.current().isPresent() || FullFinishingServices.finishing() || closed)
            throw new ChunkProtocolException("structure-start demand requires an active owner-free input operation");
        if (tickets.containsKey(position)) return;
        var cache = runtime.level().getChunkSource();
        var ticket = new Ticket(type, ChunkLevel.byStatus(ChunkStatus.STRUCTURE_STARTS));
        // Record ownership before the original insertion can notify a distance listener.
        tickets.put(position, ticket);
        try (var serial = FullFinishingServices.forServer(runtime.server()).serial(FullFinishingServices.Phase.SERVER_TASK)) {
            cache.addTicket(ticket, position);
            if (closed) return; // A reentrant stop already acknowledged this exact insertion.
            ((CacheAccess) cache).phoenium$updateStructureDemand();
        } catch (Throwable failure) {
            tickets.remove(position, ticket);
            try { remove(position, ticket); } catch (Throwable cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
            throw failure;
        } finally { runtime.inputs.changed(); }
    }
    void retainOnly(Set<ChunkPos> required) {
        runtime.requireObservationThread();
        var removals = new LinkedHashMap<ChunkPos, Ticket>();
        tickets.forEach((position, ticket) -> { if (!required.contains(position)) removals.put(position, ticket); });
        removals.keySet().forEach(tickets::remove);
        var failures = new FullInputNotifications.Failures();
        removals.forEach((position, ticket) -> failures.run(() -> remove(position, ticket)));
        failures.rethrow();
    }
    private void remove(ChunkPos position, Ticket ticket) {
        if (OwnershipContext.current().isPresent() || FullFinishingServices.finishing())
            throw new ChunkProtocolException("structure input ticket acknowledged before owner exit");
        try (var serial = FullFinishingServices.forServer(runtime.server()).serial(FullFinishingServices.Phase.STOP)) {
            ((CacheAccess) runtime.level().getChunkSource()).phoenium$structureDemandTickets().removeTicket(ticket, position);
        }
    }
    int size() { return tickets.size(); }
    @Override public void close() {
        if (closed) return;
        closed = true; retainOnly(Set.of());
    }
}
