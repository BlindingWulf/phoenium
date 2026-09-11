package dev.phoenium.chunk;

import java.util.Objects;

/** Provider-facing control termination for C2ME FULL/sending work that Phoenium did not apply. */
public final class C2meProviderControlException extends RuntimeException {
    private final ChunkStageAdmission.Result reason;

    C2meProviderControlException(ChunkStageAdmission.Result reason) {
        super("C2ME provider stage ended with " + Objects.requireNonNull(reason, "reason"));
        this.reason = reason;
    }

    public ChunkStageAdmission.Result reason() { return reason; }
}
