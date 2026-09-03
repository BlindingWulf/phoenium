package dev.phoenium.live;

import dev.phoenium.core.WorldId;
import dev.phoenium.interaction.InteractionTargetIndex;
import dev.phoenium.ownership.ObjectOwnership;
import dev.phoenium.ownership.ObjectOwnershipRegistry;
import dev.phoenium.ownership.OwnedObjectKind;
import dev.phoenium.topology.RegionDirectory;
import dev.phoenium.topology.RegionRoute;
import dev.phoenium.topology.RoutingKey;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The real-Minecraft lifecycle adapter's derived state only.  Its callers receive an entity
 * identity directly from vanilla add/move/remove callbacks; it never consults a ServerLevel entity
 * lookup to populate or invalidate the interaction index.
 */
public final class EntityIndexLifecycleAdapter {
    private final ObjectOwnershipRegistry objects;
    private final RegionDirectory directory;
    private final InteractionTargetIndex index;
    private final Map<String, Integer> entityIds = new ConcurrentHashMap<>();

    public EntityIndexLifecycleAdapter(ObjectOwnershipRegistry objects, RegionDirectory directory, InteractionTargetIndex index) {
        this.objects = Objects.requireNonNull(objects, "objects"); this.directory = Objects.requireNonNull(directory, "directory");
        this.index = Objects.requireNonNull(index, "index");
    }
    public ObjectOwnership addressable(String objectId, OwnedObjectKind kind, WorldId world, int entityId,
                                       RoutingKey key, boolean requireExplicitRoute) {
        if (requireExplicitRoute && !directory.snapshot().routes().containsKey(key)) {
            throw new IllegalStateException("addressable entity lacks explicit topology route " + key);
        }
        ObjectOwnership binding = objects.current(objectId).orElseGet(() -> objects.admit(objectId, kind, key));
        if (!binding.routingKey().equals(key)) binding = objects.updateRoutingKey(objectId, key, binding.objectEpoch());
        index.publish(world, entityId, binding);
        entityIds.put(objectId, entityId);
        return binding;
    }
    /** Cross-owner movement invalidates rather than assigning an owner or retaining a stale candidate. */
    public void routingObserved(String objectId, WorldId world, int entityId, RoutingKey key) {
        ObjectOwnership current = objects.current(objectId).orElse(null);
        if (current == null) return;
        RegionRoute route = directory.resolve(key).orElse(null);
        if (route == null || !route.region().equals(current.owner())) {
            index.invalidate(world, entityId, current.objectId(), current.incarnation());
            return;
        }
        ObjectOwnership updated = current.routingKey().equals(key) ? current
            : objects.updateRoutingKey(current.objectId(), key, current.objectEpoch());
        index.publish(world, entityId, updated);
        entityIds.put(updated.objectId(), entityId);
    }
    public void removed(String objectId, WorldId world, int entityId) {
        ObjectOwnership current = objects.current(objectId).orElse(null);
        if (current == null) return;
        index.invalidate(world, entityId, current.objectId(), current.incarnation());
        entityIds.remove(current.objectId(), entityId);
        objects.remove(current.objectId());
    }
    public Integer entityId(String objectId) { return entityIds.get(objectId); }
}
