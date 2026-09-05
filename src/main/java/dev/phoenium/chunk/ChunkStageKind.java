package dev.phoenium.chunk;

/** Semantic mutation stages. Minecraft adapter names deliberately do not appear here. */
public enum ChunkStageKind {
    POI_INSTALL,
    PARSED_APPLY,
    LOAD_RECOVERY,
    MAP_METADATA,
    LIGHT_INPUT,
    LIGHT_POST_UPDATE,
    FULL_CONVERT,
    FULL_PREPARE,
    FULL_FINISH,
    STATUS_PUBLISH,
    TICK_READY,
    SEND_READY,
    DEMOTE
}
