package dev.phoenium.topology;

import dev.phoenium.core.OwnerEpoch;
import dev.phoenium.core.TopologyGeneration;
import dev.phoenium.ownership.ObjectOwnership;
import dev.phoenium.ownership.ObjectOwnershipRegistry;
import dev.phoenium.region.RegionId;
import java.util.Objects;

/**
 * Generation-fenced identity ticket for a target-free player packet. The native handler remains
 * responsible for deriving item targets; this ticket grants only the current player's owner turn.
 */
public final class LivePlayerPacketAdmission {
    private LivePlayerPacketAdmission() { }

    public static Ticket admit(RegionDirectory directory, ObjectOwnership player) {
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(player, "player");
        TopologyGeneration generation = directory.snapshot().generation();
        if (!generation.equals(player.topologyGeneration()))
            throw new IllegalStateException("player packet binding has stale topology generation");
        return new Ticket(player.objectId(), player.incarnation(), player.objectEpoch(), player.owner(), generation);
    }

    public record Ticket(String playerId, long playerIncarnation, OwnerEpoch playerEpoch,
                         RegionId owner, TopologyGeneration generation) {
        public Ticket {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(playerEpoch, "playerEpoch");
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(generation, "generation");
        }

        public void requireCurrent(RegionDirectory directory, ObjectOwnershipRegistry objects,
                                   RegionId executingOwner) {
            if (!owner.equals(executingOwner))
                throw new IllegalStateException("player packet executing at foreign owner " + executingOwner);
            ObjectOwnership player = objects.current(playerId)
                .orElseThrow(() -> new IllegalStateException("stale player packet player"));
            if (player.incarnation() != playerIncarnation
                    || !player.objectEpoch().equals(playerEpoch)
                    || !player.owner().equals(owner))
                throw new IllegalStateException("player packet ownership changed");
            TopologyGeneration current = directory.snapshot().generation();
            if (!generation.equals(current))
                throw new IllegalStateException("stale player packet topology generation " + generation.value()
                    + "; current is " + current.value());
        }
    }
}
