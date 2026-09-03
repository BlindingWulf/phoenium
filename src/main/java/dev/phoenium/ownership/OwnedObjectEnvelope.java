package dev.phoenium.ownership;

import dev.phoenium.core.OwnerEpoch;
import dev.phoenium.task.AdmissionPolicy;
import dev.phoenium.task.TaskCorrelationId;
import dev.phoenium.task.TaskSequence;
import java.util.Objects;

/** Typed ingress description. It deliberately carries no Entity, ServerPlayer, packet, or callback. */
public record OwnedObjectEnvelope(String objectId, OwnerEpoch expectedObjectEpoch, String operationKind,
                                  String origin, TaskCorrelationId correlationId, TaskSequence sequence,
                                  AdmissionPolicy admissionPolicy) {
    public OwnedObjectEnvelope {
        Objects.requireNonNull(objectId, "objectId"); Objects.requireNonNull(expectedObjectEpoch, "expectedObjectEpoch");
        Objects.requireNonNull(operationKind, "operationKind"); Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(correlationId, "correlationId"); Objects.requireNonNull(sequence, "sequence");
        Objects.requireNonNull(admissionPolicy, "admissionPolicy");
    }
    public OwnedObjectEnvelope rerouted(OwnerEpoch epoch) {
        return new OwnedObjectEnvelope(objectId, epoch, operationKind, origin, correlationId, sequence, admissionPolicy);
    }
}
