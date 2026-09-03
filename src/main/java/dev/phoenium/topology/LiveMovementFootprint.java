package dev.phoenium.topology;

import dev.phoenium.core.WorldId;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Conservative geographic projection of one classified live continuous movement operation.
 * The routing destination is deliberately recorded separately: it is useful for later accepted
 * position bookkeeping, but it never alone authorizes the operation. Every listed component must
 * fit the frozen authority before vanilla movement, collision, index, or callback work begins.
 */
public record LiveMovementFootprint(
    WorldId world,
    RoutingKey routingDestination,
    List<ChunkBox> sweptCollision,
    List<ChunkBox> stepUpCandidates,
    List<ChunkBox> supportingBlockAccesses,
    List<ChunkBox> fluidAccesses,
    List<ChunkBox> entityIndexAccesses,
    Set<RoutingKey> synchronousCallbackAccesses
) {
    public LiveMovementFootprint {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(routingDestination, "routingDestination");
        if (!world.equals(routingDestination.world())) throw new IllegalArgumentException("routing destination crosses world");
        sweptCollision = immutableBoxes(world, sweptCollision, "sweptCollision");
        stepUpCandidates = immutableBoxes(world, stepUpCandidates, "stepUpCandidates");
        supportingBlockAccesses = immutableBoxes(world, supportingBlockAccesses, "supportingBlockAccesses");
        fluidAccesses = immutableBoxes(world, fluidAccesses, "fluidAccesses");
        entityIndexAccesses = immutableBoxes(world, entityIndexAccesses, "entityIndexAccesses");
        synchronousCallbackAccesses = Set.copyOf(Objects.requireNonNull(synchronousCallbackAccesses, "synchronousCallbackAccesses"));
        if (synchronousCallbackAccesses.stream().anyMatch(key -> !world.equals(key.world()))) {
            throw new IllegalArgumentException("callback access crosses world");
        }
    }

    /** Validates every actual component; {@code routingDestination} alone is intentionally absent. */
    public void requireWithin(FrozenRegionAuthority authority) {
        if (!world.equals(authority.region().world())) {
            throw new ClosureEscapeException("movement footprint world " + world.value()
                + " does not match frozen authority world " + authority.region().world().value());
        }
        sweptCollision.forEach(box -> box.requireWithin(authority));
        stepUpCandidates.forEach(box -> box.requireWithin(authority));
        supportingBlockAccesses.forEach(box -> box.requireWithin(authority));
        fluidAccesses.forEach(box -> box.requireWithin(authority));
        entityIndexAccesses.forEach(box -> box.requireWithin(authority));
        authority.requireWithin(synchronousCallbackAccesses);
    }

    /** A compact diagnostic projection for tests and admission diagnostics, not an authority source. */
    public Set<RoutingKey> namedAccesses() {
        Set<RoutingKey> keys = new LinkedHashSet<>(synchronousCallbackAccesses);
        keys.add(routingDestination);
        return Set.copyOf(keys);
    }

    private static List<ChunkBox> immutableBoxes(WorldId world, List<ChunkBox> boxes, String name) {
        List<ChunkBox> copy = List.copyOf(Objects.requireNonNull(boxes, name));
        if (copy.stream().anyMatch(box -> !world.equals(box.world()))) {
            throw new IllegalArgumentException(name + " crosses world");
        }
        return copy;
    }

    /** Inclusive routing-key box. It is chunk-space already; block/AABB projection belongs to the live adapter. */
    public record ChunkBox(WorldId world, long minX, long maxX, long minZ, long maxZ) {
        public ChunkBox {
            Objects.requireNonNull(world, "world");
            if (maxX < minX || maxZ < minZ) throw new IllegalArgumentException("inverted chunk box");
        }
        void requireWithin(FrozenRegionAuthority authority) {
            authority.requireWithinBox(world, minX, maxX, minZ, maxZ);
        }
    }
}
