package dev.phoenium.chunk;

/** Private result channel; control outcomes are never impersonated as vanilla I/O/corruption failures. */
public sealed interface ChunkStageOutcome<O> permits ChunkStageOutcome.Applied, ChunkStageOutcome.Control, ChunkStageOutcome.VanillaFailure, ChunkStageOutcome.BarrierFailure {
    record Applied<O>(O value) implements ChunkStageOutcome<O> { }
    record Control<O>(ChunkStageAdmission.Result result) implements ChunkStageOutcome<O> { }
    record VanillaFailure<O>(Throwable failure) implements ChunkStageOutcome<O> { }
    record BarrierFailure<O>(Throwable failure) implements ChunkStageOutcome<O> { }
}
