package dev.phoenium.chunk;

/**
 * The closed, typed form of one retained vanilla synchronous chunk continuation.
 *
 * <p>This is intentionally not a general scheduler callback.  Implementations are retained only
 * by {@link PendingVanillaStage} and are invoked only by {@link ChunkStageExecutor} after the
 * custody claim and owner lease have succeeded.</p>
 */
@FunctionalInterface
public interface VanillaChunkStage<I, O> {
    O apply(I input) throws Throwable;

    /** The exact claim is passed lexically to adapters with synchronous service sub-operations. */
    @FunctionalInterface
    interface Admitted<I, O> {
        O apply(I input, ChunkStageClaim claim) throws Throwable;
    }
}
