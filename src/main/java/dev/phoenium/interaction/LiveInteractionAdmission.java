package dev.phoenium.interaction;

import dev.phoenium.core.TopologyGeneration;
import dev.phoenium.ownership.ObjectOwnership;
import dev.phoenium.ownership.ObjectOwnershipRegistry;
import dev.phoenium.region.RegionId;
import dev.phoenium.topology.FrozenRegionAuthority;
import dev.phoenium.topology.RegionDirectory;
import dev.phoenium.topology.RoutingKey;
import java.util.Objects;
import java.util.Set;

/** Same-owner-only interaction admission; cross-owner interaction is intentionally deferred. */
public final class LiveInteractionAdmission {
    private LiveInteractionAdmission() { }
    public static Ticket admit(RegionDirectory directory, ObjectOwnership player, InteractionTargetRef target,
                               InteractionTargetIndex index) {
        Objects.requireNonNull(directory, "directory"); Objects.requireNonNull(player, "player");
        Objects.requireNonNull(target, "target"); Objects.requireNonNull(index, "index");
        if (!player.owner().equals(target.expectedOwner())) throw new CrossRegionInteractionException();
        FrozenRegionAuthority authority = FrozenRegionAuthority.freeze(directory, player.owner());
        authority.requireWithin(keys(player.routingKey(), target.routingKey()));
        return new Ticket(player.objectId(), player.incarnation(), player.objectEpoch(), target,
            player.owner(), authority.generation());
    }
    public record Ticket(String playerId, long playerIncarnation, dev.phoenium.core.OwnerEpoch playerEpoch,
                         InteractionTargetRef target, RegionId owner, TopologyGeneration generation) {
        public void requireCurrent(RegionDirectory directory, ObjectOwnershipRegistry objects, InteractionTargetIndex index,
                                   RegionId executingOwner) {
            if (!owner.equals(executingOwner) || !index.isCurrent(target)) throw new IllegalStateException("stale interaction target reference");
            ObjectOwnership player = objects.current(playerId).orElseThrow(() -> new IllegalStateException("stale interaction player"));
            ObjectOwnership currentTarget = objects.current(target.objectId()).orElseThrow(() -> new IllegalStateException("stale interaction target"));
            if (player.incarnation() != playerIncarnation || !player.objectEpoch().equals(playerEpoch)
                || currentTarget.incarnation() != target.incarnation() || !currentTarget.objectEpoch().equals(target.objectEpoch())
                || !player.owner().equals(owner) || !currentTarget.owner().equals(owner)) throw new IllegalStateException("interaction ownership changed");
            FrozenRegionAuthority authority = FrozenRegionAuthority.freeze(directory, owner);
            if (!generation.equals(authority.generation())) throw new IllegalStateException("stale interaction topology generation");
            authority.requireWithin(keys(player.routingKey(), currentTarget.routingKey()));
        }
    }
    private static Set<RoutingKey> keys(RoutingKey player, RoutingKey target) {
        return player.equals(target) ? Set.of(player) : Set.of(player, target);
    }
    public static final class CrossRegionInteractionException extends IllegalStateException {
        public CrossRegionInteractionException() { super("cross-region interaction deferred before mutation"); }
    }
}
