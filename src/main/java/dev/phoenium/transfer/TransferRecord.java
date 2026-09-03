package dev.phoenium.transfer;

import dev.phoenium.core.OwnerEpoch;
import dev.phoenium.failure.FailureBarrier;
import dev.phoenium.region.RegionId;
import java.util.Objects;

/** Durable generic transfer ledger; it records messages, never peer locking or waits. */
public final class TransferRecord {
    private final String objectId;
    private final RegionId source;
    private final RegionId destination;
    private final OwnerEpoch destinationEpoch;
    private TransferState state = TransferState.REQUESTED;
    public TransferRecord(String objectId, RegionId source, RegionId destination, OwnerEpoch destinationEpoch) {
        this.objectId = Objects.requireNonNull(objectId); this.source = Objects.requireNonNull(source);
        this.destination = Objects.requireNonNull(destination); this.destinationEpoch = Objects.requireNonNull(destinationEpoch);
    }
    public TransferState state() { return state; }
    public void sourcePrepared() { transition(TransferState.REQUESTED, TransferState.SOURCE_PREPARED); }
    public void destinationReserved(OwnerEpoch observed) { if (!destinationEpoch.equals(observed)) throw new IllegalStateException("stale destination epoch"); transition(TransferState.SOURCE_PREPARED, TransferState.DESTINATION_RESERVED); }
    public void commit() { transition(TransferState.DESTINATION_RESERVED, TransferState.COMMITTED); }
    public void destinationApplied() { transition(TransferState.COMMITTED, TransferState.DESTINATION_APPLIED); }
    public void complete() { transition(TransferState.DESTINATION_APPLIED, TransferState.COMPLETED); }
    public void reject() { transition(TransferState.REQUESTED, TransferState.REJECTED); }
    public void rollback() { if (state.ordinal() >= TransferState.COMMITTED.ordinal()) throw new IllegalStateException("post-commit rollback is forbidden"); state = TransferState.ROLLED_BACK; }
    public void postCommitFailure(FailureBarrier barrier) { if (state.ordinal() < TransferState.COMMITTED.ordinal()) throw new IllegalStateException("barrier only applies post-commit"); state = TransferState.FAILED_BARRIER; barrier.trip(new IllegalStateException("post-commit transfer failure " + objectId), null, destinationEpoch, null); }
    private void transition(TransferState expected, TransferState next) { if (state != expected) throw new IllegalStateException("illegal transfer transition " + state + " -> " + next); state = next; }
}
