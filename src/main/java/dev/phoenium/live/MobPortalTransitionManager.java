package dev.phoenium.live;

import dev.phoenium.chunk.FullEntityFacts;
import dev.phoenium.ownership.*;
import dev.phoenium.service.RegionalServiceKind;
import dev.phoenium.mixin.PearlPortalProcessorAccess;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PortalProcessor;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.level.portal.TeleportTransition;

/** Exact passenger-free ZombifiedPiglin, Chicken, Pig and EnderMan tick retention before Nether lookup. */
public final class MobPortalTransitionManager implements AutoCloseable {
    private final NativePortalTransfer.Authority authority;
    private final ObjectOwnershipRegistry objects;
    private final CrossDimensionReplacementBoundary replacements = new CrossDimensionReplacementBoundary();
    private final Map<IdentityKey, Entry> active = new java.util.concurrent.ConcurrentHashMap<>();
    private final ThreadLocal<Mob> ticking = new ThreadLocal<>();
    private volatile boolean closed;
    public MobPortalTransitionManager(NativePortalTransfer.Authority authority, ObjectOwnershipRegistry objects) {
        this.authority = Objects.requireNonNull(authority); this.objects = Objects.requireNonNull(objects);
    }
    public static boolean supports(Entity entity) {
        return entity.getClass() == net.minecraft.world.entity.monster.zombie.ZombifiedPiglin.class
            || entity.getClass() == net.minecraft.world.entity.animal.chicken.Chicken.class
            || entity.getClass() == net.minecraft.world.entity.animal.pig.Pig.class
            || entity.getClass() == net.minecraft.world.entity.monster.EnderMan.class;
    }
    public void begin(Mob mob) {
        if (closed || !authority.running() || !supports(mob)) return;
        if (ticking.get() != null) throw new IllegalStateException("nested mob portal tick");
        ticking.set(mob);
    }
    public boolean suspendDestination(Mob mob, PortalProcessor processor) {
        if (ticking.get() != mob || closed || !authority.running() || mob.isRemoved()
                || !(mob.level() instanceof ServerLevel level) || mob.portalProcess != processor
                || processor == null || !supports(mob)) return false;
        Portal portal = ((PearlPortalProcessorAccess) processor).phoenium$portal();
        if (!(portal instanceof NetherPortalBlock)) return false;
        if (mob.isPassenger() || mob.isVehicle())
            throw new IllegalStateException("mob portal passenger graph is outside this continuation");
        if(active.containsKey(key(mob)))throw new IllegalStateException("mob portal was captured twice");
        ObjectOwnership binding = objects.current(mob.getUUID().toString()).orElseThrow();
        objects.requireCurrentOwner(binding.objectId(), binding.objectEpoch(), OwnershipContext.current().orElseThrow().region());
        var token = replacements.begin(new CrossDimensionReplacementBoundary.SourceIdentity(binding.objectId(),
            ((FullEntityFacts.Access) mob).phoenium$entityIncarnation(), binding.incarnation()));
        Entry entry = new Entry(mob, level, processor, null, binding, token);
        entry.destinationPending = true;
        if (active.putIfAbsent(key(mob), entry) != null) {
            replacements.cancel(token);
            throw new IllegalStateException("mob portal was captured twice");
        }
        NativePortalTransfer.retained(mob, binding, token, null, "CAPTURED");
        return true;
    }
    public boolean suspended(Mob mob) { return active.containsKey(key(mob)); }
    public boolean complete(Mob mob) {
        Entry entry = active.get(key(mob)); if (entry == null || ticking.get() != mob) return false;
        entry.retained = true; return true;
    }
    public void finish(Mob mob) {
        Entry entry = active.get(key(mob));
        if (entry != null && !entry.retained) cancel(entry);
        if (ticking.get() == mob) ticking.remove();
    }
    public int advance() {
        if (OwnershipContext.current().isPresent()) throw new IllegalStateException("mob portal coordinator retained source owner");
        if (closed || !authority.running()) return 0;
        List<Entry> entries = new ArrayList<>(active.values());
        entries.sort(Comparator.comparingLong(entry -> entry.token.generation()));
        int settled = 0;
        for (Entry entry : entries) {
            if (!entry.retained || entry.advancing || active.get(key(entry.mob)) != entry) continue;
            if (!current(entry)) { cancel(entry,"STALE_BEFORE_NATIVE"); continue; }
            entry.advancing = true;
            try {
                if (entry.destinationPending) {
                    NativePortalTransfer.retained(entry.mob, entry.binding, entry.token, null, "DESTINATION_PREPARING");
                    entry.transition = PortalDestinationObservation.prepare(entry.mob,entry.binding,entry.token,
                        () -> entry.processor.getPortalDestination(entry.level, entry.mob));
                    entry.destinationPending = false;
                    if (!current(entry)) { cancel(entry,"STALE_AFTER_PREPARATION"); continue; }
                    replacements.prepared(entry.token);
                    NativePortalTransfer.retained(entry.mob, entry.binding, entry.token,
                        entry.transition == null ? null : entry.transition.newLevel(), "DESTINATION_READY");
                }
                boolean permitted = entry.transition != null && entry.level.isAllowedToEnterPortal(entry.transition.newLevel())
                    && (entry.level.dimension() == entry.transition.newLevel().dimension()
                        || entry.mob.canTeleport(entry.level, entry.transition.newLevel()));
                if (permitted && entry.level.dimension() == entry.transition.newLevel().dimension())
                    throw new IllegalStateException("vanilla Nether portal produced a same-dimension mob transition");
                var transfer = permitted ? new NativePortalTransfer(authority, objects, replacements, entry.token,
                    entry.mob, entry.binding, entry.transition, () -> active.get(key(entry.mob)) == entry) : null;
                Entity result = transfer == null ? null : transfer.run();
                var suffix = result == null
                    ? replacements.authorizeUnchangedSourceContinuation(entry.token, entry.token.source())
                    : replacements.authorizeReplacementContinuation(entry.token);
                authority.execute(entry.binding.routingKey(), RegionalServiceKind.ENTITY_PORTAL_SOURCE, () -> {
                    if (active.get(key(entry.mob)) != entry || entry.consumed || !authority.worldActive(entry.level)
                            || ((FullEntityFacts.Access) entry.mob).phoenium$entityIncarnation() != entry.token.source().nativeIncarnation()
                            || result == null && !current(entry)
                            || result != null && entry.mob.getRemovalReason() != Entity.RemovalReason.CHANGED_DIMENSION)
                        throw new IllegalStateException("stale mob portal tick suffix");
                    entry.consumed = true;
                    ((PearlPortalNativeAccess.EntitySuffix) entry.mob).phoenium$resumePearlPortalBaseTickSuffix();
                    ((MobPortalNativeAccess.LivingSuffix) entry.mob).phoenium$resumeMobLivingBaseSuffix();
                    ((MobPortalNativeAccess.MobSuffix) entry.mob).phoenium$resumeMobBaseSuffix();
                    ((MobPortalNativeAccess.LivingSuffix) entry.mob).phoenium$resumeMobLivingTickSuffix();
                    ((MobPortalNativeAccess.MobSuffix) entry.mob).phoenium$resumeMobTickSuffix();
                });
                replacements.settle(suffix);
                if (transfer != null) transfer.settled();
                else NativePortalTransfer.retained(entry.mob, entry.binding, entry.token, null, "SETTLED_NATIVE_NO_TRANSFER");
                active.remove(key(entry.mob), entry); settled++;
            } catch (RuntimeException | Error failure) { cancel(entry,"FAILED:"+failure.getClass().getSimpleName()); throw failure; }
            finally { entry.advancing = false; }
        }
        return settled;
    }
    private boolean current(Entry entry) {
        ObjectOwnership current = objects.current(entry.token.source().objectId()).orElse(null);
        return replacements.isCurrent(entry.token) && authority.worldActive(entry.level)
            && (entry.transition == null || authority.worldActive(entry.transition.newLevel())) && !entry.mob.isRemoved()
            && entry.mob.level() == entry.level && entry.mob.portalProcess == entry.processor
            && entry.mob.position().equals(entry.position) && entry.mob.getDeltaMovement().equals(entry.motion)
            && entry.mob.getYRot()==entry.yRot && entry.mob.getXRot()==entry.xRot && entry.mob.getPose()==entry.pose
            && ((FullEntityFacts.Access) entry.mob).phoenium$entityIncarnation() == entry.token.source().nativeIncarnation()
            && entry.mob.getUUID().toString().equals(entry.token.source().objectId())
            && (entry.processor == null || entry.processor.getEntryPosition().equals(entry.entryPosition)
                && ((PearlPortalProcessorAccess) entry.processor).phoenium$portal() == entry.portal
                && entry.processor.getPortalTime() == entry.portalTime
                && entry.processor.isInsidePortalThisTick() == entry.insidePortal)
            && current != null && current.incarnation() == entry.binding.incarnation()
            && current.objectEpoch().equals(entry.binding.objectEpoch()) && current.routingKey().equals(entry.binding.routingKey());
    }
    public boolean onRemoval(Entity entity, Entity.RemovalReason reason) {
        if (!(entity instanceof Mob mob)) return false;
        Entry entry = active.get(key(mob)); if (entry == null) return false;
        // Native suffixes can call discard on the already retired source. Its replacement
        // is a distinct incarnation; leave its current UUID binding alone.
        if (entry.consumed && mob.getRemovalReason() == Entity.RemovalReason.CHANGED_DIMENSION) return true;
        cancel(entry,"EXTERNAL_REMOVAL:"+reason); return false;
    }
    public void worldClosing(ServerLevel level) {
        List<Entry> entries = List.copyOf(active.values());
        for (Entry entry : entries) if (entry.level == level || entry.transition != null && entry.transition.newLevel() == level) cancel(entry,"WORLD_CLOSED");
    }
    private void cancel(Entry entry) { cancel(entry,"UNRETAINED_PREFIX"); }
    private void cancel(Entry entry,String reason) {
        NativePortalTransfer.retained(entry.mob,entry.binding,entry.token,entry.transition == null ? null : entry.transition.newLevel(),reason);
        replacements.cancel(entry.token); active.remove(key(entry.mob), entry);
    }
    public int pendingCount() { return active.size(); }
    @Override public void close() {
        closed = true;
        List<Entry> entries=List.copyOf(active.values());
        for(Entry entry:entries) cancel(entry,"SHUTDOWN");
        replacements.close(); ticking.remove();
    }
    private static IdentityKey key(Mob mob) { return new IdentityKey(mob); }
    private static final class IdentityKey {
        final Mob mob;
        IdentityKey(Mob mob) { this.mob=Objects.requireNonNull(mob); }
        @Override public int hashCode() { return System.identityHashCode(mob); }
        @Override public boolean equals(Object other) { return other instanceof IdentityKey key && mob == key.mob; }
    }
    private static final class Entry {
        final Mob mob; final ServerLevel level; final PortalProcessor processor;
        TeleportTransition transition; final ObjectOwnership binding;
        final Portal portal; final BlockPos entryPosition; final int portalTime; final boolean insidePortal;
        final net.minecraft.world.phys.Vec3 position,motion;
        final float yRot,xRot;final net.minecraft.world.entity.Pose pose;
        final CrossDimensionReplacementBoundary.Token token;
        volatile boolean retained, consumed, destinationPending, advancing;
        Entry(Mob mob, ServerLevel level, PortalProcessor processor, TeleportTransition transition,
                ObjectOwnership binding, CrossDimensionReplacementBoundary.Token token) {
            this.mob=mob; this.level=level; this.processor=processor; this.transition=transition; this.binding=binding; this.token=token;
            this.position=mob.position();this.motion=mob.getDeltaMovement();this.yRot=mob.getYRot();this.xRot=mob.getXRot();this.pose=mob.getPose();
            this.portal=processor == null ? null : ((PearlPortalProcessorAccess) processor).phoenium$portal();
            this.entryPosition=processor == null ? null : processor.getEntryPosition().immutable();
            this.portalTime=processor == null ? 0 : processor.getPortalTime();
            this.insidePortal=processor != null && processor.isInsidePortalThisTick();
        }
    }
}
