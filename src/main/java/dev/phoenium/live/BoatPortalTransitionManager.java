package dev.phoenium.live;

import dev.phoenium.chunk.FullEntityFacts;
import dev.phoenium.mixin.PearlPortalProcessorAccess;
import dev.phoenium.ownership.ObjectOwnership;
import dev.phoenium.ownership.ObjectOwnershipRegistry;
import dev.phoenium.ownership.OwnershipContext;
import dev.phoenium.service.RegionalServiceKind;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.PortalProcessor;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.animal.pig.Pig;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.entity.vehicle.boat.Boat;
import net.minecraft.world.entity.vehicle.boat.ChestBoat;
import net.minecraft.world.entity.vehicle.boat.ChestRaft;
import net.minecraft.world.entity.vehicle.boat.Raft;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

/** Retains the exact Boat/Raft tick at its inherited Entity.handlePortal lookup. */
public final class BoatPortalTransitionManager implements AutoCloseable {
    private static final Set<EntityType<?>> WOOD_BOATS = Set.<EntityType<?>>of(
        EntityTypes.ACACIA_BOAT, EntityTypes.BIRCH_BOAT, EntityTypes.CHERRY_BOAT,
        EntityTypes.DARK_OAK_BOAT, EntityTypes.JUNGLE_BOAT, EntityTypes.MANGROVE_BOAT,
        EntityTypes.OAK_BOAT, EntityTypes.PALE_OAK_BOAT, EntityTypes.SPRUCE_BOAT);
    private static final Set<EntityType<?>> WOOD_CHEST_BOATS = Set.<EntityType<?>>of(
        EntityTypes.ACACIA_CHEST_BOAT, EntityTypes.BIRCH_CHEST_BOAT, EntityTypes.CHERRY_CHEST_BOAT,
        EntityTypes.DARK_OAK_CHEST_BOAT, EntityTypes.JUNGLE_CHEST_BOAT, EntityTypes.MANGROVE_CHEST_BOAT,
        EntityTypes.OAK_CHEST_BOAT, EntityTypes.PALE_OAK_CHEST_BOAT, EntityTypes.SPRUCE_CHEST_BOAT);

    private final NativePortalTransfer.Authority authority;
    private final ObjectOwnershipRegistry objects;
    private final CrossDimensionReplacementBoundary replacements = new CrossDimensionReplacementBoundary();
    private final Map<Key, Entry> active = new ConcurrentHashMap<>();
    private final Map<Key, Entry> graphPending = new ConcurrentHashMap<>();
    private final ThreadLocal<AbstractBoat> ticking = new ThreadLocal<>();
    private volatile boolean closed;

    public BoatPortalTransitionManager(NativePortalTransfer.Authority authority,
            ObjectOwnershipRegistry objects) {
        this.authority = Objects.requireNonNull(authority);
        this.objects = Objects.requireNonNull(objects);
    }

    private static boolean supported(AbstractBoat boat) {
        Class<?> concrete = boat.getClass();
        EntityType<?> type = boat.getType();
        return concrete == Boat.class && WOOD_BOATS.contains(type)
            || concrete == Raft.class && type == EntityTypes.BAMBOO_RAFT
            || concrete == ChestBoat.class && WOOD_CHEST_BOATS.contains(type)
            || concrete == ChestRaft.class && type == EntityTypes.BAMBOO_CHEST_RAFT;
    }

    public void begin(AbstractBoat boat) {
        if (closed || !authority.running() || !supported(boat)) return;
        if (ticking.get() != null) throw new IllegalStateException("nested boat portal tick");
        ticking.set(boat);
    }

