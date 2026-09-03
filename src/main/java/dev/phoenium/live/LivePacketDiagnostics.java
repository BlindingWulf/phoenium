package dev.phoenium.live;

/** Immutable packet-boundary counters for future real-client exactly-once evidence. */
public record LivePacketDiagnostics(
    long movementIngress, long movementBootstrapDeferred, long movementFootprintAccepted, long movementFootprintRejected,
    long movementOwnerExecution, long movementReplay, long movementHandlerCompletion,
    long movementRoutingReconciliation, long movementSameOwnerEpochPreserved, long movementStaleTicketRejected,
    long interactionIngress, long interactionIndexHit, long interactionIndexMiss, long interactionIncarnationRejected,
    long interactionOwnerAdmission, long interactionCrossOwnerRejected, long interactionOwnerExecution,
    long interactionReplay, long interactionVanillaLookup, long interactionHandlerCompletion,
    long interactionStaleTicketRejected) {
    public String toLogLine() {
        return "move[in=" + movementIngress + ",defer=" + movementBootstrapDeferred + ",accept=" + movementFootprintAccepted + ",reject=" + movementFootprintRejected
            + ",owner=" + movementOwnerExecution + ",replay=" + movementReplay + ",effect=" + movementHandlerCompletion
            + ",reconcile=" + movementRoutingReconciliation + ",sameEpoch=" + movementSameOwnerEpochPreserved
            + ",stale=" + movementStaleTicketRejected + "] interact[in=" + interactionIngress + ",hit=" + interactionIndexHit
            + ",miss=" + interactionIndexMiss + ",incarnationReject=" + interactionIncarnationRejected + ",ownerAdmission=" + interactionOwnerAdmission
            + ",crossReject=" + interactionCrossOwnerRejected + ",ownerExecution=" + interactionOwnerExecution + ",replay=" + interactionReplay
            + ",lookup=" + interactionVanillaLookup + ",effect=" + interactionHandlerCompletion + ",stale=" + interactionStaleTicketRejected + "]";
    }
}
