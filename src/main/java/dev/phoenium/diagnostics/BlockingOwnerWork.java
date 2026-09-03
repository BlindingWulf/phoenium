package dev.phoenium.diagnostics;

import dev.phoenium.ownership.OwnershipContext;

/** Gateway-facing guard for T-05. Stage 1 exposes no wait operation. */
public final class BlockingOwnerWork {
    private BlockingOwnerWork() {}
    public static void reject(String target) {
        if (OwnershipContext.current().isPresent()) {
            throw new IllegalStateException("owner work may not synchronously wait for " + target);
        }
    }
}
