package dev.phoenium.chunk;

import dev.phoenium.core.OwnerEpoch;
import dev.phoenium.core.TopologyGeneration;
import dev.phoenium.failure.FailureBarrier;
import dev.phoenium.ownership.OperationCategory;
import dev.phoenium.ownership.OwnershipContext;
import dev.phoenium.ownership.RegionLease;
import dev.phoenium.ownership.RegionLeaseManager;
import dev.phoenium.region.RegionId;
import dev.phoenium.topology.RegionDirectory;
import dev.phoenium.topology.RegionRoute;
import java.util.Map;
import java.util.Objects;

/**
 * One synchronous stage under a current-owner lease. The custody metadata fence orders claim,
 * registration readiness and terminal fences. No Minecraft callback or future completion runs
 * inside it. A winning claim keeps its captured authority until the synchronous unit exits.
 */
public final class ChunkStageAdmission {
    public enum Result { APPLIED, NOT_READY, STALE_ROUTE, SUPERSEDED, CANCELLED, SHUTDOWN, DUPLICATE, FAILED_VANILLA, FAILED_BARRIER }
    record Execution<O>(Result result, O value, Throwable failure) { }
    private final ChunkCustodyModel custody;
    private final RegionDirectory directory;
    private final Map<RegionId, RegionLeaseManager> leases;
    private final FailureBarrier barrier;
    private long applications;
    private long staleRoutes;

    public ChunkStageAdmission(ChunkCustodyModel custody, RegionDirectory directory,
                               Map<RegionId, RegionLeaseManager> leases, FailureBarrier barrier) {
        this.custody = Objects.requireNonNull(custody); this.directory = Objects.requireNonNull(directory);
        this.leases = Map.copyOf(leases); this.barrier = Objects.requireNonNull(barrier);
    }
    public void closeAdmissions() { custody.closeAdmissions(); }
    public long applications() { return applications; }
    public long staleRoutes() { return staleRoutes; }

    /** Missing topology cannot cancel an active reservation. Settle without entering an owner. */
    Execution<Void> unroutable(PendingVanillaStage<?, ?> pending) {
        custody.gate().lock();
        try {
            if (!pending.belongsTo(custody)) return breachLocked(pending.stage(), null,
                    new ChunkProtocolException("foreign custody in route delivery"));
            var state = custody.stageState(pending.stage()).orElse(null);
            if (custody.hasActiveClaimLocked(pending.stage()) || state == ChunkCustodyModel.StageState.APPLIED
                    || state == ChunkCustodyModel.StageState.FAILED_VANILLA) return result(Result.DUPLICATE);
            if (pending.outcome().isDone()) return result(Result.DUPLICATE);
            if (!custody.admissionsOpenLocked()) return result(Result.SHUTDOWN);
            if (!custody.isEligibleLocked(pending.stage())) return result(classify(pending.stage()));
            return breachLocked(pending.stage(), null, new ChunkProtocolException(
                    "permanently unroutable active chunk work: " + pending.stage()));
        } finally { custody.gate().unlock(); }
    }

