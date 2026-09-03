package dev.phoenium.topology;

import dev.phoenium.region.RegionId;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Narrow replay-marker model for the future packet adapter. A marker is created only after the
 * movement ticket has passed execution-time revalidation and can be consumed exactly once; it is
 * never a global or recursive exemption from the footprint or owner checks.
 */
public final class LivePacketReplayGate {
    private final ThreadLocal<LiveMovementAdmission.Ticket> active = new ThreadLocal<>();
    private final Set<LiveMovementAdmission.Ticket> consumed = new HashSet<>();

    public Scope enter(LiveMovementAdmission.Ticket ticket, RegionDirectory directory, RegionId owner) {
        Objects.requireNonNull(ticket, "ticket");
        ticket.requireCurrent(directory, owner);
        if (active.get() != null) throw new IllegalStateException("nested packet replay marker");
        if (!consumed.add(ticket)) throw new IllegalStateException("packet replay ticket already consumed");
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
            active.remove();
            closed = true;
        }
    }
}
