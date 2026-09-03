package dev.phoenium.failure;

import dev.phoenium.core.OwnerEpoch;
import dev.phoenium.core.TopologyGeneration;
import dev.phoenium.ownership.OwnershipContext;
import dev.phoenium.region.RegionId;
import dev.phoenium.task.RegionTaskEnvelope;
import java.util.Optional;

/** A one-way barrier: no exception may escape into a vanilla fallback path. */
public final class FailureBarrier {
    private FailureRecord record;
    public boolean tripped() { return record != null; }
    public Optional<FailureRecord> record() { return Optional.ofNullable(record); }
    public void trip(Throwable cause, TopologyGeneration generation, OwnerEpoch ownerEpoch, RegionTaskEnvelope envelope) {
        if (record == null) {
            RegionId region = OwnershipContext.current().map(OwnershipContext.Active::region).orElse(null);
            record = new FailureRecord(cause, region, generation, ownerEpoch, envelope);
        }
    }
    public record FailureRecord(Throwable cause, RegionId region, TopologyGeneration generation, OwnerEpoch ownerEpoch, RegionTaskEnvelope envelope) { }
}
