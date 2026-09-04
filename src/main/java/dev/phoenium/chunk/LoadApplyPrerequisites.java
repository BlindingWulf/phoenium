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
    private volatile boolean open = true;
    public LoadApplyPrerequisites(ChunkLifecycleRegistry lifecycles, ChunkWorkRegistry works, PoiInstallService poi, LightInputService light, ChunkTypeMetadataService metadata) {
        this.lifecycles = Objects.requireNonNull(lifecycles); this.works = Objects.requireNonNull(works); this.poi = Objects.requireNonNull(poi); this.light = Objects.requireNonNull(light); this.metadata = Objects.requireNonNull(metadata);
    }
    public State evaluate(ChunkCustodyModel.LifecycleId lifecycle, ChunkCustodyModel.WorkId work, Required required) {
        return evaluate(lifecycle, work, required.light().map(LightInputService.BatchId::stage).orElse(null), required);
    }
    public State evaluate(ChunkCustodyModel.LifecycleId lifecycle, ChunkCustodyModel.WorkId work,
                          ChunkCustodyModel.StageId stage, Required required) {
        Objects.requireNonNull(lifecycle); Objects.requireNonNull(work); Objects.requireNonNull(required);
        if (!open) return State.SHUTDOWN;
        if (!work.lifecycle().equals(lifecycle) || !lifecycles.isCurrent(lifecycle) || !works.isCurrentWork(work)) return State.STALE;
        if (required.poi().isPresent() && (!required.poi().get().lifecycle().equals(lifecycle) || !required.poi().get().work().equals(work))) return State.STALE;
        if (required.poi().isPresent() && !poi.receipt(required.poi().get()).isPresent()) return State.NOT_READY;
        if (required.light().isPresent() && (!required.light().get().lifecycle().equals(lifecycle) || !required.light().get().work().equals(work))) return State.STALE;
        if (required.light().isPresent()) {
            State registration = switch (light.registeredForParsedApply(required.light().get(), stage)) {
                case REGISTERED -> State.READY;
                case NOT_REGISTERED -> State.NOT_READY;
                case STALE -> State.STALE;
                case FAILED -> State.FAILED;
                case SHUTDOWN -> State.SHUTDOWN;
            };
            if (registration != State.READY) return registration;
        }
        return required.metadata() && !metadata.preconditionValid(work) ? State.STALE : State.READY;
    }
    public void shutdown() { open = false; }
}
