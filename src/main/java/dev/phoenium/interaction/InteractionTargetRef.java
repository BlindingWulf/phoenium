package dev.phoenium.interaction;

import dev.phoenium.core.OwnerEpoch;
import dev.phoenium.core.TopologyGeneration;
import dev.phoenium.core.WorldId;
import dev.phoenium.region.RegionId;
import dev.phoenium.topology.RoutingKey;
import java.util.Objects;

/** Immutable derived target identity.  It is evidence for admission, never ownership authority. */
public record InteractionTargetRef(WorldId world, int networkEntityId, String objectId, long incarnation,
                                   RoutingKey routingKey, RegionId expectedOwner,
                                   TopologyGeneration topologyGeneration, OwnerEpoch objectEpoch) {
    public InteractionTargetRef {
        Objects.requireNonNull(world, "world"); Objects.requireNonNull(objectId, "objectId");
        Objects.requireNonNull(routingKey, "routingKey"); Objects.requireNonNull(expectedOwner, "expectedOwner");
        Objects.requireNonNull(topologyGeneration, "topologyGeneration"); Objects.requireNonNull(objectEpoch, "objectEpoch");
        if (incarnation < 0) throw new IllegalArgumentException("negative incarnation");
        if (!world.equals(routingKey.world()) || !world.equals(expectedOwner.world())) throw new IllegalArgumentException("cross-world target ref");
    }
}