    public boolean suspendDestination(AbstractBoat boat, PortalProcessor processor) {
        if (closed || !authority.running() || ticking.get() != boat || boat.isRemoved()
                || !supported(boat) || !(boat.level() instanceof ServerLevel level)
                || processor == null || boat.portalProcess != processor) return false;
        Portal portal = ((PearlPortalProcessorAccess) processor).phoenium$portal();
        if (!(portal instanceof NetherPortalBlock)) return false;
        if (boat.isPassenger())
            throw new IllegalStateException("boat passenger cannot be a native portal root");
        PortalPassengerGraphSnapshot graph = null;
        if (boat.isVehicle()) {
            if (boat.getClass() != Boat.class || boat.getType() != EntityTypes.OAK_BOAT)
                throw new IllegalStateException("boat portal passenger graph type is not bound");
            graph = PortalPassengerGraphSnapshot.capture(boat, objects);
            if (graph.preorder().size() != 2 || graph.member(boat).children().size() != 1
                    || graph.member(graph.member(boat).children().get(0)).children().size() != 0
                    || graph.member(boat).children().get(0).getClass() != Pig.class)
                throw new IllegalStateException("boat portal graph requires one exact pig passenger");
        }
        var context = OwnershipContext.current().orElseThrow(
            () -> new IllegalStateException("boat portal cut lacks source authority"));
        ObjectOwnership binding = objects.current(boat.getUUID().toString()).orElseThrow();
        objects.requireCurrentOwner(binding.objectId(), binding.objectEpoch(), context.region());
        var token = replacements.begin(new CrossDimensionReplacementBoundary.SourceIdentity(
            binding.objectId(), ((FullEntityFacts.Access) boat).phoenium$entityIncarnation(),
            binding.incarnation()));
        Entry entry = new Entry(boat, level, processor, binding, token, graph);
        entry.graphTokens.put(boat, token);
        try {
            if (graph != null) {
                for (Entity member : graph.preorder()) {
                    if (member == boat) continue;
                    var captured = graph.member(member);
                    var childToken = replacements.begin(new CrossDimensionReplacementBoundary.SourceIdentity(
                        captured.uuid().toString(), captured.nativeIncarnation(),
                        captured.binding().incarnation()));
                    entry.graphTokens.put(member, childToken);
                    NativePortalTransfer.retained(member, captured.binding(), childToken,
                        null, "CAPTURED_GRAPH_PASSENGER");
                }
            }
            if (active.putIfAbsent(new Key(boat), entry) != null)
                throw new IllegalStateException("boat portal captured twice");
            if (graph != null) {
                for (Entity member : graph.preorder())
                    if (graphPending.putIfAbsent(new Key(member), entry) != null)
                        throw new IllegalStateException("boat portal graph member already pending");
            }
            NativePortalTransfer.retained(boat, binding, token, null, "CAPTURED_BOAT_TICK");
            return true;
        } catch (RuntimeException | Error failure) {
            active.remove(new Key(boat), entry);
            releaseGraphMembers(entry);
            for (var captured : entry.graphTokens.values()) replacements.cancel(captured);
            throw failure;
        }
    }

    public boolean suspended(AbstractBoat boat) { return active.containsKey(new Key(boat)); }

    /** Same pinned ServerLevel root/passenger tick fence used by captured mob graphs. */
    public boolean graphTickSuspended(Entity entity, boolean passenger) {
        Entry entry = graphPending.get(new Key(entity));
        return entry != null && entry.retained && !entry.consumed
            && active.get(new Key(entry.boat)) == entry && (entry.boat != entity) == passenger;
    }

    public boolean complete(AbstractBoat boat) {
        Entry entry = active.get(new Key(boat));
        if (entry == null || ticking.get() != boat) return false;
        if (entry.retained) throw new IllegalStateException("boat tail captured twice");
        entry.retained = true;
        return true;
    }

    public void finish(AbstractBoat boat) {
        Entry entry = active.get(new Key(boat));
        if (entry != null && !entry.retained) cancel(entry, "UNRETAINED_PREFIX");
        if (ticking.get() == boat) ticking.remove();
    }

