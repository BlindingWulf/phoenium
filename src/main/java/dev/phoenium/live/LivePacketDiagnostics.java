package dev.phoenium.live;

/** Immutable packet-boundary counters for future real-client exactly-once evidence. */
public record LivePacketDiagnostics(
    long movementIngress, long movementBootstrapDeferred, long movementFootprintAccepted, long movementFootprintRejected,
    long movementOwnerExecution, long movementReplay, long movementHandlerCompletion,
    long movementRoutingReconciliation, long movementSameOwnerEpochPreserved, long movementStaleTicketRejected,
    long movementPortalRetainedRetired,
    long interactionIngress, long interactionIndexHit, long interactionIndexMiss, long interactionIncarnationRejected,
    long interactionOwnerAdmission, long interactionCrossOwnerRejected, long interactionOwnerExecution,
    long interactionReplay, long interactionVanillaLookup, long interactionHandlerCompletion,
    long interactionStaleTicketRejected,
    long playerActionIngress, long playerActionOwnerAdmission, long playerActionOwnerExecution,
    long playerActionReplay, long playerActionHandlerCompletion, long playerActionStaleTicketRejected,
    long useItemOnIngress, long useItemOnOwnerAdmission, long useItemOnOwnerExecution,
    long useItemOnReplay, long useItemOnHandlerCompletion, long useItemOnStaleTicketRejected,
    long useItemIngress, long useItemOwnerAdmission, long useItemOwnerExecution,
    long useItemReplay, long useItemHandlerCompletion, long useItemStaleTicketRejected,
    long blockPacketClosureRejected, long blockPacketDuplicate) {
    public String toLogLine() {
        String move = "move[in=" + movementIngress + ",defer=" + movementBootstrapDeferred + ",accept=" + movementFootprintAccepted + ",reject=" + movementFootprintRejected
            + ",owner=" + movementOwnerExecution + ",replay=" + movementReplay + ",effect=" + movementHandlerCompletion
            + ",reconcile=" + movementRoutingReconciliation + ",sameEpoch=" + movementSameOwnerEpochPreserved
            + ",stale=" + movementStaleTicketRejected + ",portalRetained=" + movementPortalRetainedRetired + "] interact[in=" + interactionIngress + ",hit=" + interactionIndexHit
            + ",miss=" + interactionIndexMiss + ",incarnationReject=" + interactionIncarnationRejected + ",ownerAdmission=" + interactionOwnerAdmission
            + ",crossReject=" + interactionCrossOwnerRejected + ",ownerExecution=" + interactionOwnerExecution + ",replay=" + interactionReplay
            + ",lookup=" + interactionVanillaLookup + ",effect=" + interactionHandlerCompletion + ",stale=" + interactionStaleTicketRejected + "]";
        return move + " action[in=" + playerActionIngress + ",admit=" + playerActionOwnerAdmission
            + ",owner=" + playerActionOwnerExecution + ",replay=" + playerActionReplay
            + ",effect=" + playerActionHandlerCompletion + ",stale=" + playerActionStaleTicketRejected
            + "] useItemOn[in=" + useItemOnIngress + ",admit=" + useItemOnOwnerAdmission
            + ",owner=" + useItemOnOwnerExecution + ",replay=" + useItemOnReplay
            + ",effect=" + useItemOnHandlerCompletion + ",stale=" + useItemOnStaleTicketRejected
            + "] useItem[in=" + useItemIngress + ",admit=" + useItemOwnerAdmission
            + ",owner=" + useItemOwnerExecution + ",replay=" + useItemReplay
            + ",effect=" + useItemHandlerCompletion + ",stale=" + useItemStaleTicketRejected
            + "] block[closureReject=" + blockPacketClosureRejected + ",duplicate=" + blockPacketDuplicate + "]";
    }
}
