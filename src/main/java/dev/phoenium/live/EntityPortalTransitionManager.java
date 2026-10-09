package dev.phoenium.live;

import dev.phoenium.chunk.FullEntityFacts;
import dev.phoenium.mixin.PearlPortalProcessorAccess;
import dev.phoenium.ownership.*;
import dev.phoenium.service.RegionalServiceKind;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PortalProcessor;
import net.minecraft.world.level.block.EndPortalBlock;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.level.portal.TeleportTransition;

/**
 * Retains the portal destination lookup of every portal-capable non-living entity class that has no dedicated
 * manager (experience orbs, firework rockets, end crystals and the non-chest minecarts). The native tick is
 * unwound at the destination lookup and its reviewed tail (MobPortalOuterTails pins, plus the Entity.baseTick tail
 * when the class reaches handlePortal through Entity.tick) runs on the retired source after the native transfer,
 * exactly as the item manager does for ItemEntity. Without it the lookup runs inside the owner tick (installed run
 * probe1-1-w1: NATIVE_UNRETAINED, "regional service producer reached foreign owner").
 *
 * A vehicle of this family (hopper/furnace/command-block/spawner/TNT minecarts) carrying riders is captured as a native
 * passenger graph (or leaf player riders) exactly like the dedicated boat and minecart managers; the previous
 * IllegalStateException ("passenger graph is outside this continuation") stopped the whole server (headless graph census
 * gs-hopper_minecart_inventory_rider: scheduler=FAILED).
 */
public final class EntityPortalTransitionManager implements AutoCloseable {
    private final NativePortalTransfer.Authority authority;
    private final ObjectOwnershipRegistry objects;
    private final CrossDimensionReplacementBoundary replacements = new CrossDimensionReplacementBoundary();
    private final Map<IdentityKey, Entry> active = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<IdentityKey, Entry> graphPending = new java.util.concurrent.ConcurrentHashMap<>();
    private final ThreadLocal<Entity> ticking = new dev.phoenium.core.FastThreadLocal<>();
    private volatile boolean closed;

    public EntityPortalTransitionManager(NativePortalTransfer.Authority authority, ObjectOwnershipRegistry objects) {
        this.authority = Objects.requireNonNull(authority); this.objects = Objects.requireNonNull(objects);
    }

    /** Families with their own manager keep it; this manager never claims them. */
    static boolean managedElsewhere(Class<?> type) {
        return Mob.class.isAssignableFrom(type) || LivingEntity.class.isAssignableFrom(type)
            || net.minecraft.world.entity.item.ItemEntity.class.isAssignableFrom(type)
            || net.minecraft.world.entity.projectile.hurtingprojectile.AbstractHurtingProjectile.class.isAssignableFrom(type)
            || net.minecraft.world.entity.projectile.ShulkerBullet.class.isAssignableFrom(type)
            || net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl.class.isAssignableFrom(type)
            || net.minecraft.world.entity.projectile.ThrowableProjectile.class.isAssignableFrom(type)
            || net.minecraft.world.entity.projectile.arrow.AbstractArrow.class.isAssignableFrom(type)
            || net.minecraft.world.entity.item.FallingBlockEntity.class.isAssignableFrom(type)
            || net.minecraft.world.entity.item.PrimedTnt.class.isAssignableFrom(type)
            || net.minecraft.world.entity.vehicle.boat.AbstractBoat.class.isAssignableFrom(type)
            || type == net.minecraft.world.entity.vehicle.minecart.Minecart.class
            || type == net.minecraft.world.entity.vehicle.minecart.MinecartChest.class;
    }

    private static MobPortalTailPlans.Plan planOf(Class<?> type) {
        return managedElsewhere(type) ? null : MobPortalTailPlans.entityPlan(type);
    }

    private static final Map<Class<?>, Boolean> SUPPORT = new java.util.concurrent.ConcurrentHashMap<>();
    public static boolean supports(Entity entity) { return supportsClass(entity.getClass()); }
    public static boolean supportsClass(Class<?> type) { return SUPPORT.computeIfAbsent(type, t -> planOf(t) != null); }

