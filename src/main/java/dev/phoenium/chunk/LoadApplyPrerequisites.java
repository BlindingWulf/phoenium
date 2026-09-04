package dev.phoenium.chunk;

import java.util.Objects;
import java.util.Optional;

/** Non-blocking future PARSED_APPLY eligibility query; it does not claim or execute a stage. */
public final class LoadApplyPrerequisites {
    public enum State { READY, NOT_READY, STALE, FAILED, SHUTDOWN }
    public record Required(Optional<PoiInstallService.InstallId> poi, Optional<LightInputService.BatchId> light, boolean metadata) {
        public Required { Objects.requireNonNull(poi, "poi"); Objects.requireNonNull(light, "light"); }
        public static Required fresh() { return new Required(Optional.empty(), Optional.empty(), true); }
        public static Required reload(PoiInstallService.InstallId poi, LightInputService.BatchId light) { return new Required(Optional.of(poi), Optional.of(light), true); }
    }
    private final ChunkLifecycleRegistry lifecycles; private final ChunkWorkRegistry works;
    private final PoiInstallService poi; private final LightInputService light; private final ChunkTypeMetadataService metadata;
    private boolean open = true;
    public LoadApplyPrerequisites(ChunkLifecycleRegistry lifecycles, ChunkWorkRegistry works, PoiInstallService poi, LightInputService light, ChunkTypeMetadataService metadata) {
        this.lifecycles = Objects.requireNonNull(lifecycles); this.works = Objects.requireNonNull(works); this.poi = Objects.requireNonNull(poi); this.light = Objects.requireNonNull(light); this.metadata = Objects.requireNonNull(metadata);
    }
    public State evaluate(ChunkCustodyModel.LifecycleId lifecycle, ChunkCustodyModel.WorkId work, Required required) {
        Objects.requireNonNull(lifecycle); Objects.requireNonNull(work); Objects.requireNonNull(required);
        if (!open) return State.SHUTDOWN;
        if (!work.lifecycle().equals(lifecycle) || !lifecycles.isCurrent(lifecycle) || !works.isCurrentWork(work)) return State.STALE;
        if (required.poi().isPresent() && (!required.poi().get().lifecycle().equals(lifecycle) || !required.poi().get().work().equals(work))) return State.STALE;
        if (required.poi().isPresent() && !poi.receipt(required.poi().get()).isPresent()) return State.NOT_READY;
        if (required.light().isPresent() && (!required.light().get().lifecycle().equals(lifecycle) || !required.light().get().work().equals(work))) return State.STALE;
        if (required.light().isPresent() && !light.receipt(required.light().get()).isPresent()) return State.NOT_READY;
        return required.metadata() && !metadata.preconditionValid(lifecycle) ? State.STALE : State.READY;
    }
    public void shutdown() { open = false; }
}
