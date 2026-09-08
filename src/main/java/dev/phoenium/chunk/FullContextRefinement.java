package dev.phoenium.chunk;

import dev.phoenium.ownership.OperationCategory;
import dev.phoenium.ownership.OwnershipContext;
import dev.phoenium.ownership.RegionLease;
import dev.phoenium.topology.RegionRoute;
import dev.phoenium.topology.RoutingKey;
import java.util.Objects;
import java.util.Set;

/** Finite owner-read phase before FULL. The caller must separately retain exact object
 * inputs and reserve payload capacity before copying dependency syntax. All new demand
 * and convergence happen after this method returns, with no owner lease held.
 */
final class FullContextRefinement {
    enum State { OBSERVED, PENDING, STALE, TERMINAL }
    record Observation<T>(State state, T value) { }
    @FunctionalInterface interface Reader<T> { T read(FullContextReadClaim claim); }
    private FullContextRefinement() { }

    static <T> Observation<T> read(FullRegistrationRuntime runtime, FullRegistrationRuntime.Graph graph,
                                  RegionRoute expected, Set<RoutingKey> footprint, Reader<T> reader) {
        Objects.requireNonNull(runtime); Objects.requireNonNull(graph); Objects.requireNonNull(expected);
        Objects.requireNonNull(reader);
        runtime.requireObservationThread();
        if (OwnershipContext.current().isPresent() || FullFinishingServices.finishing())
            throw new ChunkProtocolException("context refinement must be admitted before an owner or FULL body");
        Set<RoutingKey> frozen = Set.copyOf(footprint);
        if (!frozen.contains(graph.lifecycle.chunkKey()))
            throw new ChunkProtocolException("context refinement omitted its source chunk");
        var custody = runtime.custody;
        final ChunkCustodyModel.ContextUse use;
        final RegionLease lease;
        custody.gate().lock();
        try {
            if (!graph.finishing.belongsTo(custody)) throw new ChunkProtocolException("context refinement belongs to another custody instance");
            if (!custody.admissionsOpenLocked() || !custody.metadataEntitledLocked(graph.work))
                return new Observation<>(State.TERMINAL, null);
            if (!graph.finishing.contextInputReadyLocked(graph.holder)) return new Observation<>(State.PENDING, null);
            for (RoutingKey key : frozen) {
                if (!key.world().equals(runtime.authority.world()))
                    throw new ChunkProtocolException("context refinement cannot acquire a foreign-world footprint");
                if (runtime.authority.directory().pendingChange(key).filter(change -> !change.completed()).isPresent())
                    return new Observation<>(State.PENDING, null);
                if (!expected.equals(runtime.authority.directory().resolve(key).orElse(null)))
                    return new Observation<>(State.STALE, null);
            }
            var manager = runtime.authority.leases().get(expected.region());
            if (manager == null) throw new ChunkProtocolException("context refinement has no named owner lease manager");
            if (manager.hasActiveLease()) {
                graph.awaitContextLease(manager);
                return new Observation<>(State.PENDING, null);
            }
            graph.clearContextLeaseWaiter();
            use = custody.beginContextUseLocked(graph.finishing.stage(), graph.holder);
            if (use == null) return new Observation<>(State.PENDING, null);
            try { lease = manager.acquire(); }
            catch (Throwable failure) { custody.endContextUseLocked(use); throw failure; }
        } finally { custody.gate().unlock(); }

        T value = null; Throwable failure = null;
        try (lease;
             var owner = OwnershipContext.enter(expected.region(), lease.epoch(), OperationCategory.OWNER_TASK);
             var claim = new FullContextReadClaim(custody, use, lease, expected, frozen);
             var faults = ChunkSynchronousAccessGuard.forContextRead(claim)) {
            try { value = reader.read(claim); }
            catch (Throwable problem) { faults.requireNoBreach(problem); throw problem; }
            faults.requireNoBreach(null);
        } catch (Throwable problem) { failure = problem; }
        // Detach the read use only after its lease and capability are revoked. Every
        // terminal acknowledgement is independent and preserves the first failure.
        var failures = new FullInputNotifications.Failures();
        Throwable first = failure;
        if (first != null) failures.run(() -> { if (first instanceof Error error) throw error;
            if (first instanceof RuntimeException error) throw error;
            throw new ChunkProtocolException("context syntax observation failed", first); });
        failures.run(() -> {
            custody.gate().lock();
            try { custody.endContextUseLocked(use); }
            finally { custody.gate().unlock(); }
        });
        failures.run(graph::contextReadExited);
        failures.rethrow();
        custody.gate().lock();
        try {
            return custody.admissionsOpenLocked() && custody.metadataEntitledLocked(graph.work)
                    ? new Observation<>(State.OBSERVED, value) : new Observation<>(State.TERMINAL, null);
        } finally { custody.gate().unlock(); }
    }
}
