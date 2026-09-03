package dev.phoenium.save;

import dev.phoenium.core.TopologyGeneration;
import dev.phoenium.region.RegionId;
import java.util.List;
import java.util.Map;

public record SaveSnapshot(TopologyGeneration topologyGeneration, Map<RegionId, List<String>> ownerData, Map<String, RegionId> objectOwners) {
    public SaveSnapshot { ownerData = ownerData.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, entry -> List.copyOf(entry.getValue()))); objectOwners = Map.copyOf(objectOwners); }
}
