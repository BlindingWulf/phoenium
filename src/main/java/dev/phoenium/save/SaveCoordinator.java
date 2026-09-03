package dev.phoenium.save;

import dev.phoenium.region.RegionRuntime;
import dev.phoenium.scheduler.RegionScheduler;
import dev.phoenium.topology.RegionDirectory;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Captures immutable, owner-produced test data at one published directory generation. */
public final class SaveCoordinator {
    public SaveSnapshot cut(RegionDirectory directory, RegionScheduler scheduler, List<RegionRuntime> runtimes, Map<String, dev.phoenium.region.RegionId> owners) {
        Map<dev.phoenium.region.RegionId, List<String>> copied = new LinkedHashMap<>();
        runtimes.stream().sorted(java.util.Comparator.comparing(RegionRuntime::id)).forEach(runtime -> scheduler.snapshot(runtime, () -> copied.put(runtime.id(), runtime.immutableLog())));
        if (owners.size() != new java.util.HashSet<>(owners.keySet()).size()) throw new IllegalStateException("duplicate logical save identity");
        return new SaveSnapshot(directory.snapshot().generation(), copied, owners);
    }
}
