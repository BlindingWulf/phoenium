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
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.EndPortalBlock;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.level.portal.TeleportTransition;

/** The exact ItemEntity tick prefix retained BEFORE native portal destination lookup. */
public final class ItemPortalTransitionManager implements AutoCloseable {
    public interface ItemSuffix { void phoenium$resumeItemTickSuffix(); }
    private final NativePortalTransfer.Authority authority;
    private final ObjectOwnershipRegistry objects;
    private final CrossDimensionReplacementBoundary replacements = new CrossDimensionReplacementBoundary();
    private final Map<IdentityKey, Entry> active = new java.util.concurrent.ConcurrentHashMap<>();
    private final ThreadLocal<ItemEntity> ticking = new dev.phoenium.core.FastThreadLocal<>();
    private volatile boolean closed;
    public ItemPortalTransitionManager(NativePortalTransfer.Authority authority, ObjectOwnershipRegistry objects) {
        this.authority = Objects.requireNonNull(authority); this.objects = Objects.requireNonNull(objects);
    }
    public void begin(ItemEntity item) {
        if (closed || !authority.running()) return;
        if (ticking.get() != null) throw new IllegalStateException("nested item portal tick");
        ticking.set(item);
    }
    public boolean capture(ItemEntity item, TeleportTransition transition) {
        if (ticking.get() != item || closed || !authority.running() || !(item.level() instanceof ServerLevel level)
                || level.dimension() == transition.newLevel().dimension()) return false;
        if (OwnershipContext.current().isEmpty()) throw new IllegalStateException("item portal cut lacks source owner");
        if (item.isPassenger() || item.isVehicle()) throw new IllegalStateException("item portal passenger graph is outside this continuation");
        ObjectOwnership binding = objects.current(item.getUUID().toString()).orElseThrow();
        objects.requireCurrentOwner(binding.objectId(), binding.objectEpoch(), OwnershipContext.current().orElseThrow().region());
        long incarnation = ((FullEntityFacts.Access) item).phoenium$entityIncarnation();
        var token = replacements.begin(new CrossDimensionReplacementBoundary.SourceIdentity(binding.objectId(), incarnation, binding.incarnation()));
        replacements.prepared(token);
        Entry entry = new Entry(item, level, item.portalProcess, transition, binding, token);
        if (active.putIfAbsent(key(item), entry) != null) throw new IllegalStateException("item portal was captured twice");
        NativePortalTransfer.retained(item,binding,token,transition.newLevel(),"CAPTURED_TICK_PREFIX");
        return true;
    }
    public boolean suspendDestination(ItemEntity item, PortalProcessor processor) {
        if (ticking.get() != item || closed || !authority.running() || item.isRemoved()
                || !(item.level() instanceof ServerLevel level) || item.portalProcess != processor
                || processor == null || item.getClass() != ItemEntity.class) return false;
        Portal portal = ((PearlPortalProcessorAccess) processor).phoenium$portal();
        if (!(portal instanceof NetherPortalBlock) && !(portal instanceof EndPortalBlock)) return false;
        if (item.isPassenger() || item.isVehicle())
            throw new IllegalStateException("item portal passenger graph is outside this continuation");
        ObjectOwnership binding = objects.current(item.getUUID().toString()).orElseThrow();
        objects.requireCurrentOwner(binding.objectId(), binding.objectEpoch(), OwnershipContext.current().orElseThrow().region());
        var token = replacements.begin(new CrossDimensionReplacementBoundary.SourceIdentity(binding.objectId(),
            ((FullEntityFacts.Access) item).phoenium$entityIncarnation(), binding.incarnation()));
        Entry entry = new Entry(item, level, processor, null, binding, token);
        entry.destinationPending = true;
        if (active.putIfAbsent(key(item), entry) != null) {
            replacements.cancel(token);
            throw new IllegalStateException("item portal was captured twice");
        }
        NativePortalTransfer.retained(item, binding, token, null, "CAPTURED");
        return true;
    }
    public boolean suspended(ItemEntity item) { return active.containsKey(key(item)); }
    public boolean complete(ItemEntity item) {
        Entry entry = active.get(key(item)); if (entry == null || ticking.get() != item) return false;
        entry.retained = true; return true;
    }
    public void finish(ItemEntity item) {
        Entry entry = active.get(key(item));
        if (entry != null && !entry.retained) cancel(entry);
        if (ticking.get() == item) ticking.set(null);
    }
    public int advance() {
        if (OwnershipContext.current().isPresent()) throw new IllegalStateException("item portal coordinator retained source owner");
        if (closed || !authority.running()) return 0;
        List<Entry> entries = new ArrayList<>(active.values());
        entries.sort(Comparator.comparingLong(entry -> entry.token.generation()));
        int settled = 0;
        for (Entry entry : entries) {
            if (!entry.retained || entry.advancing || active.get(key(entry.item)) != entry) continue;
            if (!current(entry)) { cancel(entry,"STALE_BEFORE_NATIVE"); continue; }
            entry.advancing = true;
            try {
                if (entry.destinationPending) {
                    NativePortalTransfer.retained(entry.item, entry.binding, entry.token, null, "DESTINATION_PREPARING");
                    entry.transition = PortalDestinationObservation.prepare(entry.item,entry.binding,entry.token,
                        authority,() -> current(entry),
                        () -> entry.processor.getPortalDestination(entry.level, entry.item));
                    entry.destinationPending = false;
                    if (!current(entry)) { cancel(entry,"STALE_AFTER_PREPARATION"); continue; }
                    replacements.prepared(entry.token);
                    NativePortalTransfer.retained(entry.item, entry.binding, entry.token,
                        entry.transition == null ? null : entry.transition.newLevel(), "DESTINATION_READY");
                }
                boolean permitted = entry.transition != null && entry.level.isAllowedToEnterPortal(entry.transition.newLevel())
                    && (entry.level.dimension() == entry.transition.newLevel().dimension()
                        || entry.item.canTeleport(entry.level, entry.transition.newLevel()));
                if (permitted && entry.level.dimension() == entry.transition.newLevel().dimension())
                    throw new IllegalStateException("vanilla Nether portal produced a same-dimension item transition");
                if (permitted) {
                    if (entry.visibilityReady == null)
                        entry.visibilityReady = authority.demandDestinationEntityVisibility(entry.item, entry.transition);
                    if (!entry.visibilityReady.getAsBoolean()) {
                        if (entry.visibilityDeadline.expired(authority.serverTick()))
                            throw new IllegalStateException("item destination entity visibility deadline: " + entry.visibilityReady);
                        continue;
                    }
                }
                var transfer = permitted ? new NativePortalTransfer(authority, objects, replacements, entry.token,
                    entry.item, entry.binding, entry.transition, () -> active.get(key(entry.item)) == entry
                        && entry.visibilityReady.getAsBoolean()) : null;
                Entity result = transfer == null ? null : transfer.run();
                var suffix = result == null
                    ? replacements.authorizeUnchangedSourceContinuation(entry.token, entry.token.source())
                    : replacements.authorizeReplacementContinuation(entry.token);
                authority.execute(entry.binding.routingKey(), RegionalServiceKind.ENTITY_PORTAL_SOURCE, () -> {
                    if (active.get(key(entry.item)) != entry || entry.consumed || !authority.worldActive(entry.level)
                            || ((FullEntityFacts.Access) entry.item).phoenium$entityIncarnation() != entry.token.source().nativeIncarnation()
                            || result == null && !current(entry)
                            || result != null && entry.item.getRemovalReason() != Entity.RemovalReason.CHANGED_DIMENSION)
                        throw new IllegalStateException("stale item portal tick suffix");
                    entry.consumed = true;
                    ((PearlPortalNativeAccess.EntitySuffix) entry.item).phoenium$resumePearlPortalBaseTickSuffix();
                    ((ItemSuffix) entry.item).phoenium$resumeItemTickSuffix();
                });
                replacements.settle(suffix);
                if (transfer != null) transfer.settled();
                else NativePortalTransfer.retained(entry.item, entry.binding, entry.token, null, "SETTLED_NATIVE_NO_TRANSFER");
                active.remove(key(entry.item), entry); settled++;
            } catch (RuntimeException | Error failure) { cancel(entry,"FAILED:"+failure.getClass().getSimpleName()); throw failure; }
            finally { entry.advancing = false; }
        }
        return settled;
    }
    private boolean current(Entry entry) {
        ObjectOwnership current = objects.current(entry.token.source().objectId()).orElse(null);
        return replacements.isCurrent(entry.token) && authority.worldActive(entry.level)
            && (entry.transition == null || authority.worldActive(entry.transition.newLevel())) && !entry.item.isRemoved()
            && entry.item.level() == entry.level && entry.item.portalProcess == entry.processor
            && ((FullEntityFacts.Access) entry.item).phoenium$entityIncarnation() == entry.token.source().nativeIncarnation()
            && entry.item.getUUID().toString().equals(entry.token.source().objectId())
            && (entry.processor == null || entry.processor.getEntryPosition().equals(entry.entryPosition)
                && ((PearlPortalProcessorAccess) entry.processor).phoenium$portal() == entry.portal
                && entry.processor.getPortalTime() == entry.portalTime
                && entry.processor.isInsidePortalThisTick() == entry.insidePortal)
            && current != null && current.incarnation() == entry.binding.incarnation()
            && current.objectEpoch().equals(entry.binding.objectEpoch()) && current.routingKey().equals(entry.binding.routingKey());
    }
    public boolean onRemoval(Entity entity, Entity.RemovalReason reason) {
        if (!(entity instanceof ItemEntity item)) return false;
        Entry entry = active.get(key(item)); if (entry == null) return false;
        // Native suffixes can call discard on the already retired source. Its replacement
        // is a distinct incarnation; leave its current UUID binding alone.
        if (entry.consumed && item.getRemovalReason() == Entity.RemovalReason.CHANGED_DIMENSION) return true;
        cancel(entry,"EXTERNAL_REMOVAL:"+reason); return false;
    }
    public void worldClosing(ServerLevel level) {
        List<Entry> entries = List.copyOf(active.values());
        for (Entry entry : entries) if (entry.level == level || entry.transition != null && entry.transition.newLevel() == level) cancel(entry,"WORLD_CLOSED");
    }
    private void cancel(Entry entry) { cancel(entry,"UNRETAINED_PREFIX"); }
    private void cancel(Entry entry,String reason) {
        NativePortalTransfer.retained(entry.item,entry.binding,entry.token,entry.transition == null ? null : entry.transition.newLevel(),reason);
        replacements.cancel(entry.token); active.remove(key(entry.item), entry);
    }
    public int pendingCount() { return active.size(); }
    @Override public void close() {
        closed = true;
        List<Entry> entries=List.copyOf(active.values());
        for(Entry entry:entries) cancel(entry,"SHUTDOWN");
        replacements.close(); ticking.set(null);
    }
    private static IdentityKey key(ItemEntity item) { return new IdentityKey(item); }
    private static final class IdentityKey {
        final ItemEntity item;
        IdentityKey(ItemEntity item) { this.item=Objects.requireNonNull(item); }
        @Override public int hashCode() { return System.identityHashCode(item); }
        @Override public boolean equals(Object other) { return other instanceof IdentityKey key && item == key.item; }
    }
    private static final class Entry {
        final ItemEntity item; final ServerLevel level; final PortalProcessor processor;
        TeleportTransition transition; final ObjectOwnership binding;
        final Portal portal; final BlockPos entryPosition; final int portalTime; final boolean insidePortal;
        final CrossDimensionReplacementBoundary.Token token;
        volatile boolean retained, consumed, destinationPending, advancing;
        final PortalVisibilityDeadline visibilityDeadline = new PortalVisibilityDeadline();
        java.util.function.BooleanSupplier visibilityReady;
        Entry(ItemEntity item, ServerLevel level, PortalProcessor processor, TeleportTransition transition,
                ObjectOwnership binding, CrossDimensionReplacementBoundary.Token token) {
            this.item=item; this.level=level; this.processor=processor; this.transition=transition; this.binding=binding; this.token=token;
            this.portal=processor == null ? null : ((PearlPortalProcessorAccess) processor).phoenium$portal();
            this.entryPosition=processor == null ? null : processor.getEntryPosition().immutable();
            this.portalTime=processor == null ? 0 : processor.getPortalTime();
            this.insidePortal=processor != null && processor.isInsidePortalThisTick();
        }
    }
}