    /** Scope of one native entity tick; mirrors the item manager's begin/complete/finish. */
    public void tick(Entity entity, Runnable nativeTick) {
        if (closed || !authority.running() || !supports(entity)) { nativeTick.run(); return; }
        if (suspended(entity)) return;
        if (ticking.get() != null) throw new IllegalStateException("nested entity portal tick");
        ticking.set(entity);
        try {
            nativeTick.run();
            Entry entry = active.get(key(entity));
            if (entry != null) entry.retained = true;
        } finally {
            Entry entry = active.get(key(entity));
            if (entry != null && !entry.retained) cancel(entry);
            ticking.set(null);
        }
    }

    public boolean suspendDestination(Entity entity, PortalProcessor processor) {
        if (ticking.get() != entity || closed || !authority.running() || entity.isRemoved()
                || !(entity.level() instanceof ServerLevel level) || entity.portalProcess != processor
                || processor == null || !supports(entity)) return false;
        Portal portal = ((PearlPortalProcessorAccess) processor).phoenium$portal();
        if (!(portal instanceof NetherPortalBlock) && !(portal instanceof EndPortalBlock)) return false;
        if (entity.isPassenger())
            throw new IllegalStateException("entity portal passenger cannot be a native portal root: " + entity.getType());
        // Leaf player riders travel natively inside the vehicle's transfer (see PortalRiders); any other rider shape is a
        // captured native graph exactly like the dedicated boat/minecart managers.
        List<net.minecraft.server.level.ServerPlayer> riders = entity.isVehicle() ? PortalRiders.leafPlayerRiders(entity) : List.of();
        PortalPassengerGraphSnapshot graph = entity.isVehicle() && riders.isEmpty()
            ? PortalPassengerGraphSnapshot.capture(entity, objects) : null;
        ObjectOwnership binding = objects.current(entity.getUUID().toString()).orElseThrow();
        objects.requireCurrentOwner(binding.objectId(), binding.objectEpoch(), OwnershipContext.current().orElseThrow().region());
        var token = replacements.begin(new CrossDimensionReplacementBoundary.SourceIdentity(binding.objectId(),
            ((FullEntityFacts.Access) entity).phoenium$entityIncarnation(), binding.incarnation()));
        Entry entry = new Entry(entity, level, processor, binding, token);
        entry.destinationPending = true;
        entry.riders = riders;
        entry.graph = graph;
        entry.graphTokens.put(entity, token);
        try {
            if (graph != null) {
                for (Entity member : graph.preorder()) {
                    if (member == entity) continue;
                    var captured = graph.member(member);
                    var childToken = replacements.begin(new CrossDimensionReplacementBoundary.SourceIdentity(
                        captured.uuid().toString(), captured.nativeIncarnation(), captured.binding().incarnation()));
                    entry.graphTokens.put(member, childToken);
                    NativePortalTransfer.retained(member, captured.binding(), childToken, null, "CAPTURED_GRAPH_PASSENGER");
                }
            }
            if (active.putIfAbsent(key(entity), entry) != null)
                throw new IllegalStateException("entity portal was captured twice");
            if (graph != null)
                for (Entity member : graph.preorder())
                    if (graphPending.putIfAbsent(key(member), entry) != null)
                        throw new IllegalStateException("entity portal graph member already pending");
        } catch (RuntimeException | Error failure) {
            active.remove(key(entity), entry);
            releaseGraphMembers(entry);
            for (var captured : entry.graphTokens.values()) replacements.cancel(captured);
            throw failure;
        }
        NativePortalTransfer.retained(entity, binding, token, null, "CAPTURED");
        return true;
    }

    /** Same pinned ServerLevel root/passenger tick fence used by captured mob, boat and minecart graphs. */
    public boolean graphTickSuspended(Entity entity, boolean passenger) {
        Entry entry = graphPending.get(key(entity));
        return entry != null && entry.retained && !entry.consumed
            && active.get(key(entry.entity)) == entry && (entry.entity != entity) == passenger;
    }

