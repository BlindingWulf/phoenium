package dev.phoenium.topology;

import dev.phoenium.core.OwnerEpoch;
import dev.phoenium.core.TopologyGeneration;
import dev.phoenium.ownership.ObjectOwnership;
import dev.phoenium.ownership.ObjectOwnershipRegistry;
import dev.phoenium.region.RegionId;
import java.util.Objects;
import java.util.Set;

/**
 * Generation-fenced admission for a native player block packet.  The packet remains owned by
 * the player, while the immutable footprint proves that the block target and its immediate
 * native callback halo are in the same published regional authority.
 */
public final class LiveBlockPacketAdmission {
    private LiveBlockPacketAdmission() { }

    public static Ticket admit(RegionDirectory directory, ObjectOwnership player, Set<RoutingKey> footprint) {
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(footprint, "footprint");
        if (footprint.isEmpty()) throw new IllegalArgumentException("block packet footprint is empty");
        FrozenRegionAuthority authority = FrozenRegionAuthority.freeze(directory, player.owner());
        authority.requireWithin(footprint);
        return new Ticket(player.objectId(), player.incarnation(), player.objectEpoch(), player.owner(),
            authority.generation(), footprint);
    }

    public record Ticket(String playerId, long playerIncarnation, OwnerEpoch playerEpoch,
                         RegionId owner, TopologyGeneration generation, Set<RoutingKey> footprint) {
        public Ticket {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(playerEpoch, "playerEpoch");
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(generation, "generation");
            footprint = Set.copyOf(Objects.requireNonNull(footprint, "footprint"));
            if (footprint.isEmpty()) throw new IllegalArgumentException("block packet footprint is empty");
        }

        /** Re-checks the exact player identity and complete block callback footprint before replay. */
        public void requireCurrent(RegionDirectory directory, ObjectOwnershipRegistry objects,
                                   RegionId executingOwner) {
            if (!owner.equals(executingOwner))
                throw new IllegalStateException("block packet executing at foreign owner " + executingOwner);
            ObjectOwnership player = objects.current(playerId)
                .orElseThrow(() -> new IllegalStateException("stale block packet player"));
            if (player.incarnation() != playerIncarnation
                    || !player.objectEpoch().equals(playerEpoch)
                    || !player.owner().equals(owner))
                throw new IllegalStateException("block packet player ownership changed");
            FrozenRegionAuthority authority = FrozenRegionAuthority.freeze(directory, owner);
            if (!generation.equals(authority.generation()))
                throw new IllegalStateException("stale block packet topology generation " + generation.value()
                    + "; current is " + authority.generation().value());
            authority.requireWithin(footprint);
        }
    }
}
