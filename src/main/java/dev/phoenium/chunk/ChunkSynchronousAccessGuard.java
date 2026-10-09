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
            throw breach(new ChunkProtocolException("unfinished synchronous chunk dependency inside an owner transaction"));
        }
    }

    /** Typed private-query faults use the same caught-failure barrier as unfinished waits. */
    public static ChunkProtocolException breach(ChunkProtocolException failure) {
        Invocation invocation = INVOCATION.get();
        if (invocation != null) invocation.record(Objects.requireNonNull(failure));
        return failure;
    }

    /** Admission installs this only after its exact claim/lease, and closes it before completion. */
    static Invocation forClaim(ChunkStageClaim claim) {
        Objects.requireNonNull(claim).require(claim.stage());
        if (INVOCATION.get() != null) throw new ChunkProtocolException("overlapping synchronous chunk query fault scope");
        Invocation invocation = new Invocation(() -> claim.require(claim.stage()));
        INVOCATION.set(invocation);
        return invocation;
    }
    static Invocation forContextRead(FullContextReadClaim claim) {
        Objects.requireNonNull(claim).requireActive();
        if (INVOCATION.get() != null) throw new ChunkProtocolException("overlapping synchronous context query fault scope");
        Invocation invocation = new Invocation(claim::requireActive);
        INVOCATION.set(invocation);
        return invocation;
    }

    static final class Invocation implements AutoCloseable {
        private Runnable requireAuthority;
        private ChunkProtocolException failure;
        private Invocation(Runnable requireAuthority) { this.requireAuthority = requireAuthority; }
        private void record(ChunkProtocolException observed) {
            requireAuthority.run();
            if (failure == null) failure = observed;
        }
        /** Vanilla may catch/wrap the thrown marker while retaining its committed mutation prefix. */
        void requireNoBreach(Throwable laterFailure) {
            if (failure == null) return;
            if (laterFailure != null && laterFailure != failure) failure.addSuppressed(laterFailure);
            throw failure;
        }
        @Override public void close() {
            INVOCATION.set(null);
            requireAuthority = null;
            failure = null;
        }
    }
}
