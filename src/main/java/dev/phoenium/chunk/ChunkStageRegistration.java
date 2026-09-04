package dev.phoenium.chunk;

import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/**
 * Typed registration wrapper for future adapters.  It intentionally exposes a Function only for
 * a named vanilla stage; no generic task submission API exists.  Control remains in the private
 * {@link ChunkStageOutcome} channel and therefore cannot masquerade as parse/corruption failure.
 */
public final class ChunkStageRegistration<I, O> {
    private final PendingVanillaStage<I, O> pending;
    private final ChunkStageDeliveryPump pump;
    public ChunkStageRegistration(PendingVanillaStage<I, O> pending, ChunkStageDeliveryPump pump) {
        this.pending = Objects.requireNonNull(pending, "pending"); this.pump = Objects.requireNonNull(pump, "pump");
    }
    public Function<I, CompletionStage<ChunkStageOutcome<O>>> callback() {
        return input -> { if (pending.bindInput(input)) pump.publishAndDeliver(pending); return pending.outcome(); };
    }
}
