package dev.phoenium.mixin;

import dev.phoenium.chunk.FullServerShutdown;

/** Shared conditional used by the exact native close call sites below. */
final class FailedStopCleanup {
    private FailedStopCleanup() { }

    static void run(Runnable original) {
        if (FullServerShutdown.failedStop()) FullServerShutdown.cleanup(original);
        else original.run();
    }
}
