package dev.phoenium.ownership;

import dev.phoenium.core.OwnerEpoch;
import dev.phoenium.region.RegionId;
import dev.phoenium.topology.RegionDirectory;
import dev.phoenium.topology.RegionRoute;
import dev.phoenium.topology.RoutingKey;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Narrow metadata ledger for real-object bindings. It cannot grant authority: every read is validated
 * against the current RegionDirectory and mutation additionally requires the active exact region lease.
 */
public final class ObjectOwnershipRegistry {
    private final RegionDirectory directory;
    private final Map<String, ObjectOwnership> entries = new LinkedHashMap<>();
    private final ReentrantLock metadataGate = new ReentrantLock();
    private boolean closed;

    public ObjectOwnershipRegistry(RegionDirectory directory) { this.directory = Objects.requireNonNull(directory, "directory"); }

    public ObjectOwnership admit(String objectId, OwnedObjectKind kind, RoutingKey key) {
        metadataGate.lock();
        try {
            if (closed) throw new IllegalStateException("object registry lifetime has ended");
            ObjectOwnership previous = entries.get(objectId);
            if (previous != null && previous.state() != ObjectOwnership.State.REMOVED) throw new IllegalStateException("duplicate object ownership " + objectId);
            RegionRoute route = route(key);
            long incarnation = previous == null ? 0 : previous.incarnation() + 1;
            OwnerEpoch epoch = previous == null ? new OwnerEpoch(0) : previous.objectEpoch().next();
            ObjectOwnership binding = new ObjectOwnership(objectId, kind, key, incarnation, route.region(), route.generation(), route.ownerEpoch(), epoch, ObjectOwnership.State.OWNED);
            entries.put(objectId, binding);
            return binding;
        } finally { metadataGate.unlock(); }
    }

    /** @deprecated use updateRoutingKey or the explicit transitionOwner publication. */
    @Deprecated(forRemoval = true)
    public ObjectOwnership move(String objectId, RoutingKey destination) {
        ObjectOwnership source = requiredLive(objectId);
        return updateRoutingKey(objectId, destination, source.objectEpoch());
    }

    /** Publishes a coordinate/routing update without changing object authority. */
    public ObjectOwnership updateRoutingKey(String objectId, RoutingKey destination, OwnerEpoch expectedObjectEpoch) {
        metadataGate.lock();
        try {
            ObjectOwnership source = requiredLive(objectId);
            RegionRoute target = route(destination);
            if (!source.objectEpoch().equals(expectedObjectEpoch)) throw new IllegalStateException("stale object epoch for routing update " + objectId);
            if (target.region().equals(source.owner())) {
                ObjectOwnership updated = with(source, destination, target.region(), target.generation(), target.ownerEpoch(), source.objectEpoch(), ObjectOwnership.State.OWNED);
                entries.put(objectId, updated);
                return updated;
            }
            throw new IllegalStateException("routing update crosses object owner; use transitionOwner after a quiescent publication boundary");
        } finally { metadataGate.unlock(); }
    }

    /**
     * Explicit object-owner publication. Callers must have already closed admission and reached
     * their topology/lease boundary; this ledger only commits the indivisible metadata snapshot.
     */
    public ObjectOwnership transitionOwner(String objectId, RoutingKey destination, OwnerEpoch expectedObjectEpoch) {
        metadataGate.lock();
        try {
            ObjectOwnership source = requiredLive(objectId);
            if (!source.objectEpoch().equals(expectedObjectEpoch)) throw new IllegalStateException("stale object epoch for owner transition " + objectId);
            RegionRoute target = route(destination);
            if (target.region().equals(source.owner())) throw new IllegalArgumentException("same-owner route must use updateRoutingKey");
            return transitionOwnerLocked(source, destination, target);
        } finally { metadataGate.unlock(); }
    }

    private ObjectOwnership transitionOwnerLocked(ObjectOwnership source, RoutingKey destination, RegionRoute target) {
            // These states are deliberately never observable outside this narrow publication gate.
            String objectId = source.objectId();
            ObjectOwnership prepared = with(source, source.routingKey(), source.owner(), source.topologyGeneration(), source.regionOwnerEpoch(), source.objectEpoch(), ObjectOwnership.State.SOURCE_PREPARED);
            entries.put(objectId, prepared);
            ObjectOwnership reserved = with(prepared, destination, target.region(), target.generation(), target.ownerEpoch(), prepared.objectEpoch(), ObjectOwnership.State.DESTINATION_RESERVED);
            entries.put(objectId, reserved);
            ObjectOwnership committed = with(reserved, destination, target.region(), target.generation(), target.ownerEpoch(), reserved.objectEpoch().next(), ObjectOwnership.State.OWNED);
            entries.put(objectId, committed);
            return committed;
    }