    public int advance() {
        if (OwnershipContext.current().isPresent())
            throw new IllegalStateException("boat coordinator retained source owner");
        if (closed || !authority.running()) return 0;
        List<Entry> entries = new ArrayList<>(active.values());
        entries.sort(Comparator.comparingLong(entry -> entry.token.generation()));
        int settled = 0;
        for (Entry entry : entries) {
            if (!entry.retained || entry.advancing || active.get(new Key(entry.boat)) != entry) continue;
            if (!current(entry)) { cancel(entry, "STALE_BEFORE_NATIVE"); continue; }
            entry.advancing = true;
            try {
                if (!entry.destinationPrepared) {
                    NativePortalTransfer.retained(entry.boat, entry.binding, entry.token, null,
                        "DESTINATION_PREPARING");
                    entry.transition = PortalDestinationObservation.prepare(entry.boat, entry.binding,
                        entry.token, authority, () -> current(entry),
                        () -> entry.processor.getPortalDestination(entry.level, entry.boat));
                    if (!current(entry)) { cancel(entry, "STALE_AFTER_PREPARATION"); continue; }
                    replacements.prepared(entry.token);
                    entry.destinationPrepared = true;
                }
                boolean permitted = entry.transition != null
                    && entry.level.isAllowedToEnterPortal(entry.transition.newLevel())
                    && (entry.level.dimension() == entry.transition.newLevel().dimension()
                        || entry.boat.canTeleport(entry.level, entry.transition.newLevel()));
                if (permitted && entry.level.dimension() == entry.transition.newLevel().dimension())
                    throw new IllegalStateException("same-dimension boat portal needs a separate continuation");
                if (permitted && entry.graph == null) {
                    if (entry.visibilityReady == null)
                        entry.visibilityReady = authority.demandDestinationEntityVisibility(
                            entry.boat, entry.transition);
                    if (!entry.visibilityReady.getAsBoolean()) {
                        if (++entry.visibilityWaits > 200)
                            throw new IllegalStateException("boat destination visibility deadline");
                        continue;
                    }
                }
                NativePortalTransfer transfer = null;
                NativePortalGraphTransferPlan graphPlan = null;
                if (permitted && entry.graph != null) {
                    if (entry.graphPlan == null)
                        entry.graphPlan = new NativePortalGraphTransferPlan(authority, objects,
                            replacements, entry.graph, entry.graphTokens, entry.transition,
                            () -> active.get(new Key(entry.boat)) == entry && authority.running()
                                && entry.graphPlan != null && entry.graphPlan.visibilityCurrent());
                    graphPlan = entry.graphPlan;
                    if (!graphPlan.ready()) {
                        if (++entry.visibilityWaits > 200)
                            throw new IllegalStateException("boat graph destination visibility deadline");
                        continue;
                    }
                    authority.execute(entry.binding.routingKey(), RegionalServiceKind.ENTITY_PORTAL_SOURCE,
                        () -> entry.graph.validateIntact(objects));
                } else if (permitted) {
                    transfer = new NativePortalTransfer(authority, objects, replacements, entry.token,
                        entry.boat, entry.binding, entry.transition,
                        () -> active.get(new Key(entry.boat)) == entry
                            && entry.visibilityReady.getAsBoolean());
                }
                Entity result = graphPlan != null ? graphPlan.invoke()
                    : transfer == null ? null : transfer.run();
                var suffix = result == null
                    ? replacements.authorizeUnchangedSourceContinuation(entry.token, entry.token.source())
                    : replacements.authorizeReplacementContinuation(entry.token);
                authority.execute(entry.binding.routingKey(), RegionalServiceKind.ENTITY_PORTAL_SOURCE, () -> {
                    if (active.get(new Key(entry.boat)) != entry || entry.consumed
                            || !authority.worldActive(entry.level)
                            || ((FullEntityFacts.Access) entry.boat).phoenium$entityIncarnation()
                                != entry.token.source().nativeIncarnation()
                            || result == null && !current(entry)
                            || result != null && entry.boat.getRemovalReason()
                                != Entity.RemovalReason.CHANGED_DIMENSION)
                        throw new IllegalStateException("stale boat native tick suffix");
                    entry.consumed = true;
                    ((PearlPortalNativeAccess.EntitySuffix) entry.boat)
                        .phoenium$resumePearlPortalBaseTickSuffix();
                    ((BoatPortalNativeAccess) entry.boat).phoenium$resumeBoatTickSuffix();
                });
                replacements.settle(suffix);
                if (graphPlan != null) {
                    graphPlan.settleChildren();
                    graphPlan.settleRoot(result);
                } else {
                    if (entry.graph != null) settleUnchangedChildren(entry);
                    if (transfer != null) transfer.settled();
                    else NativePortalTransfer.retained(entry.boat, entry.binding, entry.token,
                        null, "SETTLED_NATIVE_NO_TRANSFER");
                }
                active.remove(new Key(entry.boat), entry);
                releaseGraphMembers(entry);
                settled++;
            } catch (RuntimeException | Error failure) {
                try { cancel(entry, "FAILED:" + failure.getClass().getSimpleName()); }
                catch (RuntimeException | Error cleanupFailure) { failure.addSuppressed(cleanupFailure); }
                throw failure;
            } finally { entry.advancing = false; }
        }
        return settled;
    }

