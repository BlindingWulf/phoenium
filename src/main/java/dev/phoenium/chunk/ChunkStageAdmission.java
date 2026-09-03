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
 * Deterministic single-control-lane simulation of the stage admission protocol.  It reserves a
 * current-owner lease and claims the StageId under one small metadata gate; the lease exists only
 * for the synchronous operation.  It has no async work, peer-owner acquisition, or owner wait.
 */
public final class ChunkStageAdmission {
    public enum Result { APPLIED, STALE_ROUTE, SUPERSEDED, CANCELLED, SHUTDOWN, DUPLICATE, FAILED_BARRIER }
    private final ChunkCustodyModel custody;
    private final RegionDirectory directory;
    private final Map<RegionId, RegionLeaseManager> leases;
    private final FailureBarrier barrier;
    private boolean admissionsOpen = true;
    private long applications;
    private long staleRoutes;

    public ChunkStageAdmission(ChunkCustodyModel custody, RegionDirectory directory,
                               Map<RegionId, RegionLeaseManager> leases, FailureBarrier barrier) {
        this.custody = Objects.requireNonNull(custody); this.directory = Objects.requireNonNull(directory);
        this.leases = Map.copyOf(leases); this.barrier = Objects.requireNonNull(barrier);
    }
    public void closeAdmissions() { custody.gate().lock(); try { admissionsOpen = false; } finally { custody.gate().unlock(); } }
    public long applications() { return applications; }
    public long staleRoutes() { return staleRoutes; }

    /** Delivery must name the owner inbox it reached. A stale inbox copy does not consume claim. */
    public Result deliver(ChunkCustodyModel.StageId stage, RegionId deliveredOwner, Runnable originalOperation) {
        RegionRoute route = directory.resolve(stage.work().lifecycle().chunkKey()).orElse(null);
        return deliver(stage, deliveredOwner, route == null ? null : route.generation(), originalOperation);
    }
    /** A queued envelope retains a route generation only for validation, never as stage identity. */
    public Result deliver(ChunkCustodyModel.StageId stage, RegionId deliveredOwner, TopologyGeneration deliveredGeneration, Runnable originalOperation) {
        Objects.requireNonNull(stage); Objects.requireNonNull(deliveredOwner); Objects.requireNonNull(originalOperation);
        final RegionRoute route;
        final RegionLease lease;
        custody.gate().lock(); try {
            if (!admissionsOpen) return Result.SHUTDOWN;
            if (!custody.isEligibleLocked(stage)) return classify(stage);
            route = directory.resolve(stage.work().lifecycle().chunkKey()).orElse(null);
            if (route == null || !route.region().equals(deliveredOwner) || !route.generation().equals(deliveredGeneration)) { staleRoutes++; return Result.STALE_ROUTE; }
            RegionLeaseManager manager = leases.get(deliveredOwner);
            if (manager == null) return breachLocked(stage, route, new IllegalStateException("no lease manager for " + deliveredOwner));
            try { lease = manager.acquire(); }
            catch (Throwable failure) { return breachLocked(stage, route, failure); }
            if (!custody.claimLocked(stage)) { lease.close(); return classify(stage); }
        } finally { custody.gate().unlock(); }
        try (lease; OwnershipContext.Scope ignored = OwnershipContext.enter(deliveredOwner, lease.epoch(), OperationCategory.OWNER_TASK)) {
            lease.requireCurrent();
            RegionRoute current = directory.resolve(stage.work().lifecycle().chunkKey()).orElse(null);
            custody.gate().lock(); try {
                // Shutdown intentionally does not revoke an already claimed synchronous operation.
                if (current == null || !current.region().equals(deliveredOwner)) return breachLocked(stage, current, new IllegalStateException("claimed stage lost current owner"));
            } finally { custody.gate().unlock(); }
            originalOperation.run();
            custody.gate().lock(); try { custody.appliedLocked(stage); applications++; } finally { custody.gate().unlock(); }
            return Result.APPLIED;
        } catch (Throwable failure) {
            custody.gate().lock(); try { return breachLocked(stage, directory.resolve(stage.work().lifecycle().chunkKey()).orElse(null), failure); } finally { custody.gate().unlock(); }
        }
    }
    /** Explicit test-only invariant entrypoint: a real foreign/unleased mutation attempt is never stale normal flow. */
    public Result reportUnleasedOrForeignAttempt(ChunkCustodyModel.StageId stage, RegionId attemptedOwner) {
        custody.gate().lock(); try {
            RegionRoute route = directory.resolve(stage.work().lifecycle().chunkKey()).orElse(null);
            return breachLocked(stage, route, new IllegalStateException("unleased or foreign stage attempt by " + attemptedOwner));
        } finally { custody.gate().unlock(); }
    }
    private Result classify(ChunkCustodyModel.StageId stage) {
        return switch (custody.stageState(stage).orElse(null)) {
            case SUPERSEDED -> Result.SUPERSEDED;
            case CANCELLED -> Result.CANCELLED;
            case FAILED_BARRIER -> Result.FAILED_BARRIER;
            default -> Result.DUPLICATE;
        };
    }
    private Result breachLocked(ChunkCustodyModel.StageId stage, RegionRoute route, Throwable failure) {
        custody.barrierLocked(stage);
        barrier.trip(failure, route == null ? directory.snapshot().generation() : route.generation(),
            route == null ? new OwnerEpoch(0) : route.ownerEpoch(), null);
        return Result.FAILED_BARRIER;
    }
}
