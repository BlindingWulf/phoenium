package dev.phoenium.live;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.phoenium.core.WorldId;
import dev.phoenium.topology.RoutingKey;
import dev.phoenium.topology.TopologyPlanner;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class BlockPacketFootprintTest {
    private static final WorldId OVERWORLD = new WorldId("minecraft:overworld");

    @Test
    void sameChunkPlayerAndTargetDeduplicateToOneSeedAndNormalRadiusOneClosure() {
        RoutingKey player = new RoutingKey(OVERWORLD, -1, 0);
        RoutingKey target = new RoutingKey(OVERWORLD, -1, 0);

        Set<RoutingKey> seeds = PhoeniumLiveRuntime.blockPacketSeeds(player, target);

        assertEquals(1, seeds.size());
        assertEquals(List.of(player), List.copyOf(seeds));
        assertThrows(UnsupportedOperationException.class,
            () -> seeds.add(new RoutingKey(OVERWORLD, 99, 99)));
        Set<RoutingKey> footprint = PhoeniumLiveRuntime.blockPacketFootprint(player, target);
        assertEquals(9, footprint.size());
        assertEquals(new TopologyPlanner().requiredClosure(Set.of(player), 1), footprint);
    }

    @Test
    void differentChunkPlayerAndTargetPreserveBothSeedsInDeterministicOrder() {
        RoutingKey player = new RoutingKey(OVERWORLD, -1, 0);
        RoutingKey target = new RoutingKey(OVERWORLD, 0, 0);

        Set<RoutingKey> seeds = PhoeniumLiveRuntime.blockPacketSeeds(player, target);

        assertEquals(2, seeds.size());
        assertEquals(List.of(player, target), List.copyOf(seeds));
        Set<RoutingKey> footprint = PhoeniumLiveRuntime.blockPacketFootprint(player, target);
        assertEquals(12, footprint.size());
        assertEquals(new TopologyPlanner().requiredClosure(List.of(player, target), 1), footprint);
    }

    @Test
    void diagnosticDerivationFailureCannotReplaceTheOriginalPacketFailure() {
        RuntimeException original = new IllegalStateException("original packet rejection");
        RuntimeException diagnostic = new IllegalArgumentException("diagnostic derivation failed");
        Set<RoutingKey> fallback = Set.of();

        Set<RoutingKey> result = PhoeniumLiveRuntime.packetDiagnosticValue(original, () -> {
            throw diagnostic;
        }, fallback);

        assertSame(fallback, result);
        assertEquals("original packet rejection", original.getMessage());
        assertArrayEquals(new Throwable[] { diagnostic }, original.getSuppressed());
    }
}
