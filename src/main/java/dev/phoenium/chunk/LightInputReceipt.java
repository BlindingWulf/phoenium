package dev.phoenium.chunk;

import java.util.Objects;

/** Proof of accepted engine ingress, deliberately not proof that light was computed. */
public record LightInputReceipt(LightInputService.BatchId batch) {
    public LightInputReceipt { Objects.requireNonNull(batch, "batch"); }
}
