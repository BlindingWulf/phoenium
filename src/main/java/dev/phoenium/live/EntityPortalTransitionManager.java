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
 */
public final class EntityPortalTransitionManager implements AutoCloseable {
    private final NativePortalTransfer.Authority authority;
    private final ObjectOwnershipRegistry objects;
    private final CrossDimensionReplacementBoundary replacements = new CrossDimensionReplacementBoundary();
    private final Map<IdentityKey, Entry> active = new java.util.concurrent.ConcurrentHashMap<>();
    private final ThreadLocal<Entity> ticking = new ThreadLocal<>();
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
            ticking.remove();
        }
    }

    public boolean suspendDestination(Entity entity, PortalProcessor processor) {
        if (ticking.get() != entity || closed || !authority.running() || entity.isRemoved()
                || !(entity.level() instanceof ServerLevel level) || entity.portalProcess != processor
                || processor == null || !supports(entity)) return false;
        Portal portal = ((PearlPortalProcessorAccess) processor).phoenium$portal();
        if (!(portal instanceof NetherPortalBlock) && !(portal instanceof EndPortalBlock)) return false;
        if (entity.isPassenger() || entity.isVehicle())
            throw new IllegalStateException("entity portal passenger graph is outside this continuation: " + entity.getType());
        ObjectOwnership binding = objects.current(entity.getUUID().toString()).orElseThrow();
        objects.requireCurrentOwner(binding.objectId(), binding.objectEpoch(), OwnershipContext.current().orElseThrow().region());
        var token = replacements.begin(new CrossDimensionReplacementBoundary.SourceIdentity(binding.objectId(),
            ((FullEntityFacts.Access) entity).phoenium$entityIncarnation(), binding.incarnation()));
        Entry entry = new Entry(entity, level, processor, binding, token);
        entry.destinationPending = true;
        if (active.putIfAbsent(key(entity), entry) != null) {
            replacements.cancel(token);
            throw new IllegalStateException("entity portal was captured twice");
        }
        NativePortalTransfer.retained(entity, binding, token, null, "CAPTURED");
        return true;
    }

    public boolean suspended(Entity entity) { return active.containsKey(key(entity)); }

    public int advance() {
        if (OwnershipContext.current().isPresent()) throw new IllegalStateException("entity portal coordinator retained source owner");
        if (closed || !authority.running()) return 0;
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
                if (permitted) {
                    if (entry.visibilityReady == null)
                        entry.visibilityReady = authority.demandDestinationEntityVisibility(entry.entity, entry.transition);
                    if (!entry.visibilityReady.getAsBoolean()) {
                        if (++entry.visibilityWaits > 200)
                            throw new IllegalStateException("entity destination visibility deadline: " + entry.visibilityReady);
                        continue;
                    }
                }
                var transfer = permitted ? new NativePortalTransfer(authority, objects, replacements, entry.token,
                    entry.entity, entry.binding, entry.transition, () -> active.get(key(entry.entity)) == entry
                        && entry.visibilityReady.getAsBoolean()) : null;
                Entity result = transfer == null ? null : transfer.run();
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
                if (transfer != null) transfer.settled();
                else NativePortalTransfer.retained(entry.entity, entry.binding, entry.token, null, "SETTLED_NATIVE_NO_TRANSFER");
                active.remove(key(entry.entity), entry); settled++;
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
        NativePortalTransfer.retained(entry.entity, entry.binding, entry.token,
            entry.transition == null ? null : entry.transition.newLevel(), reason);
        replacements.cancel(entry.token); active.remove(key(entry.entity), entry);
    }

    public int pendingCount() { return active.size(); }

    @Override public void close() {
        closed = true;
        for (Entry entry : List.copyOf(active.values())) cancel(entry, "SHUTDOWN");
        replacements.close(); ticking.remove();
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
        volatile boolean retained, consumed, destinationPending, advancing;
        int visibilityWaits;
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
