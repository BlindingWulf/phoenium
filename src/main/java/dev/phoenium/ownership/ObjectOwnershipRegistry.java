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

    public ObjectOwnershipRegistry(RegionDirectory directory) { this.directory = Objects.requireNonNull(directory, "directory"); }

    public ObjectOwnership admit(String objectId, OwnedObjectKind kind, RoutingKey key) {
        metadataGate.lock();
        try {
            if (entries.containsKey(objectId)) throw new IllegalStateException("duplicate object ownership " + objectId);
            RegionRoute route = route(key);
            ObjectOwnership binding = new ObjectOwnership(objectId, kind, key, route.generation(), route.ownerEpoch(), new OwnerEpoch(0), ObjectOwnership.State.OWNED);
            entries.put(objectId, binding);
            return binding;
        } finally { metadataGate.unlock(); }
    }

    /** Movement uses an explicit transitional state and always invalidates old object envelopes. */
    public ObjectOwnership move(String objectId, RoutingKey destination) {
        metadataGate.lock();
        try {
            ObjectOwnership source = requiredLive(objectId);
            RegionRoute target = route(destination);
            ObjectOwnership prepared = with(source, source.routingKey(), source.topologyGeneration(), source.regionOwnerEpoch(), source.objectEpoch(), ObjectOwnership.State.SOURCE_PREPARED);
            entries.put(objectId, prepared);
            ObjectOwnership reserved = with(prepared, destination, target.generation(), target.ownerEpoch(), prepared.objectEpoch(), ObjectOwnership.State.DESTINATION_RESERVED);
            entries.put(objectId, reserved);
            ObjectOwnership committed = with(reserved, destination, target.generation(), target.ownerEpoch(), reserved.objectEpoch().next(), ObjectOwnership.State.OWNED);
            entries.put(objectId, committed);
            return committed;
        } finally { metadataGate.unlock(); }
    }

    public void remove(String objectId) {
        metadataGate.lock();
        try {
            ObjectOwnership current = requiredLive(objectId);
            entries.put(objectId, with(current, current.routingKey(), current.topologyGeneration(), current.regionOwnerEpoch(), current.objectEpoch().next(), ObjectOwnership.State.REMOVED));
        } finally { metadataGate.unlock(); }
    }

    /** Re-resolves current directory state; a stale derived record is never usable as authority. */
    public Optional<ObjectOwnership> current(String objectId) {
        metadataGate.lock();
        try {
            ObjectOwnership binding = entries.get(objectId);
            if (binding == null || binding.state() != ObjectOwnership.State.OWNED) return Optional.empty();
            RegionRoute route = route(binding.routingKey());
            if (!binding.topologyGeneration().equals(route.generation()) || !binding.regionOwnerEpoch().equals(route.ownerEpoch())) {
                binding = with(binding, binding.routingKey(), route.generation(), route.ownerEpoch(), binding.objectEpoch().next(), ObjectOwnership.State.OWNED);
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
        if (!route.region().equals(executingRegion)) throw new IllegalStateException("foreign object mutation: " + objectId + " is owned by " + route.region());
        OwnershipContext.Active active = OwnershipContext.current().orElseThrow(() -> new IllegalStateException("object mutation without ownership context"));
        if (!active.region().equals(executingRegion)) throw new IllegalStateException("object mutation context differs from executing region");
        return binding;
    }

    public int liveCount() { return (int) entries.values().stream().filter(value -> value.state() == ObjectOwnership.State.OWNED).count(); }
    public int playerCount() { return (int) entries.values().stream().filter(value -> value.kind() == OwnedObjectKind.PLAYER && value.state() == ObjectOwnership.State.OWNED).count(); }
    public java.util.List<ObjectOwnership> ownedInWorld(dev.phoenium.core.WorldId world) {
        return entries.values().stream().filter(value -> value.state() == ObjectOwnership.State.OWNED && value.routingKey().world().equals(world)).toList();
    }

    private RegionRoute route(RoutingKey key) { return directory.resolve(key).orElseThrow(() -> new IllegalArgumentException("unknown ownership key " + key)); }
    private ObjectOwnership requiredLive(String objectId) { return current(objectId).orElseThrow(() -> new IllegalStateException("object is absent, removed, or transitioning: " + objectId)); }
    private static ObjectOwnership with(ObjectOwnership source, RoutingKey key, dev.phoenium.core.TopologyGeneration topology, OwnerEpoch regionEpoch, OwnerEpoch objectEpoch, ObjectOwnership.State state) {
        return new ObjectOwnership(source.objectId(), source.kind(), key, topology, regionEpoch, objectEpoch, state);
    }
}
