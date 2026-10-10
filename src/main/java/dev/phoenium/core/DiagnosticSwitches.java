package dev.phoenium.core;

/** S9-D18: diagnostics that cost time on hot paths and run only when asked for. Read once, at class load; this class
 * loads no Minecraft class, so the mixin config plugin may read it before any target is transformed. */
public final class DiagnosticSwitches {
    /** The block-entity chunk-query diagnostic: a scope around each block-entity tick and a Level.getBlockState
     * wrapper that remember the tick's last read, so the first chunk query that fails inside a block-entity tick is
     * reported with its context (PHOENIUM_BLOCK_ENTITY_CHUNK_QUERY_FIRST_FAILURE). It observes only: the failure is
     * rethrown unchanged either way. On with phoenium.test.diagnostics (the Step B and investigation runs) or
     * phoenium.diagnostics.blockEntityChunkQueries. */
    public static final boolean BLOCK_ENTITY_CHUNK_QUERIES = Boolean.getBoolean("phoenium.test.diagnostics")
        || Boolean.getBoolean("phoenium.diagnostics.blockEntityChunkQueries");

    private DiagnosticSwitches() { }
}
