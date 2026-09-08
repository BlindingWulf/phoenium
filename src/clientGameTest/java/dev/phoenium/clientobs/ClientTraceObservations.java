package dev.phoenium.clientobs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.HashSet;
import java.util.Set;

/** Test-only client packet observations kept outside the Mixin class. */
public final class ClientTraceObservations {
    private static final List<String> RECEIVED = new ArrayList<>();
    private static final Set<Long> RECEIVED_CHUNKS = new HashSet<>();

    private ClientTraceObservations() { }

    public static void clear() {
        synchronized (RECEIVED) {
            RECEIVED.clear();
            RECEIVED_CHUNKS.clear();
        }
    }

    /** Clear packet payload observations for a probe while retaining current-world chunk receipts. */
    public static void clearPacketEvents() {
        synchronized (RECEIVED) { RECEIVED.clear(); }
    }

    public static void record(String value) {
        synchronized (RECEIVED) { RECEIVED.add(value); }
    }

    public static void recordChunk(int x, int z) {
        synchronized (RECEIVED) { RECEIVED_CHUNKS.add(net.minecraft.world.level.ChunkPos.pack(x, z)); }
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
        synchronized (RECEIVED) { return RECEIVED_CHUNKS.contains(net.minecraft.world.level.ChunkPos.pack(x, z)); }
    }

    /** Stable typed chunk coordinates for a bounded FULL miss diagnostic. */
    public static String receivedChunksSnapshot() {
        synchronized (RECEIVED) {
            return RECEIVED_CHUNKS.stream().sorted()
                .map(key -> net.minecraft.world.level.ChunkPos.getX(key) + ","
                    + net.minecraft.world.level.ChunkPos.getZ(key))
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        }
    }
}
