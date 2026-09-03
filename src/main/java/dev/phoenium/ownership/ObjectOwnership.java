package dev.phoenium.ownership;

import dev.phoenium.core.OwnerEpoch;
import dev.phoenium.core.TopologyGeneration;
import dev.phoenium.region.RegionId;
import dev.phoenium.topology.RoutingKey;
import java.util.Objects;

/** Immutable, non-authoritative index entry. The RegionDirectory remains the location-owner authority. */
public record ObjectOwnership(String objectId, OwnedObjectKind kind, RoutingKey routingKey,
                              long incarnation, RegionId owner,
                              TopologyGeneration topologyGeneration, OwnerEpoch regionOwnerEpoch,
                              OwnerEpoch objectEpoch, State state) {
    public ObjectOwnership {
        Objects.requireNonNull(objectId, "objectId"); Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(routingKey, "routingKey"); Objects.requireNonNull(topologyGeneration, "topologyGeneration");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(regionOwnerEpoch, "regionOwnerEpoch"); Objects.requireNonNull(objectEpoch, "objectEpoch");
        Objects.requireNonNull(state, "state");
        if (incarnation < 0) throw new IllegalArgumentException("negative object incarnation");
    }
    public enum State { OWNED, SOURCE_PREPARED, DESTINATION_RESERVED, REMOVED }
}
