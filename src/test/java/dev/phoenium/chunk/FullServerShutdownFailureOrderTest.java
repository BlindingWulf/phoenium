package dev.phoenium.chunk;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.server.MinecraftServer;
import org.junit.jupiter.api.Test;

import static dev.phoenium.chunk.FullDecisionWitnessSupport.Answer;
import static dev.phoenium.chunk.FullDecisionWitnessSupport.stub;
import static org.junit.jupiter.api.Assertions.*;

/** The terminal wrapper must retain every failure while it still closes its named services. */
final class FullServerShutdownFailureOrderTest {
    @Test
    void originalAndAcknowledgementFailuresStillRunNamedCleanupExactlyOnce() throws Exception {
        var events = new ArrayList<String>();
        var serviceLookups = new AtomicInteger();
        var originalFailure = new IllegalStateException("original stop failure");
        var acknowledgementFailure = new IllegalArgumentException("runtime acknowledgement failure");
        var namedServiceFailure = new IllegalStateException("named service failure");
        MinecraftServer server = stub(MinecraftServer.class, List.of(new Answer(
                MinecraftServer.class.getMethod("services"), ignored -> {
                    serviceLookups.incrementAndGet();
                    events.add("named-service");
                    throw namedServiceFailure;
                })));
        var service = FullFinishingServices.forServer(server);

        Throwable failure = assertThrows(IllegalStateException.class, () -> FullServerShutdown.run(server,
                () -> {
                    events.add("original-stop");
                    throw originalFailure;
                },
                () -> {
                    events.add("runtime-acknowledgement");
                    throw acknowledgementFailure;
                }));

        assertSame(originalFailure, failure, "the first stop failure must remain the thrown identity");
        assertEquals(List.of("original-stop", "runtime-acknowledgement", "named-service"), events,
                "acknowledgement and named cleanup must follow the original stop exactly once");
        assertEquals(1, serviceLookups.get(), "named service cleanup must not be retried");
        assertArrayEquals(new Throwable[]{acknowledgementFailure, namedServiceFailure}, failure.getSuppressed(),
                "later acknowledgement and cleanup failures must remain in execution order");

        service.legacyOwners.termination().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertEquals(1L, service.legacyOwners.measurements().get("producerTerminated"),
                "the named legacy-owner producer must terminate after a prior failure");
        assertThrows(ChunkProtocolException.class, () -> FullFinishingServices.forServer(server),
                "terminal cleanup must detach the service and prevent resurrection");

        // A repeated terminal acknowledgement is a no-op and cannot invoke the named service again.
        FullFinishingServices.stopped(server);
        assertEquals(1, serviceLookups.get(), "terminal cleanup must be exactly once");
    }
}