    /**
     * Quiescent-boundary reconciliation. The topology publisher must invoke this after publishing a new
     * generation (lock order: coordinator gate then this metadata gate). Every live binding is re-derived
     * against the directory: a binding whose chunk is now routed to another region commits exactly one
     * object-epoch increment through the same narrow gate as an explicit owner transition; a same-owner
     * generation/region-epoch drift only refreshes the derived snapshot. Returns the number of owner
     * transitions committed. A key that no longer resolves fails closed at the boundary rather than
     * stranding a binding.
     */
    public int reconcileTopology() {
        metadataGate.lock();
        try {
            int transitions = 0;
            for (ObjectOwnership binding : entries.values()) {
                if (binding.state() != ObjectOwnership.State.OWNED) continue;
                RegionRoute route = route(binding.routingKey());
                if (route.region().equals(binding.owner())) {
                    if (!route.generation().equals(binding.topologyGeneration()) || !route.ownerEpoch().equals(binding.regionOwnerEpoch())) {
                        entries.put(binding.objectId(), with(binding, binding.routingKey(), route.region(), route.generation(), route.ownerEpoch(), binding.objectEpoch(), ObjectOwnership.State.OWNED));
                    }
                    continue;
                }
                transitionOwnerLocked(binding, binding.routingKey(), route);
                transitions++;
            }
            return transitions;
        } finally { metadataGate.unlock(); }
    }

    public void remove(String objectId) {
        metadataGate.lock();
        try {
            ObjectOwnership current = requiredLive(objectId);
            entries.put(objectId, with(current, current.routingKey(), current.owner(), current.topologyGeneration(), current.regionOwnerEpoch(), current.objectEpoch().next(), ObjectOwnership.State.REMOVED));
        } finally { metadataGate.unlock(); }
    }

    /** Re-resolves current directory state; a stale derived record is never usable as authority. */
    public Optional<ObjectOwnership> current(String objectId) {
        metadataGate.lock();
        try {
            ObjectOwnership binding = entries.get(objectId);
            if (binding == null || binding.state() != ObjectOwnership.State.OWNED) return Optional.empty();
            RegionRoute route = route(binding.routingKey());
            if (!binding.owner().equals(route.region())) {
                // A directory change alone never grants a new object owner. The topology publisher
                // must reconcile this object at its quiescent boundary with transitionOwner().
                return Optional.empty();
            }
            if (!binding.topologyGeneration().equals(route.generation()) || !binding.regionOwnerEpoch().equals(route.ownerEpoch())) {
                binding = with(binding, binding.routingKey(), route.region(), route.generation(), route.ownerEpoch(), binding.objectEpoch(), ObjectOwnership.State.OWNED);
                entries.put(objectId, binding);
            }
            return Optional.of(binding);
        } finally { metadataGate.unlock(); }
    }

    /** The Stage 3 anti-cheat: an outer world lease does not authorize a foreign object. */
    public ObjectOwnership requireCurrentOwner(String objectId, OwnerEpoch expectedObjectEpoch, RegionId executingRegion) {
        ObjectOwnership binding = current(objectId).orElseThrow(() -> new IllegalStateException("object is absent, removed, or transitioning: " + objectId));
        if (!binding.objectEpoch().equals(expectedObjectEpoch)) throw new IllegalStateException("stale object epoch for " + objectId);
        RegionRoute route = route(binding.routingKey());
        if (!binding.owner().equals(executingRegion) || !route.region().equals(executingRegion)) throw new IllegalStateException("foreign object mutation: " + objectId + " is owned by " + binding.owner());
        OwnershipContext.Active active = OwnershipContext.current().orElseThrow(() -> new IllegalStateException("object mutation without ownership context"));
        if (!active.region().equals(executingRegion)) throw new IllegalStateException("object mutation context differs from executing region");
        return binding;
    }

    public int liveCount() { return (int) entries.values().stream().filter(value -> value.state() == ObjectOwnership.State.OWNED).count(); }
    /** Terminal metadata cleanup after the scheduler and all retained consumers settle. */
    public void close() {
        if (OwnershipContext.current().isPresent()) throw new IllegalStateException("object registry closed under ownership");
        metadataGate.lock();
        try { closed = true; entries.clear(); }
        finally { metadataGate.unlock(); }
    }
    public int playerCount() { return (int) entries.values().stream().filter(value -> value.kind() == OwnedObjectKind.PLAYER && value.state() == ObjectOwnership.State.OWNED).count(); }
    public java.util.List<ObjectOwnership> ownedInWorld(dev.phoenium.core.WorldId world) {
        return entries.values().stream().filter(value -> value.state() == ObjectOwnership.State.OWNED && value.routingKey().world().equals(world)).toList();
    }

    private RegionRoute route(RoutingKey key) { return directory.resolve(key).orElseThrow(() -> new IllegalArgumentException("unknown ownership key " + key)); }
    private ObjectOwnership requiredLive(String objectId) { return current(objectId).orElseThrow(() -> new IllegalStateException("object is absent, removed, or transitioning: " + objectId)); }
    private static ObjectOwnership with(ObjectOwnership source, RoutingKey key, RegionId owner, dev.phoenium.core.TopologyGeneration topology, OwnerEpoch regionEpoch, OwnerEpoch objectEpoch, ObjectOwnership.State state) {
        return new ObjectOwnership(source.objectId(), source.kind(), key, source.incarnation(), owner, topology, regionEpoch, objectEpoch, state);
    }
}
