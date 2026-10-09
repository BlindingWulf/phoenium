package dev.phoenium.topology;

import dev.phoenium.region.RegionId;
import java.util.Objects;

/**
 * Narrow replay-marker model for the future packet adapter. A marker is created only after the
 * movement ticket has passed execution-time revalidation and can be consumed exactly once; it is
 * never a global or recursive exemption from the footprint or owner checks.
 */
public final class LivePacketReplayGate {
    private final ThreadLocal<LiveMovementAdmission.Ticket> active = new ThreadLocal<>();
    // Ticket values may legitimately be equal for two different packets with the same movement
    // footprint.  The replay guard is about one admission instance, not value-level geometry, so the
    // instance itself carries its consumption (S9-D7; this gate is the only consumer).

    public Scope enter(LiveMovementAdmission.Ticket ticket, RegionDirectory directory, RegionId owner) {
        Objects.requireNonNull(ticket, "ticket");
        ticket.requireCurrent(directory, owner);
        if (active.get() != null) throw new IllegalStateException("nested packet replay marker");
        if (!ticket.consume()) throw new IllegalStateException("packet replay ticket already consumed");
        active.set(ticket);
        return new Scope(ticket);
    }

    public boolean marked() { return active.get() != null; }

    public final class Scope implements AutoCloseable {
        private final LiveMovementAdmission.Ticket ticket;
        private boolean closed;
        private Scope(LiveMovementAdmission.Ticket ticket) { this.ticket = ticket; }
        @Override public void close() {
            if (closed || active.get() != ticket) throw new IllegalStateException("packet replay marker escaped");
            active.set(null);
            closed = true;
        }
    }
}
