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

    public record Ticket(RegionId owner, TopologyGeneration generation, LiveMovementFootprint footprint) {
        public Ticket {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(generation, "generation");
            Objects.requireNonNull(footprint, "footprint");
            if (!owner.world().equals(footprint.world())) throw new IllegalArgumentException("ticket footprint crosses owner world");
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
