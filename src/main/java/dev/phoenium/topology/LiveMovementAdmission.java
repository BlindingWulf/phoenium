package dev.phoenium.topology;

import dev.phoenium.core.TopologyGeneration;
import dev.phoenium.region.RegionId;
import java.util.Objects;

/**
 * Generation-fenced admission for a classified live movement footprint. The ticket is not an
 * owner transfer and it cannot survive a topology publication: replay must validate it again
 * immediately before invoking vanilla code under the already-selected region lease.
 */
public final class LiveMovementAdmission {
    private LiveMovementAdmission() { }

    public static Ticket admit(RegionDirectory directory, RegionId owner, LiveMovementFootprint footprint) {
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(footprint, "footprint");
        FrozenRegionAuthority authority = FrozenRegionAuthority.freeze(directory, owner);
        footprint.requireWithin(authority);
        return new Ticket(owner, authority.generation(), footprint);
    }

    /** S9-D7: a value class with the former record's components, equality and string form, plus the replay
     * gate's exactly-once marker for this admission instance. The gate formerly recorded every consumed ticket
     * (and its whole footprint) in an identity set that was never cleared: one retained footprint per move. */
    public static final class Ticket {
        private final RegionId owner;
        private final TopologyGeneration generation;
        private final LiveMovementFootprint footprint;
        private final java.util.concurrent.atomic.AtomicBoolean consumed = new java.util.concurrent.atomic.AtomicBoolean();
        public Ticket(RegionId owner, TopologyGeneration generation, LiveMovementFootprint footprint) {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(generation, "generation");
            Objects.requireNonNull(footprint, "footprint");
            if (!owner.world().equals(footprint.world())) throw new IllegalArgumentException("ticket footprint crosses owner world");
            this.owner = owner; this.generation = generation; this.footprint = footprint;
        }
        public RegionId owner() { return owner; }
        public TopologyGeneration generation() { return generation; }
        public LiveMovementFootprint footprint() { return footprint; }
        /** True exactly once per ticket instance. */
        boolean consume() { return consumed.compareAndSet(false, true); }
        @Override public boolean equals(Object other) {
            return this == other || other instanceof Ticket ticket && owner.equals(ticket.owner)
                && generation.equals(ticket.generation) && footprint.equals(ticket.footprint);
        }
        @Override public int hashCode() { return Objects.hash(owner, generation, footprint); }
        @Override public String toString() {
            return "Ticket[owner=" + owner + ", generation=" + generation + ", footprint=" + footprint + "]";
        }

        /** Re-check topology generation and the complete footprint immediately before replay. */
        public void requireCurrent(RegionDirectory directory, RegionId executingOwner) {
            if (!owner.equals(executingOwner)) throw new IllegalStateException("movement admission executing at foreign owner " + executingOwner);
            FrozenRegionAuthority authority = FrozenRegionAuthority.freeze(directory, owner);
            if (!generation.equals(authority.generation())) {
                throw new IllegalStateException("stale live movement admission generation " + generation.value()
                    + "; current is " + authority.generation().value());
            }
            footprint.requireWithin(authority);
        }
    }
}
