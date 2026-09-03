package dev.phoenium.topology;

import dev.phoenium.core.OwnerEpoch;
import dev.phoenium.core.TopologyGeneration;
import dev.phoenium.region.RegionId;

public record RegionRoute(RegionId region, TopologyGeneration generation, OwnerEpoch ownerEpoch) {}
