package dev.phoenium.live;

import dev.phoenium.core.WorldId;
import dev.phoenium.interaction.InteractionTargetIndex;
import dev.phoenium.ownership.ObjectOwnership;
import dev.phoenium.ownership.ObjectOwnershipRegistry;
import dev.phoenium.ownership.OwnedObjectKind;
import dev.phoenium.ownership.OwnershipContext;
import dev.phoenium.ownership.RegionLeaseManager;
import dev.phoenium.region.RegionId;
import dev.phoenium.region.RegionLifecycle;
import dev.phoenium.region.RegionRuntime;
import dev.phoenium.topology.RegionDirectory;
import dev.phoenium.topology.RegionRoute;
import dev.phoenium.topology.RoutingKey;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

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
    /** Latest physical cross-owner observation per non-player object; bounded by live object count. */
    private final Map<String, PendingRelocation> pendingRelocations = new ConcurrentHashMap<>();
    private final AtomicLong relocationSequence = new AtomicLong();
    private final Consumer<RelocationFact> relocationSink;

    public EntityIndexLifecycleAdapter(ObjectOwnershipRegistry objects, RegionDirectory directory, InteractionTargetIndex index) {
        this(objects, directory, index, ignored -> { });
    }
    public EntityIndexLifecycleAdapter(ObjectOwnershipRegistry objects, RegionDirectory directory, InteractionTargetIndex index,
                                       Consumer<RelocationFact> relocationSink) {
        this.objects = Objects.requireNonNull(objects, "objects"); this.directory = Objects.requireNonNull(directory, "directory");
        this.index = Objects.requireNonNull(index, "index"); this.relocationSink = Objects.requireNonNull(relocationSink, "relocationSink");
    }
    public ObjectOwnership addressable(String objectId, OwnedObjectKind kind, WorldId world, int entityId,
                                       RoutingKey key, boolean requireExplicitRoute) {
        if (requireExplicitRoute && !directory.snapshot().routes().containsKey(key)) {
            throw new IllegalStateException("addressable entity lacks explicit topology route " + key);
        }
        ObjectOwnership binding = objects.current(objectId).orElseGet(() -> objects.admit(objectId, kind, key));
        PendingRelocation pending = pendingRelocations.get(objectId);
        // A player can be observed in its destination level before the owner-free relocation cut
        // commits the cross-world owner transition. Preserve that bounded pending fact instead of
        // attempting an unsafe routing-key update across RegionId worlds. The next packet is then
        // rejected by the world-fenced movement ticket until the cut has published the destination.
        if (pending != null && pending.kind() == kind && pending.world().equals(world)
                && pending.entityId() == entityId
                && (pending.destination().equals(key) || kind == OwnedObjectKind.PLAYER)
                && pending.observedObjectEpoch().equals(binding.objectEpoch())) {
            entityIds.put(objectId, entityId);
            return binding;
        }
        pendingRelocations.remove(objectId);
        if (!binding.routingKey().equals(key)) binding = objects.updateRoutingKey(objectId, key, binding.objectEpoch());
        index.publish(world, entityId, binding);
        entityIds.put(objectId, entityId);
        return binding;
    }
    /**
     * Movement observation is metadata-only. Same-owner routing may publish immediately; a cross-owner
     * observation invalidates the derived interaction candidate and retains only the latest immutable
     * fact for an owner-free quiescent commit. Player packet movement keeps its separate
     * classified-footprint protocol and is deliberately not admitted through this relocation path;
     * this path is used for a player's native cross-world level change.
     */
    public void routingObserved(String objectId, WorldId world, int entityId, RoutingKey key) {
        ObjectOwnership current = objects.current(objectId).orElse(null);
        if (current == null) { pendingRelocations.remove(objectId); return; }
        RegionRoute route = directory.resolve(key).orElse(null);
        if (route == null) {
            index.invalidate(world, entityId, current.objectId(), current.incarnation());
            // A physical non-player can enter a key while topology publication is temporarily
            // incomplete. Keep the latest immutable observation so a later published route can
            // settle authority without requiring the entity to move a second time.
            if (current.kind() == OwnedObjectKind.ENTITY || !current.routingKey().world().equals(world)) {
                pendingRelocations.put(objectId, new PendingRelocation(objectId, current.kind(), world, entityId, key,
                    current.objectEpoch(), current.incarnation(), relocationSequence.incrementAndGet()));
            } else pendingRelocations.remove(objectId);
            return;
        }
        if (!route.region().equals(current.owner())) {
            index.invalidate(world, entityId, current.objectId(), current.incarnation());
            if (current.kind() == OwnedObjectKind.ENTITY || !current.routingKey().world().equals(world)) {
                pendingRelocations.put(objectId, new PendingRelocation(objectId, current.kind(), world, entityId, key,
                    current.objectEpoch(), current.incarnation(), relocationSequence.incrementAndGet()));
            } else pendingRelocations.remove(objectId);
            return;
        }
        // Any newer same-owner observation supersedes an older foreign destination.
        pendingRelocations.remove(objectId);
        ObjectOwnership updated = current.routingKey().equals(key) ? current
            : objects.updateRoutingKey(current.objectId(), key, current.objectEpoch());
        index.publish(world, entityId, updated);
        entityIds.put(updated.objectId(), entityId);
    }

    /**
     * Attempts pending non-player relocation at the server coordinator's owner-free boundary, after
     * all owner callbacks for this phase have returned. Source and destination are
     * fenced with non-blocking delivery selections in deterministic RegionId order. If either side is
     * busy or transitioning, the fact remains pending for a later boundary; no owner waits on a peer.
     * A relocation sink failure is deliberately propagated after authority/index publication: the
     * pending fact is already retired, so the outer live-tick failure barrier stops simulation instead
     * of replaying the same owner transition. Returns the number of committed real owner changes.
     */
    public int advancePendingRelocations(Map<RegionId, RegionRuntime> runtimes) {
        Objects.requireNonNull(runtimes, "runtimes");
        if (OwnershipContext.current().isPresent()) throw new IllegalStateException("entity relocation boundary retained owner authority");
        int committed = 0;
        List<PendingRelocation> snapshot = pendingRelocations.values().stream()
            .sorted(Comparator.comparingLong(PendingRelocation::sequence).thenComparing(PendingRelocation::objectId)).toList();
        for (PendingRelocation pending : snapshot) {
            if (pendingRelocations.get(pending.objectId()) != pending) continue;
            ObjectOwnership current = objects.current(pending.objectId()).orElse(null);
            if (!matches(pending, current)) { pendingRelocations.remove(pending.objectId(), pending); continue; }
            RegionRoute target = directory.resolve(pending.destination()).orElse(null);
            if (target == null) continue;
            if (target.region().equals(current.owner())) {
                if (pendingRelocations.get(pending.objectId()) != pending) continue;
                ObjectOwnership refreshed = current.routingKey().equals(pending.destination()) ? current
                    : objects.updateRoutingKey(current.objectId(), pending.destination(), current.objectEpoch());
                pendingRelocations.remove(pending.objectId(), pending);
                index.publish(pending.world(), pending.entityId(), refreshed);
                entityIds.put(refreshed.objectId(), pending.entityId());
                continue;
            }
            try (RelocationReservation reservation = RelocationReservation.tryAcquire(runtimes, current.owner(), target.region())) {
                if (!reservation.acquired() || !reservation.stable()) continue;
                if (pendingRelocations.get(pending.objectId()) != pending) continue;
                ObjectOwnership rechecked = objects.current(pending.objectId()).orElse(null);
                RegionRoute recheckedTarget = directory.resolve(pending.destination()).orElse(null);
                if (!matches(pending, rechecked) || recheckedTarget == null) {
                    pendingRelocations.remove(pending.objectId(), pending); continue;
                }
                if (!rechecked.owner().equals(current.owner()) || !recheckedTarget.region().equals(target.region())) continue;
                if (recheckedTarget.region().equals(rechecked.owner())) {
                    ObjectOwnership refreshed = rechecked.routingKey().equals(pending.destination()) ? rechecked
                        : objects.updateRoutingKey(rechecked.objectId(), pending.destination(), rechecked.objectEpoch());
                    pendingRelocations.remove(pending.objectId(), pending);
                    index.publish(pending.world(), pending.entityId(), refreshed);
                    entityIds.put(refreshed.objectId(), pending.entityId());
                    continue;
                }
                if (!reservation.stable() || pendingRelocations.get(pending.objectId()) != pending) continue;
                ObjectOwnership moved = objects.transitionOwner(pending.objectId(), pending.destination(), pending.observedObjectEpoch());
                pendingRelocations.remove(pending.objectId(), pending);
                index.publish(pending.world(), pending.entityId(), moved);
                entityIds.put(moved.objectId(), pending.entityId());
                if (pending.kind() == OwnedObjectKind.ENTITY)
                    relocationSink.accept(new RelocationFact(rechecked.owner(), moved.owner(), moved.objectId(), moved.incarnation()));
                committed++;
            }
        }
        return committed;
    }

    private static boolean matches(PendingRelocation pending, ObjectOwnership current) {
        return current != null && current.kind() == pending.kind()
            && current.incarnation() == pending.incarnation()
            && current.objectEpoch().equals(pending.observedObjectEpoch());
    }

    public void removed(String objectId, WorldId world, int entityId) {
        pendingRelocations.remove(objectId);
        ObjectOwnership current = objects.current(objectId).orElse(null);
        entityIds.remove(objectId, entityId);
        if (current == null) return;
        index.invalidate(world, entityId, current.objectId(), current.incarnation());
        objects.remove(current.objectId());
    }
    public Integer entityId(String objectId) { return entityIds.get(objectId); }
    public int pendingRelocationCount() { return pendingRelocations.size(); }
    /** No native entities are mutated: these are the ended server's derived identity ledgers. */
    public void close() {
        objects.close();
        index.close();
        entityIds.clear();
        pendingRelocations.clear();
    }

    public record RelocationFact(RegionId oldRegion, RegionId newRegion, String objectId, long incarnation) {
        public RelocationFact {
            Objects.requireNonNull(oldRegion, "oldRegion"); Objects.requireNonNull(newRegion, "newRegion");
            Objects.requireNonNull(objectId, "objectId");
            if (oldRegion.equals(newRegion)) throw new IllegalArgumentException("relocation fact has no owner change");
            if (incarnation < 0) throw new IllegalArgumentException("negative relocation incarnation");
        }
    }
    private record PendingRelocation(String objectId, OwnedObjectKind kind, WorldId world, int entityId, RoutingKey destination,
                                     dev.phoenium.core.OwnerEpoch observedObjectEpoch, long incarnation, long sequence) { }

    /** Non-authoritative, non-blocking two-region publication fence. */
    private static final class RelocationReservation implements AutoCloseable {
        private final List<RegionLeaseManager.Selection> selections;
        private final List<RegionRuntime> runtimes;
        private RelocationReservation(List<RegionLeaseManager.Selection> selections, List<RegionRuntime> runtimes) {
            this.selections = selections; this.runtimes = runtimes;
        }
        static RelocationReservation tryAcquire(Map<RegionId, RegionRuntime> runtimes, RegionId first, RegionId second) {
            List<RegionId> ids = first.equals(second) ? List.of(first)
                : java.util.stream.Stream.of(first, second).sorted().toList();
            List<RegionLeaseManager.Selection> selections = new ArrayList<>(ids.size());
            List<RegionRuntime> selectedRuntimes = new ArrayList<>(ids.size());
            for (RegionId id : ids) {
                RegionRuntime runtime = runtimes.get(id);
                if (runtime == null || runtime.lifecycle() != RegionLifecycle.READY || runtime.transitioning()) {
                    closeReverse(selections); return new RelocationReservation(List.of(), List.of());
                }
                RegionLeaseManager.Selection selection = runtime.leases().trySelect().orElse(null);
                if (selection == null) { closeReverse(selections); return new RelocationReservation(List.of(), List.of()); }
                selections.add(selection);
                selectedRuntimes.add(runtime);
                if (runtime.transitioning()) { closeReverse(selections); return new RelocationReservation(List.of(), List.of()); }
            }
            return new RelocationReservation(selections, selectedRuntimes);
        }
        boolean acquired() { return !selections.isEmpty(); }
        boolean stable() {
            return runtimes.stream().allMatch(runtime -> runtime.lifecycle() == RegionLifecycle.READY && !runtime.transitioning());
        }
        @Override public void close() { closeReverse(selections); }
        private static void closeReverse(List<RegionLeaseManager.Selection> selections) {
            for (int i = selections.size() - 1; i >= 0; i--) selections.get(i).close();
        }
    }
}
