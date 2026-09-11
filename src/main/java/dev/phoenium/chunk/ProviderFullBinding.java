package dev.phoenium.chunk;

/** Narrow readiness/service hook for provider-native FULL work that does not use vanilla FULL reservations. */
interface ProviderFullBinding {
    LightInputService.Readiness readinessLocked(ChunkStageKind kind);
    void prepare(ChunkStageKind kind);
    boolean reserveLocked(ChunkStageKind kind);
    void unreserveLocked(ChunkStageKind kind);
    void stageExited(ChunkStageKind kind);
}