    <I, O> Execution<O> execute(PendingVanillaStage<I, O> pending, RegionId deliveredOwner,
                               TopologyGeneration deliveredGeneration) {
        Objects.requireNonNull(pending); Objects.requireNonNull(deliveredOwner);
        ChunkCustodyModel.StageId stage = pending.stage();
        final RegionRoute route;
        final RegionLease lease;
        final Object lightRegistration;
        custody.gate().lock();
        try {
            if (!pending.belongsTo(custody)) return breachLocked(stage, null, new ChunkProtocolException("foreign custody instance"));
            var state = custody.stageState(stage).orElse(null);
            if (state == ChunkCustodyModel.StageState.FAILED_BARRIER)
                return new Execution<>(Result.FAILED_BARRIER, null, barrier.record().orElseThrow().cause());
            // A late fence/duplicate must not complete the outcome while its winner holds a lease.
            if (state == ChunkCustodyModel.StageState.CLAIMED || state == ChunkCustodyModel.StageState.APPLIED
                    || state == ChunkCustodyModel.StageState.FAILED_VANILLA) return result(Result.DUPLICATE);
            if (!custody.admissionsOpenLocked()) return result(Result.SHUTDOWN);
            if (!custody.isEligibleLocked(stage)) return result(classify(stage));
            if (custody.targetClaimedLocked(stage)) return result(Result.NOT_READY);
            switch (pending.loadReadinessLocked()) {
                case NOT_REGISTERED -> { return result(Result.NOT_READY); }
                case STALE -> { return result(Result.SUPERSEDED); }
                case SHUTDOWN -> { return result(Result.SHUTDOWN); }
                case FAILED -> { return breachLocked(stage, null, new ChunkProtocolException("terminal load prerequisite")); }
                case REGISTERED -> { }
            }
            switch (pending.lightReadinessLocked()) {
                case NOT_REGISTERED -> { return result(Result.NOT_READY); }
                case STALE -> { return result(Result.SUPERSEDED); }
                case SHUTDOWN -> { return result(Result.SHUTDOWN); }
                case FAILED -> { return breachLocked(stage, null, new ChunkProtocolException("terminal light prerequisite")); }
                case REGISTERED -> { }
            }
            route = directory.resolve(stage.work().lifecycle().chunkKey()).orElse(null);
            if (route == null || !route.region().equals(deliveredOwner) || !route.generation().equals(deliveredGeneration)) {
                staleRoutes++; return result(Result.STALE_ROUTE);
            }
            RegionLeaseManager manager = leases.get(deliveredOwner);
            if (manager == null) return breachLocked(stage, route, new ChunkProtocolException("no lease manager for " + deliveredOwner));
            lightRegistration = pending.lightRegistrationLocked();
            try { lease = manager.acquire(); }
            catch (Throwable failure) { return breachLocked(stage, route, failure); }
            if (!custody.claimLocked(stage)) { lease.close(); return result(classify(stage)); }
        } finally { custody.gate().unlock(); }

        try (lease;
             OwnershipContext.Scope ignored = OwnershipContext.enter(deliveredOwner, lease.epoch(), OperationCategory.OWNER_TASK);
             ChunkStageClaim claim = new ChunkStageClaim(custody, stage, route, lease, lightRegistration);
             ChunkSynchronousAccessGuard.Invocation queryFaults = ChunkSynchronousAccessGuard.forClaim(claim)) {
            claim.require(stage);
            final O value;
            // Only the original, correctly admitted body is inside this vanilla-failure boundary.
            try { value = pending.invokeOriginal(claim); }
            catch (ChunkProtocolException failure) { queryFaults.requireNoBreach(failure); throw failure; }
            catch (Throwable failure) {
                queryFaults.requireNoBreach(failure);
                custody.failVanilla(stage);
                return new Execution<>(Result.FAILED_VANILLA, null, failure);
            }
            queryFaults.requireNoBreach(null);
            custody.gate().lock();
            try { custody.appliedLocked(stage); applications++; }
            finally { custody.gate().unlock(); }
            return new Execution<>(Result.APPLIED, value, null);
        } catch (Throwable failure) {
            custody.gate().lock();
            try { return breachLocked(stage, route, failure); }
            finally { custody.gate().unlock(); }
        } finally {
            custody.gate().lock();
            try { custody.releaseClaimLocked(stage); }
            finally { custody.gate().unlock(); }
        }
    }

    /** A foreign/unleased mutation attempt is a breach, distinct from an obsolete queue delivery. */
    public Result reportUnleasedOrForeignAttempt(ChunkCustodyModel.StageId stage, RegionId attemptedOwner) {
        custody.gate().lock();
        try {
            RegionRoute route = directory.resolve(stage.work().lifecycle().chunkKey()).orElse(null);
            return breachLocked(stage, route, new ChunkProtocolException("unleased or foreign stage attempt by " + attemptedOwner)).result();
        } finally { custody.gate().unlock(); }
    }
    private Result classify(ChunkCustodyModel.StageId stage) {
        return switch (custody.stageState(stage).orElse(null)) {
            case null -> Result.SUPERSEDED;
            case SUPERSEDED -> Result.SUPERSEDED;
            case CANCELLED -> Result.CANCELLED;
            case FAILED_BARRIER -> Result.FAILED_BARRIER;
            default -> Result.DUPLICATE;
        };
    }
    private <O> Execution<O> result(Result result) { return new Execution<>(result, null, null); }
    private <O> Execution<O> breachLocked(ChunkCustodyModel.StageId stage, RegionRoute route, Throwable failure) {
        custody.barrierLocked(stage); custody.closeAdmissions();
        barrier.trip(failure, route == null ? directory.snapshot().generation() : route.generation(),
                route == null ? new OwnerEpoch(0) : route.ownerEpoch(), null);
        return new Execution<>(Result.FAILED_BARRIER, null, failure);
    }
}
