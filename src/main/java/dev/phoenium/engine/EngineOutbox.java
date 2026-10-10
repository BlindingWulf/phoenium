package dev.phoenium.engine;

import java.util.ArrayList;
import java.util.IdentityHashMap;

/**
 * S9-E9: one thread's held packet sends while it runs a capturing parallel-for ({@link EngineTrackerSends}): per
 * connection, in the order this thread sent them. A thread fills only its own outbox and touches no connection, so the
 * job's threads never send to one connection at once (Connection.send locks its pending-action queue and counts packets
 * in a plain field). After the job the server thread has every connection's sends delivered by one thread: outbox by
 * outbox in a fixed order (the server thread's, then the tile threads' in thread order), each in the order it was held,
 * so each entity's packets keep their order.
 */
final class EngineOutbox {
    /** Delivers one held send (a connection's send(packet, listener) outside any capturing job). */
    interface Sender {
        void send(Object connection, Object packet, Object listener);
    }

    /** A held send with a completion listener; a send without one holds the packet itself. */
    private record WithListener(Object packet, Object listener) { }

    private final IdentityHashMap<Object, ArrayList<Object>> held = new IdentityHashMap<>();
    /** The connections this outbox holds sends for, first held first. */
    private final ArrayList<Object> connections = new ArrayList<>();
    private final ArrayList<ArrayList<Object>> spare = new ArrayList<>();
    private int packets;

    void add(Object connection, Object packet, Object listener) {
        ArrayList<Object> sends = held.get(connection);
        if (sends == null) {
            sends = spare.isEmpty() ? new ArrayList<>() : spare.remove(spare.size() - 1);
            held.put(connection, sends);
            connections.add(connection);
        }
        sends.add(listener == null ? packet : new WithListener(packet, listener));
        packets++;
    }

    int packets() { return packets; }

    /** Empties the outbox, keeping its lists for the next job. */
    void clear() {
        for (int i = 0, n = connections.size(); i < n; i++) {
            ArrayList<Object> sends = held.get(connections.get(i));
            sends.clear();
            spare.add(sends);
        }
        held.clear();
        connections.clear();
        packets = 0;
    }

    // ---- delivery (the outboxes are only read while it runs) ----

    /** The connections any outbox holds sends for: the first outbox's in order, then each next outbox's new ones. */
    static void targets(EngineOutbox[] boxes, ArrayList<Object> out, IdentityHashMap<Object, Boolean> seen) {
        for (EngineOutbox box : boxes) {
            for (int i = 0, n = box.connections.size(); i < n; i++) {
                Object connection = box.connections.get(i);
                if (seen.put(connection, Boolean.TRUE) == null) out.add(connection);
            }
        }
    }

    /** One connection's held sends from every outbox, outbox by outbox; returns how many were sent. */
    static int deliver(EngineOutbox[] boxes, Object connection, Sender sender) {
        int sent = 0;
        for (EngineOutbox box : boxes) {
            ArrayList<Object> sends = box.held.get(connection);
            if (sends == null) continue;
            for (int i = 0, n = sends.size(); i < n; i++) {
                Object send = sends.get(i);
                if (send instanceof WithListener with) sender.send(connection, with.packet(), with.listener());
                else sender.send(connection, send, null);
            }
            sent += sends.size();
        }
        return sent;
    }
}
