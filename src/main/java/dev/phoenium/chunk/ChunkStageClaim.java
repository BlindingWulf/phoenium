package dev.phoenium.chunk;

import dev.phoenium.ownership.RegionLease;
import dev.phoenium.topology.RegionRoute;
import java.util.Objects;

/**
 * Explicit capability for one already-admitted synchronous transaction. Issued only by admission;
 * it cannot acquire authority from a thread or a later directory lookup. Close drops the lease
 * reference before any future is completed. A retained, closed capability cannot authorize work.
 */
public final class ChunkStageClaim implements AutoCloseable {
    private final ChunkCustodyModel.StageId stage;
    private final RegionRoute frozenRoute;
    private RegionLease lease;
    private ChunkCustodyModel custody;
    private Object lightRegistration;

    ChunkStageClaim(ChunkCustodyModel custody, ChunkCustodyModel.StageId stage, RegionRoute frozenRoute,
                    RegionLease lease, Object lightRegistration) {
        this.custody = Objects.requireNonNull(custody);
        this.stage = Objects.requireNonNull(stage);
        this.frozenRoute = Objects.requireNonNull(frozenRoute);
        this.lease = Objects.requireNonNull(lease);
        this.lightRegistration = lightRegistration;
    }
    public ChunkCustodyModel.StageId stage() { return stage; }
    public RegionRoute frozenRoute() { return frozenRoute; }
    public void require(ChunkCustodyModel.StageId expected) {
        if (!stage.equals(expected) || lease == null)
            throw new ChunkProtocolException("wrong or expired synchronous stage claim");
        try { lease.requireCurrent(); }
        catch (IllegalStateException failure) {
            throw new ChunkProtocolException("light/stage operation requires its admitted owner lease", failure);
        }
    }
    boolean belongsTo(ChunkCustodyModel expected) { return custody == expected; }
    boolean bindsLight(Object expected) { return expected != null && lightRegistration == expected; }
    @Override public void close() { lease = null; custody = null; lightRegistration = null; }
}
