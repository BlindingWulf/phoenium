package dev.phoenium.chunk;

import dev.phoenium.ownership.OwnershipContext;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Rejects an unfinished synchronous dependency; context presence grants no new authority. */
public final class ChunkSynchronousAccessGuard {
    /** Synchronous fault routing only: never used to discover work or grant query authority. */
    private static final ThreadLocal<Invocation> INVOCATION = new ThreadLocal<>();
    private ChunkSynchronousAccessGuard() { }
    public static void beforeWait(BooleanSupplier ready) {
        if (OwnershipContext.current().isPresent() && !ready.getAsBoolean()) {
            var failure = new ChunkProtocolException("unfinished synchronous chunk dependency inside an owner transaction");
            Invocation invocation = INVOCATION.get();
            if (invocation != null) invocation.record(failure);
            throw failure;
        }
    }

    /** Admission installs this only after its exact claim/lease, and closes it before completion. */
    static Invocation forClaim(ChunkStageClaim claim) {
        Objects.requireNonNull(claim).require(claim.stage());
        if (INVOCATION.get() != null) throw new ChunkProtocolException("overlapping synchronous chunk query fault scope");
        Invocation invocation = new Invocation(claim);
        INVOCATION.set(invocation);
        return invocation;
    }

    static final class Invocation implements AutoCloseable {
        private ChunkStageClaim claim;
        private ChunkProtocolException failure;
        private Invocation(ChunkStageClaim claim) { this.claim = claim; }
        private void record(ChunkProtocolException observed) {
            claim.require(claim.stage());
            if (failure == null) failure = observed;
        }
        /** Vanilla may catch/wrap the thrown marker while retaining its committed mutation prefix. */
        void requireNoBreach(Throwable laterFailure) {
            if (failure == null) return;
            if (laterFailure != null && laterFailure != failure) failure.addSuppressed(laterFailure);
            throw failure;
        }
        @Override public void close() {
            INVOCATION.remove();
            claim = null;
            failure = null;
        }
    }
}
