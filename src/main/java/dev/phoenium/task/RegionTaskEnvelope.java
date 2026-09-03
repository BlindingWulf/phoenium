package dev.phoenium.task;

import dev.phoenium.core.OwnerEpoch;
import dev.phoenium.core.TopologyGeneration;
import dev.phoenium.topology.RoutingKey;
import java.util.Objects;

public record RegionTaskEnvelope(
    String operationKind, TaskPayload payload, RoutingKey routingKey,
    TopologyGeneration expectedGeneration, OwnerEpoch expectedOwnerEpoch,
    String origin, TaskCorrelationId correlationId, TaskSequence sequence, AdmissionPolicy admissionPolicy) {
    public RegionTaskEnvelope {
        Objects.requireNonNull(operationKind, "operationKind"); Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(routingKey, "routingKey"); Objects.requireNonNull(expectedGeneration, "expectedGeneration");
        Objects.requireNonNull(expectedOwnerEpoch, "expectedOwnerEpoch"); Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(correlationId, "correlationId"); Objects.requireNonNull(sequence, "sequence");
        Objects.requireNonNull(admissionPolicy, "admissionPolicy");
    }
    public RegionTaskEnvelope rerouted(TopologyGeneration generation, OwnerEpoch ownerEpoch) {
        return new RegionTaskEnvelope(operationKind, payload, routingKey, generation, ownerEpoch, origin, correlationId, sequence, admissionPolicy);
    }
}
