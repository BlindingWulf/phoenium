package dev.phoenium.service;

/** Closed Stage 4 regional-service vocabulary; additions require scheduler-boundary review. */
public enum RegionalServiceKind {
    COLLECT_SCHEDULED_BLOCK_TICK,
    RUN_SCHEDULED_BLOCK_TICK,
    COLLECT_SCHEDULED_FLUID_TICK,
    RUN_SCHEDULED_FLUID_TICK,
    BLOCK_EVENT,
    SPAWN_STATE_CAPTURE,
    CHUNK_GAMEPLAY,
    BLOCK_ENTITY_TICK,
    CHUNK_UNLOAD,
    ENTITY_STORAGE_SAVE,
    TRACKING_UPDATE,
    TRACKING_PAIR,
    TRACKING_REMOVE,
    SAVE_CAPTURE,
    SAVE_ACKNOWLEDGEMENT
}