    private boolean current(Entry entry) {
        ObjectOwnership current = objects.current(entry.token.source().objectId()).orElse(null);
        return replacements.isCurrent(entry.token) && authority.worldActive(entry.level)
            && (entry.transition == null || authority.worldActive(entry.transition.newLevel()))
            && !entry.boat.isRemoved() && entry.boat.level() == entry.level
            && !entry.boat.isPassenger() && (entry.graph != null || !entry.boat.isVehicle())
            && entry.boat.portalProcess == entry.processor
            && entry.boat.position().equals(entry.position)
            && entry.boat.getDeltaMovement().equals(entry.motion)
            && entry.boat.getYRot() == entry.yRot && entry.boat.getXRot() == entry.xRot
            && entry.boat.getPose() == entry.pose
            && ((FullEntityFacts.Access) entry.boat).phoenium$entityIncarnation()
                == entry.token.source().nativeIncarnation()
            && entry.boat.getUUID().toString().equals(entry.token.source().objectId())
            && entry.processor.getEntryPosition().equals(entry.entryPosition)
            && ((PearlPortalProcessorAccess) entry.processor).phoenium$portal() == entry.portal
            && entry.processor.getPortalTime() == entry.portalTime
            && entry.processor.isInsidePortalThisTick() == entry.insidePortal
            && current != null && current.incarnation() == entry.binding.incarnation()
            && current.objectEpoch().equals(entry.binding.objectEpoch())
            && current.routingKey().equals(entry.binding.routingKey());
    }

    public boolean onRemoval(Entity entity, Entity.RemovalReason reason) {
        if (!(entity instanceof AbstractBoat boat) || !supported(boat)) return false;
        Entry entry = active.get(new Key(boat));
        if (entry == null) return false;
        if (entry.consumed && boat.getRemovalReason() == Entity.RemovalReason.CHANGED_DIMENSION)
            return true;
        cancel(entry, "EXTERNAL_REMOVAL:" + reason);
        return false;
    }

    public void worldClosing(ServerLevel level) {
        for (Entry entry : List.copyOf(active.values()))
            if (entry.level == level || entry.transition != null && entry.transition.newLevel() == level)
                cancel(entry, "WORLD_CLOSED");
    }