    private void releaseGraphMembers(Entry entry) {
        if (entry.graph == null) return;
        for (Entity member : entry.graph.preorder()) graphPending.remove(key(member), entry);
    }

    private void settleUnchangedChildren(Entry entry) {
        for (Entity member : entry.graph.postorder()) {
            if (member == entry.entity) continue;
            var childToken = entry.graphTokens.get(member);
            if (replacements.phase(childToken) == CrossDimensionReplacementBoundary.Phase.CAPTURED)
                replacements.prepared(childToken);
            var continuation = replacements.authorizeUnchangedSourceContinuation(childToken, childToken.source());
            replacements.settle(continuation);
            NativePortalTransfer.retained(member, entry.graph.member(member).binding(), childToken,
                entry.transition == null ? null : entry.transition.newLevel(), "SETTLED_NATIVE_NO_TRANSFER");
        }
    }

    public boolean suspended(Entity entity) { return active.containsKey(key(entity)); }

    /** S9-D11: {@link #advance()} would settle nothing now. */
    public boolean quiet() { return active.isEmpty(); }
    public int advance() {
        if (OwnershipContext.current().isPresent()) throw new IllegalStateException("entity portal coordinator retained source owner");
        if (closed || !authority.running() || active.isEmpty()) return 0; // S9-D11: nothing to settle
        List<Entry> entries = new ArrayList<>(active.values());
        entries.sort(Comparator.comparingLong(entry -> entry.token.generation()));
        int settled = 0;
        for (Entry entry : entries) {
            if (!entry.retained || entry.advancing || active.get(key(entry.entity)) != entry) continue;
            if (!current(entry)) { cancel(entry, "STALE_BEFORE_NATIVE"); continue; }
            entry.advancing = true;
            try {
                if (entry.destinationPending) {
                    NativePortalTransfer.retained(entry.entity, entry.binding, entry.token, null, "DESTINATION_PREPARING");
                    entry.transition = PortalDestinationObservation.prepare(entry.entity, entry.binding, entry.token,
                        authority, () -> current(entry),
                        () -> entry.processor.getPortalDestination(entry.level, entry.entity));
                    entry.destinationPending = false;
                    if (!current(entry)) { cancel(entry, "STALE_AFTER_PREPARATION"); continue; }
                    replacements.prepared(entry.token);
                    NativePortalTransfer.retained(entry.entity, entry.binding, entry.token,
                        entry.transition == null ? null : entry.transition.newLevel(), "DESTINATION_READY");
                }
                boolean permitted = entry.transition != null && entry.level.isAllowedToEnterPortal(entry.transition.newLevel())
                    && (entry.level.dimension() == entry.transition.newLevel().dimension()
                        || entry.entity.canTeleport(entry.level, entry.transition.newLevel()));
                if (permitted && entry.level.dimension() == entry.transition.newLevel().dimension())
                    throw new IllegalStateException("vanilla portal produced a same-dimension entity transition");
                if (permitted && entry.graph == null) {
                    if (entry.visibilityReady == null)
                        entry.visibilityReady = authority.demandDestinationEntityVisibility(entry.entity, entry.transition);
                    if (!entry.visibilityReady.getAsBoolean()) {
                        if (entry.visibilityDeadline.expired(authority.serverTick()))
                            throw new IllegalStateException("entity destination visibility deadline: " + entry.visibilityReady);
                        continue;
                    }
                }
                NativePortalTransfer transfer = null;
                NativePortalGraphTransferPlan graphPlan = null;
                if (permitted && entry.graph != null) {
                    if (entry.graphPlan == null)
                        entry.graphPlan = new NativePortalGraphTransferPlan(authority, objects, replacements,
                            entry.graph, entry.graphTokens, entry.transition,
                            () -> active.get(key(entry.entity)) == entry && authority.running()
                                && entry.graphPlan != null && entry.graphPlan.visibilityCurrent());
                    graphPlan = entry.graphPlan;
                    if (!graphPlan.ready()) {
                        if (entry.visibilityDeadline.expired(authority.serverTick()))
                            throw new IllegalStateException("entity graph destination visibility deadline");
                        continue;
                    }
                    authority.execute(entry.binding.routingKey(), RegionalServiceKind.ENTITY_PORTAL_SOURCE,
                        () -> entry.graph.validateIntact(objects));
                } else if (permitted) {
                    transfer = new NativePortalTransfer(authority, objects, replacements, entry.token,
                        entry.entity, entry.binding, entry.transition, () -> active.get(key(entry.entity)) == entry
                            && entry.visibilityReady.getAsBoolean());
                    if (!entry.riders.isEmpty()) transfer.bindPlayerRiders(entry.riders);
                }
                Entity result = graphPlan != null ? graphPlan.invoke() : transfer == null ? null : transfer.run();
                var suffix = result == null
                    ? replacements.authorizeUnchangedSourceContinuation(entry.token, entry.token.source())
                    : replacements.authorizeReplacementContinuation(entry.token);
                authority.execute(entry.binding.routingKey(), RegionalServiceKind.ENTITY_PORTAL_SOURCE, () -> {
                    if (active.get(key(entry.entity)) != entry || entry.consumed || !authority.worldActive(entry.level)
                            || ((FullEntityFacts.Access) entry.entity).phoenium$entityIncarnation() != entry.token.source().nativeIncarnation()
                            || result == null && !current(entry)
                            || result != null && entry.entity.getRemovalReason() != Entity.RemovalReason.CHANGED_DIMENSION)
                        throw new IllegalStateException("stale entity portal tick suffix");
                    entry.consumed = true;
                    var plan = MobPortalTailPlans.entityPlan(entry.entity.getClass());
                    if (plan.viaBaseTick())
                        ((PearlPortalNativeAccess.EntitySuffix) entry.entity).phoenium$resumePearlPortalBaseTickSuffix();
                    plan.resumeBase(entry.entity, 0);
                    plan.resumeTick(entry.entity);
                });
                replacements.settle(suffix);
                if (graphPlan != null) {
                    graphPlan.settleChildren();
                    graphPlan.settleRoot(result);
                } else {
                    if (entry.graph != null) settleUnchangedChildren(entry);
                    if (transfer != null) transfer.settled();
                    else NativePortalTransfer.retained(entry.entity, entry.binding, entry.token, null, "SETTLED_NATIVE_NO_TRANSFER");
                }
                active.remove(key(entry.entity), entry); releaseGraphMembers(entry); settled++;
            } catch (RuntimeException | Error failure) { cancel(entry, "FAILED:" + failure.getClass().getSimpleName()); throw failure; }
            finally { entry.advancing = false; }
        }
        return settled;
    }

