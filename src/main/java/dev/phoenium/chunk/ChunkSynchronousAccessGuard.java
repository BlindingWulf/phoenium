package dev.phoenium.chunk;

import dev.phoenium.ownership.OwnershipContext;
import java.util.function.BooleanSupplier;

/** Rejects an unfinished synchronous dependency; context presence grants no new authority. */
public final class ChunkSynchronousAccessGuard {
    private ChunkSynchronousAccessGuard() { }
    public static void beforeWait(BooleanSupplier ready) {
        if (OwnershipContext.current().isPresent() && !ready.getAsBoolean())
            throw new ChunkProtocolException("unfinished synchronous chunk dependency inside an owner transaction");
    }
}
