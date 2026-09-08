package dev.phoenium.chunk;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import org.junit.jupiter.api.Test;

import static dev.phoenium.chunk.FullDecisionWitnessSupport.stub;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the same conditional used by every native resource-call wrapper. */
final class FailedStopNativeCleanupTest {
    @Test
    void healthyInvocationPreservesTheFirstNativeFailureAndDoesNotContinue() {
        var events = new ArrayList<String>();
        var nativeFailure = new IllegalStateException("healthy native close");
        Throwable failure = assertThrows(IllegalStateException.class, () -> {
            invoke(() -> { events.add("first"); throw nativeFailure; });
            events.add("after-first");
        });
        assertSame(nativeFailure, failure);
        assertEquals(List.of("first"), events);
    }

    @Test
    void failedStopCapturesOneCloseAndRunsItsSiblingInNativeOrder() throws Exception {
        MinecraftServer server = stub(MinecraftServer.class, List.of());
        var events = new ArrayList<String>();
        var drainFailure = new IllegalStateException("retained operation failed");
        var firstCloseFailure = new IllegalArgumentException("packet processor close failed");

        Throwable failure = assertThrows(IllegalStateException.class, () -> FullServerShutdown.run(server,
                () -> { },
                () -> { throw drainFailure; },
                () -> {
                    invoke(() -> { events.add("first"); throw firstCloseFailure; });
                    events.add("between");
                    invoke(() -> events.add("second"));
                },
                () -> events.add("runtime-ack")));

        assertSame(drainFailure, failure);
        assertArrayEquals(new Throwable[]{firstCloseFailure}, failure.getSuppressed());
        assertEquals(List.of("runtime-ack", "first", "between", "second"), events);
    }

    private static void invoke(Runnable body) {
        FullServerShutdown.cleanup(body);
    }
}
