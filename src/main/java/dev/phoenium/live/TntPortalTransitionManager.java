package dev.phoenium.live;

import dev.phoenium.chunk.FullEntityFacts;
import dev.phoenium.mixin.PearlPortalProcessorAccess;
import dev.phoenium.ownership.*;
import dev.phoenium.service.RegionalServiceKind;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PortalProcessor;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.block.EndPortalBlock;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.level.portal.TeleportTransition;

/** Retains the exact PrimedTnt.tick continuation after its direct handlePortal call. */
public final class TntPortalTransitionManager implements AutoCloseable {
    private final NativePortalTransfer.Authority authority;
    private final ObjectOwnershipRegistry objects;
    private final CrossDimensionReplacementBoundary replacements = new CrossDimensionReplacementBoundary();
    private final Map<IdentityKey, Entry> active = new ConcurrentHashMap<>();
    private final ThreadLocal<PrimedTnt> ticking = new ThreadLocal<>();
    private volatile boolean closed;

    public TntPortalTransitionManager(NativePortalTransfer.Authority authority, ObjectOwnershipRegistry objects) {
        this.authority = Objects.requireNonNull(authority); this.objects = Objects.requireNonNull(objects);
    }
    public void begin(PrimedTnt tnt) {
        if (closed || !authority.running() || tnt.getClass() != PrimedTnt.class) return;
        if (ticking.get() != null) throw new IllegalStateException("nested tnt portal tick");
        ticking.set(tnt);
    }
    public boolean suspendDestination(PrimedTnt tnt, PortalProcessor processor) {
        if (closed || !authority.running() || ticking.get() != tnt || tnt.isRemoved()
                || tnt.getClass() != PrimedTnt.class || !(tnt.level() instanceof ServerLevel level)
                || processor == null || tnt.portalProcess != processor) return false;
        Portal portal = ((PearlPortalProcessorAccess) processor).phoenium$portal();
        if (!(portal instanceof EndPortalBlock) && !(portal instanceof NetherPortalBlock)) return false;
        if (tnt.isPassenger() || tnt.isVehicle())
            throw new IllegalStateException("tnt passenger graph has no native continuation");
        ObjectOwnership binding = objects.current(tnt.getUUID().toString()).orElseThrow();
        objects.requireCurrentOwner(binding.objectId(), binding.objectEpoch(), OwnershipContext.current().orElseThrow().region());
        var token = replacements.begin(new CrossDimensionReplacementBoundary.SourceIdentity(binding.objectId(),
            ((FullEntityFacts.Access) tnt).phoenium$entityIncarnation(), binding.incarnation()));
        Entry entry = new Entry(tnt, level, processor, binding, token);
        if (active.putIfAbsent(key(tnt), entry) != null) {
            replacements.cancel(token); throw new IllegalStateException("tnt portal captured twice");
        }
        NativePortalTransfer.retained(tnt, binding, token, null, "CAPTURED_TNT_TICK");
        return true;
    }
    public boolean suspended(PrimedTnt tnt) { return active.containsKey(key(tnt)); }
    public boolean complete(PrimedTnt tnt) {
        Entry entry = active.get(key(tnt));
        if (entry == null || ticking.get() != tnt) return false;
        if (entry.retained) throw new IllegalStateException("tnt tail captured twice");
        entry.retained = true;
        return true;
    }
    public void finish(PrimedTnt tnt) {
        Entry entry = active.get(key(tnt));
        if (entry != null && !entry.retained) cancel(entry, "UNRETAINED_PREFIX");
        if (ticking.get() == tnt) ticking.set(null);
    }
    public int advance() {
        if (OwnershipContext.current().isPresent()) throw new IllegalStateException("tnt coordinator retained source owner");
        if (closed || !authority.running()) return 0;
        var entries = new ArrayList<>(active.values());
        entries.sort(Comparator.comparingLong(entry -> entry.token.generation()));
        int settled = 0;
        for (Entry entry : entries) {
            if (!entry.retained || entry.advancing || active.get(key(entry.tnt)) != entry) continue;
            if (!current(entry)) { cancel(entry, "STALE_BEFORE_NATIVE"); continue; }
            entry.advancing = true;
            try {
                if (!entry.destinationPrepared) {
                    NativePortalTransfer.retained(entry.tnt, entry.binding, entry.token, null, "DESTINATION_PREPARING");
                    entry.transition = PortalDestinationObservation.prepare(entry.tnt, entry.binding, entry.token,
                        authority, () -> current(entry), () -> entry.processor.getPortalDestination(entry.level, entry.tnt));
                    if (!current(entry)) { cancel(entry, "STALE_AFTER_PREPARATION"); continue; }
                    replacements.prepared(entry.token);
                    entry.destinationPrepared = true;
                }
                boolean permitted = entry.transition != null && entry.level.isAllowedToEnterPortal(entry.transition.newLevel())
                    && (entry.level.dimension() == entry.transition.newLevel().dimension()
                        || entry.tnt.canTeleport(entry.level, entry.transition.newLevel()));
                if (permitted && entry.level.dimension() == entry.transition.newLevel().dimension())
                    throw new IllegalStateException("same-dimension tnt portal needs a separate native adapter");
                if (permitted) {
                    if (entry.visibilityReady == null)
                        entry.visibilityReady = authority.demandDestinationEntityVisibility(entry.tnt, entry.transition);
                    if (!entry.visibilityReady.getAsBoolean()) {
                        if (entry.visibilityDeadline.expired(authority.serverTick()))
                            throw new IllegalStateException("tnt destination entity visibility deadline: " + entry.visibilityReady);
                        continue;
                    }
                }
                var transfer = permitted ? new NativePortalTransfer(authority, objects, replacements, entry.token,
                    entry.tnt, entry.binding, entry.transition, () -> active.get(key(entry.tnt)) == entry
                        && entry.visibilityReady.getAsBoolean()) : null;
                Entity result = transfer == null ? null : transfer.run();
                var continuation = result == null
                    ? replacements.authorizeUnchangedSourceContinuation(entry.token, entry.token.source())
                    : replacements.authorizeReplacementContinuation(entry.token);
                authority.execute(entry.binding.routingKey(), RegionalServiceKind.ENTITY_PORTAL_SOURCE, () -> {
                    if (active.get(key(entry.tnt)) != entry || entry.consumed || !authority.worldActive(entry.level)
                            || ((FullEntityFacts.Access) entry.tnt).phoenium$entityIncarnation() != entry.token.source().nativeIncarnation()
                            || result == null && !current(entry)
                            || result != null && entry.tnt.getRemovalReason() != Entity.RemovalReason.CHANGED_DIMENSION)
                        throw new IllegalStateException("stale tnt native tick tail");
                    entry.consumed = true;
                    ((TntPortalNativeAccess) entry.tnt).phoenium$resumeTntTickSuffix();
                });
                replacements.settle(continuation);
                if (transfer != null) transfer.settled();
                else NativePortalTransfer.retained(entry.tnt, entry.binding, entry.token, null, "SETTLED_NATIVE_NO_TRANSFER");
                active.remove(key(entry.tnt), entry); settled++;
            } catch (RuntimeException | Error failure) {
                cancel(entry, "FAILED:" + failure.getClass().getSimpleName()); throw failure;
            } finally { entry.advancing = false; }
        }
        return settled;
    }
    private boolean current(Entry entry) {
        ObjectOwnership current = objects.current(entry.token.source().objectId()).orElse(null);
        return replacements.isCurrent(entry.token) && authority.worldActive(entry.level)
            && (entry.transition == null || authority.worldActive(entry.transition.newLevel()))
            && !entry.tnt.isRemoved() && entry.tnt.level() == entry.level && entry.tnt.portalProcess == entry.processor
            && entry.tnt.position().equals(entry.position) && entry.tnt.getDeltaMovement().equals(entry.motion)
            && entry.tnt.getYRot() == entry.yRot && entry.tnt.getXRot() == entry.xRot && entry.tnt.getPose() == entry.pose
            && entry.tnt.getFuse() == entry.fuse && entry.tnt.getBlockState().equals(entry.blockState)
            && ((FullEntityFacts.Access) entry.tnt).phoenium$entityIncarnation() == entry.token.source().nativeIncarnation()
            && entry.tnt.getUUID().toString().equals(entry.token.source().objectId())
            && entry.processor.getEntryPosition().equals(entry.entryPosition)
            && ((PearlPortalProcessorAccess) entry.processor).phoenium$portal() == entry.portal
            && entry.processor.getPortalTime() == entry.portalTime
            && entry.processor.isInsidePortalThisTick() == entry.insidePortal
            && current != null && current.incarnation() == entry.binding.incarnation()
            && current.objectEpoch().equals(entry.binding.objectEpoch())
            && current.routingKey().equals(entry.binding.routingKey());
    }
    public boolean onRemoval(Entity entity, Entity.RemovalReason reason) {
        if (!(entity instanceof PrimedTnt tnt)) return false;
        Entry entry = active.get(key(tnt)); if (entry == null) return false;
        if (entry.consumed && tnt.getRemovalReason() == Entity.RemovalReason.CHANGED_DIMENSION) return true;
        cancel(entry, "EXTERNAL_REMOVAL:" + reason); return false;
    }
    public void worldClosing(ServerLevel level) {
        for (Entry entry : List.copyOf(active.values()))
            if (entry.level == level || entry.transition != null && entry.transition.newLevel() == level) cancel(entry, "WORLD_CLOSED");
    }
    private void cancel(Entry entry, String reason) {
        NativePortalTransfer.retained(entry.tnt, entry.binding, entry.token,
            entry.transition == null ? null : entry.transition.newLevel(), reason);
        replacements.cancel(entry.token); active.remove(key(entry.tnt), entry);
    }
    public int pendingCount() { return active.size(); }
    @Override public void close() {
        closed = true;
        for (Entry entry : List.copyOf(active.values())) cancel(entry, "SHUTDOWN");
        replacements.close(); ticking.set(null);
    }
    private static IdentityKey key(PrimedTnt tnt) { return new IdentityKey(tnt); }
    private record IdentityKey(PrimedTnt tnt) {
        @Override public int hashCode() { return System.identityHashCode(tnt); }
        @Override public boolean equals(Object other) { return other instanceof IdentityKey key && tnt == key.tnt; }
    }
    private static final class Entry {
        final PrimedTnt tnt; final ServerLevel level; final PortalProcessor processor;
        final ObjectOwnership binding; final CrossDimensionReplacementBoundary.Token token;
        final Portal portal; final BlockPos entryPosition; final int portalTime; final boolean insidePortal;
        final net.minecraft.world.phys.Vec3 position, motion;
        final float yRot, xRot; final net.minecraft.world.entity.Pose pose;
        volatile boolean retained, consumed, advancing, destinationPrepared;
        final PortalVisibilityDeadline visibilityDeadline = new PortalVisibilityDeadline();
        java.util.function.BooleanSupplier visibilityReady;
        final int fuse; final net.minecraft.world.level.block.state.BlockState blockState;
        TeleportTransition transition;
        Entry(PrimedTnt tnt, ServerLevel level, PortalProcessor processor, ObjectOwnership binding,
                CrossDimensionReplacementBoundary.Token token) {
            this.tnt = tnt; this.level = level; this.processor = processor; this.binding = binding; this.token = token;
            this.portal = ((PearlPortalProcessorAccess) processor).phoenium$portal();
            this.entryPosition = processor.getEntryPosition().immutable(); this.portalTime = processor.getPortalTime();
            this.insidePortal = processor.isInsidePortalThisTick();
            this.position = tnt.position(); this.motion = tnt.getDeltaMovement();
            this.yRot = tnt.getYRot(); this.xRot = tnt.getXRot(); this.pose = tnt.getPose();
            this.fuse = tnt.getFuse(); this.blockState = tnt.getBlockState();
        }
    }
}
