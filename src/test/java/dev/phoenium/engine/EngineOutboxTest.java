package dev.phoenium.engine;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * S9-E9: an outbox holds each connection's sends in the order they were held; delivery gives a connection every
 * outbox's sends, outbox by outbox, with their listeners; targets lists each connection once, first held first.
 */
class EngineOutboxTest {
    private record Sent(Object connection, Object packet, Object listener) { }

    @Test
    void deliveryKeepsEachOutboxsOrderPerConnection() {
        Object a = new Object();
        Object b = new Object();
        Object c = new Object();
        EngineOutbox[] boxes = {new EngineOutbox(), new EngineOutbox(), new EngineOutbox()};
        Object listener = new Object();
        boxes[0].add(b, "b1", null);
        boxes[0].add(a, "a1", null);
        boxes[0].add(b, "b2", listener);
        boxes[1].add(c, "c1", null);
        boxes[1].add(a, "a2", null);
        boxes[2].add(a, "a3", null);
        boxes[2].add(b, "b3", null);
        assertEquals(3, boxes[0].packets());

        ArrayList<Object> targets = new ArrayList<>();
        EngineOutbox.targets(boxes, targets, new IdentityHashMap<>());
        assertEquals(List.of(b, a, c), targets);

        List<Sent> sent = new ArrayList<>();
        EngineOutbox.Sender sender = (connection, packet, with) -> sent.add(new Sent(connection, packet, with));
        assertEquals(3, EngineOutbox.deliver(boxes, a, sender));
        assertEquals(List.of(new Sent(a, "a1", null), new Sent(a, "a2", null), new Sent(a, "a3", null)), sent);
        sent.clear();
        assertEquals(3, EngineOutbox.deliver(boxes, b, sender));
        assertEquals(List.of(new Sent(b, "b1", null), new Sent(b, "b2", listener), new Sent(b, "b3", null)), sent);
        sent.clear();
        assertEquals(0, EngineOutbox.deliver(boxes, new Object(), sender));
        assertTrue(sent.isEmpty());
    }

    @Test
    void clearEmptiesAndTheListsAreReused() {
        Object a = new Object();
        EngineOutbox box = new EngineOutbox();
        EngineOutbox[] boxes = {box};
        for (int round = 0; round < 5; round++) {
            for (int i = 0; i < 100; i++) box.add(a, round * 1000 + i, null);
            List<Object> sent = new ArrayList<>();
            assertEquals(100, EngineOutbox.deliver(boxes, a, (connection, packet, listener) -> sent.add(packet)));
            assertEquals(round * 1000, sent.get(0));
            assertEquals(round * 1000 + 99, sent.get(99));
            box.clear();
            assertEquals(0, box.packets());
            ArrayList<Object> targets = new ArrayList<>();
            EngineOutbox.targets(boxes, targets, new IdentityHashMap<>());
            assertTrue(targets.isEmpty());
        }
    }
}
