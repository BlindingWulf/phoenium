package dev.phoenium.chunk;

import java.util.Objects;

/** Explicit provider-facing termination for a C2ME generation branch that Phoenium did not apply. */
public final class C2meGenerationControlException extends RuntimeException {
    private final ChunkStageAdmission.Result reason;

    C2meGenerationControlException(ChunkStageAdmission.Result reason) {
        super("C2ME generation branch ended with " + Objects.requireNonNull(reason, "reason"));
        this.reason = reason;
    }

    public ChunkStageAdmission.Result reason() { return reason; }
}