    private boolean current(Entry entry) {
        ObjectOwnership current = objects.current(entry.token.source().objectId()).orElse(null);
        return replacements.isCurrent(entry.token) && authority.worldActive(entry.level)
            && (entry.transition == null || authority.worldActive(entry.transition.newLevel())) && !entry.entity.isRemoved()
            && entry.entity.level() == entry.level && entry.entity.portalProcess == entry.processor
            && (entry.riders.isEmpty() || entry.riders.equals(PortalRiders.leafPlayerRiders(entry.entity)))
            && ((FullEntityFacts.Access) entry.entity).phoenium$entityIncarnation() == entry.token.source().nativeIncarnation()
            && entry.entity.getUUID().toString().equals(entry.token.source().objectId())
            && (entry.processor == null || entry.processor.getEntryPosition().equals(entry.entryPosition)
                && ((PearlPortalProcessorAccess) entry.processor).phoenium$portal() == entry.portal
                && entry.processor.getPortalTime() == entry.portalTime
                && entry.processor.isInsidePortalThisTick() == entry.insidePortal)
            && current != null && current.incarnation() == entry.binding.incarnation()
            && current.objectEpoch().equals(entry.binding.objectEpoch()) && current.routingKey().equals(entry.binding.routingKey());
    }

    public boolean onRemoval(Entity entity, Entity.RemovalReason reason) {
        Entry entry = active.get(key(entity)); if (entry == null) return false;
        // Native tails can call discard on the already retired source; its replacement is a distinct incarnation.
        if (entry.consumed && entity.getRemovalReason() == Entity.RemovalReason.CHANGED_DIMENSION) return true;
        cancel(entry, "EXTERNAL_REMOVAL:" + reason); return false;
    }

