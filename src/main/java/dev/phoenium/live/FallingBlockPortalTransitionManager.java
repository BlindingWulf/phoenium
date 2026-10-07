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
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EndPortalBlock;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.level.portal.TeleportTransition;

/** Exact FallingBlockEntity.tick continuation, including its original Block local. */
public final class FallingBlockPortalTransitionManager implements AutoCloseable {
    private final NativePortalTransfer.Authority authority;
    private final ObjectOwnershipRegistry objects;
    private final CrossDimensionReplacementBoundary replacements = new CrossDimensionReplacementBoundary();
    private final Map<IdentityKey, Entry> active = new ConcurrentHashMap<>();
    private final ThreadLocal<FallingBlockEntity> ticking = new ThreadLocal<>();
    private volatile boolean closed;

    public FallingBlockPortalTransitionManager(NativePortalTransfer.Authority authority, ObjectOwnershipRegistry objects) {
        this.authority = Objects.requireNonNull(authority); this.objects = Objects.requireNonNull(objects);
    }
    public void begin(FallingBlockEntity block) {
        if (closed || !authority.running() || block.getClass() != FallingBlockEntity.class) return;
        if (ticking.get() != null) throw new IllegalStateException("nested falling-block portal tick");
        ticking.set(block);
    }
    public boolean suspendDestination(FallingBlockEntity block, PortalProcessor processor) {
        if (closed || !authority.running() || ticking.get() != block || block.isRemoved()
                || block.getClass() != FallingBlockEntity.class || !(block.level() instanceof ServerLevel level)
                || processor == null || block.portalProcess != processor) return false;
        Portal portal = ((PearlPortalProcessorAccess) processor).phoenium$portal();
        if (!(portal instanceof EndPortalBlock) && !(portal instanceof NetherPortalBlock)) return false;
        if (block.isPassenger() || block.isVehicle())
            throw new IllegalStateException("falling-block passenger graph has no native continuation");
        ObjectOwnership binding = objects.current(block.getUUID().toString()).orElseThrow();
        objects.requireCurrentOwner(binding.objectId(), binding.objectEpoch(), OwnershipContext.current().orElseThrow().region());
        var token = replacements.begin(new CrossDimensionReplacementBoundary.SourceIdentity(binding.objectId(),
            ((FullEntityFacts.Access) block).phoenium$entityIncarnation(), binding.incarnation()));
        Entry entry = new Entry(block, level, processor, binding, token);
        if (active.putIfAbsent(key(block), entry) != null) {
            replacements.cancel(token); throw new IllegalStateException("falling-block portal captured twice");
        }
        NativePortalTransfer.retained(block, binding, token, null, "CAPTURED_FALLING_BLOCK_TICK");
        return true;
    }
    public boolean suspended(FallingBlockEntity block) { return active.containsKey(key(block)); }
    public boolean complete(FallingBlockEntity block, Block originalBlock) {
        Entry entry = active.get(key(block));
        if (entry == null || ticking.get() != block) return false;
        if (entry.retained) throw new IllegalStateException("falling-block tail captured twice");
        entry.originalBlock = Objects.requireNonNull(originalBlock);
        entry.retained = true;
        return true;
    }
    public void finish(FallingBlockEntity block) {
        Entry entry = active.get(key(block));
        if (entry != null && !entry.retained) cancel(entry, "UNRETAINED_PREFIX");
        if (ticking.get() == block) ticking.remove();
    }
    public int advance() {
        if (OwnershipContext.current().isPresent()) throw new IllegalStateException("falling-block coordinator retained source owner");
        if (closed || !authority.running()) return 0;
        var entries = new ArrayList<>(active.values());
        entries.sort(Comparator.comparingLong(entry -> entry.token.generation()));
        int settled = 0;
        for (Entry entry : entries) {
            if (!entry.retained || entry.advancing || active.get(key(entry.block)) != entry) continue;
            if (!current(entry)) { cancel(entry, "STALE_BEFORE_NATIVE"); continue; }
            entry.advancing = true;
            try {
                if (!entry.destinationPrepared) {
                    NativePortalTransfer.retained(entry.block, entry.binding, entry.token, null, "DESTINATION_PREPARING");
                    entry.transition = PortalDestinationObservation.prepare(entry.block, entry.binding, entry.token,
                        authority, () -> current(entry), () -> entry.processor.getPortalDestination(entry.level, entry.block));
                    if (!current(entry)) { cancel(entry, "STALE_AFTER_PREPARATION"); continue; }
                    replacements.prepared(entry.token);
                    entry.destinationPrepared = true;
                }
                boolean permitted = entry.transition != null && entry.level.isAllowedToEnterPortal(entry.transition.newLevel())
                    && (entry.level.dimension() == entry.transition.newLevel().dimension()
                        || entry.block.canTeleport(entry.level, entry.transition.newLevel()));
                if (permitted && entry.level.dimension() == entry.transition.newLevel().dimension())
                    throw new IllegalStateException("same-dimension falling-block portal needs a separate native adapter");
                if (permitted) {
                    if (entry.visibilityReady == null)
                        entry.visibilityReady = authority.demandDestinationEntityVisibility(entry.block, entry.transition);
                    if (!entry.visibilityReady.getAsBoolean()) {
                        if (entry.visibilityDeadline.expired(authority.serverTick()))
                            throw new IllegalStateException("falling-block destination entity visibility deadline: " + entry.visibilityReady);
                        continue;
                    }
                }
                var transfer = permitted ? new NativePortalTransfer(authority, objects, replacements, entry.token,
                    entry.block, entry.binding, entry.transition, () -> active.get(key(entry.block)) == entry
                        && entry.visibilityReady.getAsBoolean()) : null;
                Entity result = transfer == null ? null : transfer.run();
                var continuation = result == null
                    ? replacements.authorizeUnchangedSourceContinuation(entry.token, entry.token.source())
                    : replacements.authorizeReplacementContinuation(entry.token);
                authority.execute(entry.binding.routingKey(), RegionalServiceKind.ENTITY_PORTAL_SOURCE, () -> {
                    if (active.get(key(entry.block)) != entry || entry.consumed || !authority.worldActive(entry.level)
                            || ((FullEntityFacts.Access) entry.block).phoenium$entityIncarnation() != entry.token.source().nativeIncarnation()
                            || result == null && !current(entry)
                            || result != null && entry.block.getRemovalReason() != Entity.RemovalReason.CHANGED_DIMENSION)
                        throw new IllegalStateException("stale falling-block native tick tail");
                    entry.consumed = true;
                    ((FallingBlockPortalNativeAccess) entry.block).phoenium$resumeFallingBlockTickSuffix(entry.originalBlock);
                });
                replacements.settle(continuation);
                if (transfer != null) transfer.settled();
                else NativePortalTransfer.retained(entry.block, entry.binding, entry.token, null, "SETTLED_NATIVE_NO_TRANSFER");
                active.remove(key(entry.block), entry); settled++;
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
            && !entry.block.isRemoved() && entry.block.level() == entry.level && entry.block.portalProcess == entry.processor
            && entry.block.position().equals(entry.position) && entry.block.getDeltaMovement().equals(entry.motion)
            && entry.block.getYRot() == entry.yRot && entry.block.getXRot() == entry.xRot && entry.block.getPose() == entry.pose
            && ((FullEntityFacts.Access) entry.block).phoenium$entityIncarnation() == entry.token.source().nativeIncarnation()
            && entry.block.getUUID().toString().equals(entry.token.source().objectId())
            && entry.processor.getEntryPosition().equals(entry.entryPosition)
            && ((PearlPortalProcessorAccess) entry.processor).phoenium$portal() == entry.portal
            && entry.processor.getPortalTime() == entry.portalTime
            && entry.processor.isInsidePortalThisTick() == entry.insidePortal
            && current != null && current.incarnation() == entry.binding.incarnation()
            && current.objectEpoch().equals(entry.binding.objectEpoch())
            && current.routingKey().equals(entry.binding.routingKey());
    }
    public boolean onRemoval(Entity entity, Entity.RemovalReason reason) {
        if (!(entity instanceof FallingBlockEntity block)) return false;
        Entry entry = active.get(key(block)); if (entry == null) return false;
        if (entry.consumed && block.getRemovalReason() == Entity.RemovalReason.CHANGED_DIMENSION) return true;
        cancel(entry, "EXTERNAL_REMOVAL:" + reason); return false;
    }
    public void worldClosing(ServerLevel level) {
        for (Entry entry : List.copyOf(active.values()))
            if (entry.level == level || entry.transition != null && entry.transition.newLevel() == level) cancel(entry, "WORLD_CLOSED");
    }
    private void cancel(Entry entry, String reason) {
        NativePortalTransfer.retained(entry.block, entry.binding, entry.token,
            entry.transition == null ? null : entry.transition.newLevel(), reason);
        replacements.cancel(entry.token); active.remove(key(entry.block), entry);
    }
    public int pendingCount() { return active.size(); }
    @Override public void close() {
        closed = true;
        for (Entry entry : List.copyOf(active.values())) cancel(entry, "SHUTDOWN");
        replacements.close(); ticking.remove();
    }
    private static IdentityKey key(FallingBlockEntity block) { return new IdentityKey(block); }
    private record IdentityKey(FallingBlockEntity block) {
        @Override public int hashCode() { return System.identityHashCode(block); }
        @Override public boolean equals(Object other) { return other instanceof IdentityKey key && block == key.block; }
    }
    private static final class Entry {
        final FallingBlockEntity block; final ServerLevel level; final PortalProcessor processor;
        final ObjectOwnership binding; final CrossDimensionReplacementBoundary.Token token;
        final Portal portal; final BlockPos entryPosition; final int portalTime; final boolean insidePortal;
        final net.minecraft.world.phys.Vec3 position, motion;
        final float yRot, xRot; final net.minecraft.world.entity.Pose pose;
        volatile boolean retained, consumed, advancing, destinationPrepared;
        final PortalVisibilityDeadline visibilityDeadline = new PortalVisibilityDeadline();
        java.util.function.BooleanSupplier visibilityReady;
        Block originalBlock; TeleportTransition transition;
        Entry(FallingBlockEntity block, ServerLevel level, PortalProcessor processor, ObjectOwnership binding,
                CrossDimensionReplacementBoundary.Token token) {
            this.block = block; this.level = level; this.processor = processor; this.binding = binding; this.token = token;
            this.portal = ((PearlPortalProcessorAccess) processor).phoenium$portal();
            this.entryPosition = processor.getEntryPosition().immutable(); this.portalTime = processor.getPortalTime();
            this.insidePortal = processor.isInsidePortalThisTick();
            this.position = block.position(); this.motion = block.getDeltaMovement();
            this.yRot = block.getYRot(); this.xRot = block.getXRot(); this.pose = block.getPose();
        }
    }
}
