package dev.phoenium.chunk;

import dev.phoenium.ownership.RegionLease;
import dev.phoenium.topology.RegionRoute;
import dev.phoenium.topology.RoutingKey;
import java.util.Set;

/** Capability for audited dependency-field observations only. It is deliberately not a
 * ChunkStageClaim: it cannot enter FULL finishing, query private inputs, invoke a saved
 * reader, serialize gameplay state, publish a result, or lend authority to another owner.
 */
final class FullContextReadClaim implements AutoCloseable {
    private ChunkCustodyModel custody;
    private ChunkCustodyModel.ContextUse use;
    private RegionLease lease;
    private Set<RoutingKey> footprint;
    private final RegionRoute route;

    FullContextReadClaim(ChunkCustodyModel custody, ChunkCustodyModel.ContextUse use,
                         RegionLease lease, RegionRoute route, Set<RoutingKey> footprint) {
        this.custody = custody; this.use = use; this.lease = lease;
        this.route = route; this.footprint = footprint;
    }
    RegionRoute frozenRoute() { return route; }
    void require(FullRegistrationRuntime.Graph graph, RoutingKey position) {
        requireActive();
        if (!use.stage().equals(graph.finishing.stage()) || use.holder() != graph.holder
                || !footprint.contains(position))
            throw new ChunkProtocolException("context read lacks its exact source and declared regional position");
    }
    void requireActive() {
        if (lease == null || custody == null || use == null)
            throw new ChunkProtocolException("expired context read claim");
        try { lease.requireCurrent(); }
        catch (IllegalStateException failure) { throw new ChunkProtocolException("context read requires its exact owner lease", failure); }
        custody.gate().lock();
        try {
            if (!custody.hasContextUseLocked(use)) throw new ChunkProtocolException("context read source was acknowledged");
        } finally { custody.gate().unlock(); }
    }
    @Override public void close() { custody = null; use = null; lease = null; footprint = null; }
}