    public void worldClosing(ServerLevel level) {
        for (Entry entry : List.copyOf(active.values()))
            if (entry.level == level || entry.transition != null && entry.transition.newLevel() == level) cancel(entry, "WORLD_CLOSED");
    }

    private void cancel(Entry entry) { cancel(entry, "UNRETAINED_PREFIX"); }
    private void cancel(Entry entry, String reason) {
        IllegalStateException partial = null;
        if (entry.graphPlan != null) {
            try { entry.graphPlan.restoreUncommittedSourceGraph(); }
            catch (RuntimeException | Error repairFailure) {
                partial = new IllegalStateException("PARTIAL_PORTAL_GRAPH: exact source graph was not restored", repairFailure);
            }
        }
        NativePortalTransfer.retained(entry.entity, entry.binding, entry.token,
            entry.transition == null ? null : entry.transition.newLevel(), reason);
        if (entry.graphPlan != null) entry.graphPlan.cancelUnsettled();
        else if (entry.graph != null) {
            for (var member : entry.graphTokens.entrySet()) {
                if (member.getKey() == entry.entity) continue;
                if (replacements.cancel(member.getValue()))
                    NativePortalTransfer.retained(member.getKey(), entry.graph.member(member.getKey()).binding(),
                        member.getValue(), entry.transition == null ? null : entry.transition.newLevel(), reason);
            }
        }
        replacements.cancel(entry.token); active.remove(key(entry.entity), entry);
        releaseGraphMembers(entry);
        if (partial != null) throw partial;
    }

    public int pendingCount() { return active.size(); }

    @Override public void close() {
        closed = true;
        for (Entry entry : List.copyOf(active.values())) cancel(entry, "SHUTDOWN");
        replacements.close(); ticking.set(null);
    }

    private static IdentityKey key(Entity entity) { return new IdentityKey(entity); }
    private static final class IdentityKey {
        final Entity entity;
        IdentityKey(Entity entity) { this.entity = Objects.requireNonNull(entity); }
        @Override public int hashCode() { return System.identityHashCode(entity); }
        @Override public boolean equals(Object other) { return other instanceof IdentityKey key && entity == key.entity; }
    }
    private static final class Entry {
        final Entity entity; final ServerLevel level; final PortalProcessor processor;
        TeleportTransition transition; final ObjectOwnership binding;
        final Portal portal; final BlockPos entryPosition; final int portalTime; final boolean insidePortal;
        final CrossDimensionReplacementBoundary.Token token;
        List<net.minecraft.server.level.ServerPlayer> riders = List.of();
        PortalPassengerGraphSnapshot graph;
        final java.util.IdentityHashMap<Entity, CrossDimensionReplacementBoundary.Token> graphTokens = new java.util.IdentityHashMap<>();
        NativePortalGraphTransferPlan graphPlan;
        volatile boolean retained, consumed, destinationPending, advancing;
        final PortalVisibilityDeadline visibilityDeadline = new PortalVisibilityDeadline();
        java.util.function.BooleanSupplier visibilityReady;
        Entry(Entity entity, ServerLevel level, PortalProcessor processor, ObjectOwnership binding,
                CrossDimensionReplacementBoundary.Token token) {
            this.entity = entity; this.level = level; this.processor = processor; this.binding = binding; this.token = token;
            this.portal = processor == null ? null : ((PearlPortalProcessorAccess) processor).phoenium$portal();
            this.entryPosition = processor == null ? null : processor.getEntryPosition().immutable();
            this.portalTime = processor == null ? 0 : processor.getPortalTime();
            this.insidePortal = processor != null && processor.isInsidePortalThisTick();
        }
    }
}
