package dev.phoenium.clientobs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Test-only client packet observations kept outside the Mixin class. */
public final class ClientTraceObservations {
    private static final List<String> RECEIVED = new ArrayList<>();

    private ClientTraceObservations() { }

    public static void clear() {
        synchronized (RECEIVED) { RECEIVED.clear(); }
    }

    public static void record(String value) {
        synchronized (RECEIVED) { RECEIVED.add(value); }
    }

    public static boolean received(String packetSimpleName, String payloadToken) {
        synchronized (RECEIVED) {
            return RECEIVED.stream().anyMatch(value -> value.contains(packetSimpleName)
                && (payloadToken == null || value.contains(payloadToken)));
        }
    }

    public static boolean receivedAll(String packetSimpleName, String... payloadTokens) {
        synchronized (RECEIVED) {
            return RECEIVED.stream().anyMatch(value -> value.contains(packetSimpleName)
                && Arrays.stream(payloadTokens).allMatch(value::contains));
        }
    }

    /** A chunk packet is the client side receipt for the native FULL holder payload. */
    public static boolean receivedChunk(int x, int z) {
        String token = "chunk=" + x + "," + z;
        synchronized (RECEIVED) {
            return RECEIVED.stream().anyMatch(value -> value.contains("ClientboundLevelChunkWithLightPacket")
                && value.contains(token));
        }
    }
}
