package dev.phoenium.service;

import dev.phoenium.core.WorldId;
import dev.phoenium.topology.RoutingKey;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the tick receipt retirement used before any tracked-player callback. */
final class RegionalTrackingRouteHandoffTest {
    private static final WorldId WORLD = new WorldId("tracking-handoff");
    private static final RoutingKey A = new RoutingKey(WORLD, 0, 0);
    private static final RoutingKey B = new RoutingKey(WORLD, 32, 0);
    private static final RoutingKey C = new RoutingKey(WORLD, 64, 0);

    private record Route(RoutingKey key, long revision, long incarnation) { }

    private static final class Tick {
        private final Object player = new Object();
        private final Set<Object> deferred = Collections.newSetFromMap(new IdentityHashMap<>());
        private final AtomicReference<Route> published;
        private final AtomicInteger callbacks;

        Tick(AtomicReference<Route> published, AtomicInteger callbacks) {
            this.published = published;
            this.callbacks = callbacks;
        }

        boolean consume(Route captured) {
            boolean current = RegionalTrackingService.currentTickReceipt(player, deferred,
                () -> captured.equals(published.get()));
            if (current) {
                assertEquals(captured, published.get(), "callback must use the exact current receipt");
                callbacks.incrementAndGet();
            }
            return current;
        }
    }

    @Test void unchangedReceiptExecutesInItsCapturedOwnerTurn() {
        Route a = new Route(A, 1, 7);
        var current = new AtomicReference<>(a);
        var callbacks = new AtomicInteger();
        assertTrue(new Tick(current, callbacks).consume(a));
        assertEquals(1, callbacks.get());
    }

    @Test void committedRelocationRetiresOldReceiptAndNextTickUsesDestination() {
        Route a = new Route(A, 1, 7), b = new Route(B, 2, 7);
        var current = new AtomicReference<>(a);
        var callbacks = new AtomicInteger();
        Tick oldTick = new Tick(current, callbacks);
        current.set(b);
        assertFalse(oldTick.consume(a));
        assertFalse(oldTick.consume(a), "old tick cannot reenter or duplicate a retired receipt");
        assertEquals(0, callbacks.get());
        assertTrue(new Tick(current, callbacks).consume(b));
        assertEquals(1, callbacks.get());
    }

    @Test void intermediateRouteNeverExecutesAfterAToBToC() {
        Route a = new Route(A, 1, 7), b = new Route(B, 2, 7), c = new Route(C, 3, 7);
        var current = new AtomicReference<>(a);
        var callbacks = new AtomicInteger();
        Tick first = new Tick(current, callbacks);
        current.set(b);
        assertFalse(first.consume(a));
        current.set(c);
        assertFalse(first.consume(a));
        assertFalse(new Tick(current, callbacks).consume(b));
        assertTrue(new Tick(current, callbacks).consume(c));
        assertEquals(1, callbacks.get());
    }

    @Test void sameChunkRevisionChangeAlsoRetiresOldReceipt() {
        Route a1 = new Route(A, 1, 7), a2 = new Route(A, 2, 7);
        var current = new AtomicReference<>(a2);
        var callbacks = new AtomicInteger();
        assertFalse(new Tick(current, callbacks).consume(a1));
        assertTrue(new Tick(current, callbacks).consume(a2));
        assertEquals(1, callbacks.get());
    }

    @Test void departureOrReplacementRetiresOldIncarnationWithoutReadmission() {
        Route a = new Route(A, 1, 7);
        var current = new AtomicReference<>(a);
        var callbacks = new AtomicInteger();
        Tick oldTick = new Tick(current, callbacks);
        current.set(null);
        assertFalse(oldTick.consume(a));
        assertEquals(0, callbacks.get());
        current.set(new Route(A, 2, 8));
        assertFalse(oldTick.consume(a));
        assertEquals(0, callbacks.get());
    }
}
