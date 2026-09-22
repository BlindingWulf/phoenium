package dev.phoenium.live;

import dev.phoenium.chunk.FullEntityFacts;
import dev.phoenium.ownership.*;
import dev.phoenium.service.RegionalServiceKind;
import java.util.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PortalProcessor;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.portal.TeleportTransition;

/** The exact ItemEntity tick suffix after its already completed portal lookup. */
public final class ItemPortalTransitionManager implements AutoCloseable {
    public interface ItemSuffix { void phoenium$resumeItemTickSuffix(); }
    private final NativePortalTransfer.Authority authority;
    private final ObjectOwnershipRegistry objects;
    private final CrossDimensionReplacementBoundary replacements = new CrossDimensionReplacementBoundary();
    private final Map<IdentityKey, Entry> active = new java.util.concurrent.ConcurrentHashMap<>();
    private final ThreadLocal<ItemEntity> ticking = new ThreadLocal<>();
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
    public boolean suspended(ItemEntity item) { return active.containsKey(key(item)); }
    public boolean complete(ItemEntity item) {
        Entry entry = active.get(key(item)); if (entry == null || ticking.get() != item) return false;
        entry.retained = true; return true;
    }
    public void finish(ItemEntity item) {
        Entry entry = active.get(key(item));
        if (entry != null && !entry.retained) cancel(entry);
        if (ticking.get() == item) ticking.remove();
    }
    public int advance() {
        if (OwnershipContext.current().isPresent()) throw new IllegalStateException("item portal coordinator retained source owner");
        if (closed || !authority.running()) return 0;
        List<Entry> entries = new ArrayList<>(active.values());
        entries.sort(Comparator.comparingLong(entry -> entry.token.generation()));
        int settled = 0;
        for (Entry entry : entries) {
            if (!entry.retained || active.get(key(entry.item)) != entry) continue;
            if (!current(entry)) { cancel(entry,"STALE_BEFORE_NATIVE"); continue; }
            try {
                var transfer = new NativePortalTransfer(authority, objects, replacements, entry.token,
                    entry.item, entry.binding, entry.transition, () -> active.get(key(entry.item)) == entry);
                Entity result = transfer.run();
                var suffix = result == null
                    ? replacements.authorizeUnchangedSourceContinuation(entry.token, entry.token.source())
                    : replacements.authorizeReplacementContinuation(entry.token);
                authority.execute(entry.binding.routingKey(), RegionalServiceKind.ENTITY_PORTAL_SOURCE, () -> {
                    if (active.get(key(entry.item)) != entry || entry.consumed || !authority.worldActive(entry.level)
                            || ((FullEntityFacts.Access) entry.item).phoenium$entityIncarnation() != entry.token.source().nativeIncarnation()
                            || result != null && entry.item.getRemovalReason() != Entity.RemovalReason.CHANGED_DIMENSION)
                        throw new IllegalStateException("stale item portal tick suffix");
                    entry.consumed = true;
                    ((PearlPortalNativeAccess.EntitySuffix) entry.item).phoenium$resumePearlPortalBaseTickSuffix();
                    ((ItemSuffix) entry.item).phoenium$resumeItemTickSuffix();
                });
                replacements.settle(suffix); transfer.settled(); active.remove(key(entry.item), entry); settled++;
            } catch (RuntimeException | Error failure) { cancel(entry,"FAILED:"+failure.getClass().getSimpleName()); throw failure; }
        }
        return settled;
    }
    private boolean current(Entry entry) {
        ObjectOwnership current = objects.current(entry.token.source().objectId()).orElse(null);
        return replacements.isCurrent(entry.token) && authority.worldActive(entry.level)
            && authority.worldActive(entry.transition.newLevel()) && !entry.item.isRemoved()
            && entry.item.level() == entry.level && entry.item.portalProcess == entry.processor
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
        for (Entry entry : entries) if (entry.level == level || entry.transition.newLevel() == level) cancel(entry,"WORLD_CLOSED");
    }
    private void cancel(Entry entry) { cancel(entry,"UNRETAINED_PREFIX"); }
    private void cancel(Entry entry,String reason) {
        NativePortalTransfer.retained(entry.item,entry.binding,entry.token,entry.transition.newLevel(),reason);
        replacements.cancel(entry.token); active.remove(key(entry.item), entry);
    }
    public int pendingCount() { return active.size(); }
    @Override public void close() {
        closed = true;
        List<Entry> entries=List.copyOf(active.values());
        for(Entry entry:entries) cancel(entry,"SHUTDOWN");
        replacements.close(); ticking.remove();
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
        final TeleportTransition transition; final ObjectOwnership binding;
        final CrossDimensionReplacementBoundary.Token token;
        volatile boolean retained, consumed;
        Entry(ItemEntity item, ServerLevel level, PortalProcessor processor, TeleportTransition transition,
                ObjectOwnership binding, CrossDimensionReplacementBoundary.Token token) {
            this.item=item; this.level=level; this.processor=processor; this.transition=transition; this.binding=binding; this.token=token;
        }
    }
}