    private void cancel(Entry entry, String reason) {
        IllegalStateException partial = null;
        if (entry.graphPlan != null) {
            try { entry.graphPlan.restoreUncommittedSourceGraph(); }
            catch (RuntimeException | Error repairFailure) {
                partial = new IllegalStateException(
                    "PARTIAL_PORTAL_GRAPH: exact source graph was not restored", repairFailure);
            }
        }
        NativePortalTransfer.retained(entry.boat, entry.binding, entry.token,
            entry.transition == null ? null : entry.transition.newLevel(), reason);
        if (entry.graphPlan != null) entry.graphPlan.cancelUnsettled();
        else if (entry.graph != null) {
            for (var member : entry.graphTokens.entrySet()) {
                if (member.getKey() == entry.boat) continue;
                if (replacements.cancel(member.getValue()))
                    NativePortalTransfer.retained(member.getKey(),
                        entry.graph.member(member.getKey()).binding(), member.getValue(),
                        entry.transition == null ? null : entry.transition.newLevel(), reason);
            }
        }
        replacements.cancel(entry.token);
        active.remove(new Key(entry.boat), entry);
        releaseGraphMembers(entry);
        if (partial != null) throw partial;
    }

    private void settleUnchangedChildren(Entry entry) {
        for (Entity member : entry.graph.postorder()) {
            if (member == entry.boat) continue;
            var childToken = entry.graphTokens.get(member);
            if (replacements.phase(childToken) == CrossDimensionReplacementBoundary.Phase.CAPTURED)
                replacements.prepared(childToken);
            var continuation = replacements.authorizeUnchangedSourceContinuation(
                childToken, childToken.source());
            replacements.settle(continuation);
            NativePortalTransfer.retained(member, entry.graph.member(member).binding(),
                childToken, entry.transition == null ? null : entry.transition.newLevel(),
                "SETTLED_NATIVE_NO_TRANSFER");
        }
    }

    private void releaseGraphMembers(Entry entry) {
        if (entry.graph == null) return;
        for (Entity member : entry.graph.preorder())
            graphPending.remove(new Key(member), entry);
    }

    public int pendingCount() { return active.size(); }

    @Override public void close() {
        closed = true;
        for (Entry entry : List.copyOf(active.values())) cancel(entry, "SHUTDOWN");
        replacements.close();
        ticking.remove();
    }

    private record Key(Entity entity) {
        @Override public int hashCode() { return System.identityHashCode(entity); }
        @Override public boolean equals(Object other) {
            return other instanceof Key key && key.entity == entity;
        }
    }

    private static final class Entry {
        final AbstractBoat boat;
        final ServerLevel level;
        final PortalProcessor processor;
        final ObjectOwnership binding;
        final CrossDimensionReplacementBoundary.Token token;
        final PortalPassengerGraphSnapshot graph;
        final IdentityHashMap<Entity, CrossDimensionReplacementBoundary.Token> graphTokens = new IdentityHashMap<>();
        final Portal portal;
        final BlockPos entryPosition;
        final int portalTime;
        final boolean insidePortal;
        final Vec3 position, motion;
        final float yRot, xRot;
        final Pose pose;
        volatile boolean retained, consumed, advancing, destinationPrepared;
        int visibilityWaits;
        BooleanSupplier visibilityReady;
        TeleportTransition transition;
        NativePortalGraphTransferPlan graphPlan;

        Entry(AbstractBoat boat, ServerLevel level, PortalProcessor processor,
                ObjectOwnership binding, CrossDimensionReplacementBoundary.Token token,
                PortalPassengerGraphSnapshot graph) {
            this.boat = boat;
            this.level = level;
            this.processor = processor;
            this.binding = binding;
            this.token = token;
            this.graph = graph;
            this.portal = ((PearlPortalProcessorAccess) processor).phoenium$portal();
            this.entryPosition = processor.getEntryPosition().immutable();
            this.portalTime = processor.getPortalTime();
            this.insidePortal = processor.isInsidePortalThisTick();
            this.position = boat.position();
            this.motion = boat.getDeltaMovement();
            this.yRot = boat.getYRot();
            this.xRot = boat.getXRot();
            this.pose = boat.getPose();
        }
    }
}
