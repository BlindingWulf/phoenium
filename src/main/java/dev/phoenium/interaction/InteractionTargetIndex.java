package dev.phoenium.interaction;

import dev.phoenium.core.WorldId;
import dev.phoenium.ownership.ObjectOwnership;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A lock-free derived projection from a world-scoped network entity id to the current object
 * incarnation.  The authoritative registry and directory are deliberately rechecked by the
 * admission ticket; this map cannot make a stale reference current.
 */
public final class InteractionTargetIndex {
    private final Map<Key, InteractionTargetRef> current = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public InteractionTargetRef publish(WorldId world, int entityId, ObjectOwnership object) {
        if (closed) throw new IllegalStateException("interaction index lifetime has ended");
        InteractionTargetRef ref = new InteractionTargetRef(world, entityId, object.objectId(), object.incarnation(),
            object.routingKey(), object.owner(), object.topologyGeneration(), object.objectEpoch());
        current.put(new Key(world, entityId), ref);
        return ref;
    }
    public Optional<InteractionTargetRef> resolve(WorldId world, int entityId) {
        return Optional.ofNullable(current.get(new Key(world, entityId)));
    }
    /** Packet ingress uses this fail-closed form; absence never invites a vanilla world lookup. */
    public InteractionTargetRef require(WorldId world, int entityId) {
        InteractionTargetRef ref = current.get(new Key(world, entityId));
        if (ref == null) throw new UnknownInteractionTargetException(world, entityId);
        return ref;
    }
    public void invalidate(WorldId world, int entityId, String objectId, long incarnation) {
        current.computeIfPresent(new Key(world, entityId), (ignored, ref) ->
            ref.objectId().equals(objectId) && ref.incarnation() == incarnation ? null : ref);
    }
    public boolean isCurrent(InteractionTargetRef ref) {
        return ref.equals(current.get(new Key(ref.world(), ref.networkEntityId())));
    }
    public void close() { closed = true; current.clear(); }
    private record Key(WorldId world, int entityId) { }
    public static final class UnknownInteractionTargetException extends IllegalStateException {
        public UnknownInteractionTargetException(WorldId world, int entityId) {
            super("unknown interaction target " + entityId + " in " + world.value());
        }
    }
}
