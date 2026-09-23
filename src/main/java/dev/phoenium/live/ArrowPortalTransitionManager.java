package dev.phoenium.live;

import dev.phoenium.chunk.FullEntityFacts;
import dev.phoenium.mixin.PearlPortalProcessorAccess;
import dev.phoenium.ownership.ObjectOwnership;
import dev.phoenium.ownership.ObjectOwnershipRegistry;
import dev.phoenium.ownership.OwnershipContext;
import dev.phoenium.service.RegionalServiceKind;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PortalProcessor;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.entity.projectile.arrow.SpectralArrow;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.level.block.EndPortalBlock;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.HitResult;

/** Retains the pinned AbstractArrow.stepMoveAndHit portal edge before destination work.
 * A clear miss is replayable without repeating movement or collision effects. Other
 * collision shapes fail closed until their distinct hit continuation is bound. */
public final class ArrowPortalTransitionManager implements AutoCloseable {
    private static final Set<Class<?>> REVIEWED_CALLERS = Set.of(Arrow.class, SpectralArrow.class, ThrownTrident.class);
    public static boolean reviewedCaller(Class<?> caller) { return caller != null && REVIEWED_CALLERS.contains(caller); }
    private final NativePortalTransfer.Authority authority;
    private final ObjectOwnershipRegistry objects;
    private final CrossDimensionReplacementBoundary replacements = new CrossDimensionReplacementBoundary();
    private final ConcurrentHashMap<IdentityKey, Entry> active = new ConcurrentHashMap<>();
    private final ThreadLocal<Capture> ticking = new ThreadLocal<>();
    private volatile boolean closed;

    public ArrowPortalTransitionManager(NativePortalTransfer.Authority authority,
            ObjectOwnershipRegistry objects) {
        this.authority = Objects.requireNonNull(authority);
        this.objects = Objects.requireNonNull(objects);
    }

    public void begin(AbstractArrow projectile) {
        if (closed || !authority.running() || !REVIEWED_CALLERS.contains(projectile.getClass())) return;
        if (ticking.get() != null) throw new IllegalStateException("nested arrow portal tick");
        if (active.containsKey(key(projectile))) throw new IllegalStateException("pending projectile resumed native tick");
        ticking.set(new Capture(projectile));
    }

    public void hitResult(AbstractArrow projectile, BlockHitResult result,
            List<EntityHitResult> entityHits) {
        Capture capture = ticking.get();
        if (capture != null && capture.projectile == projectile) {
            capture.hitResult = Objects.requireNonNull(result);
            capture.entityHits = List.copyOf(entityHits);
        }
    }

    public boolean suspendDestination(AbstractArrow projectile, PortalProcessor processor) {
        Capture capture = ticking.get();
        if (capture == null || capture.projectile != projectile || capture.hitResult == null
                || closed || !authority.running() || projectile.isRemoved()
                || !(projectile.level() instanceof ServerLevel level) || projectile.portalProcess != processor
                || !REVIEWED_CALLERS.contains(projectile.getClass())) return false;
        if (OwnershipContext.current().isEmpty())
            throw new IllegalStateException("arrow portal suspension lacks source owner");
        if (capture.hitResult.getType() != HitResult.Type.MISS || !capture.entityHits.isEmpty())
            throw new IllegalStateException("arrow portal collision continuation is not bound");
        Portal portal = ((PearlPortalProcessorAccess) processor).phoenium$portal();
        if (!(portal instanceof NetherPortalBlock) && !(portal instanceof EndPortalBlock)) return false;
        if (projectile.isPassenger() || projectile.isVehicle())
            throw new IllegalStateException("arrow graph requires its own native continuation");
        if (active.containsKey(key(projectile))) throw new IllegalStateException("projectile portal captured twice");
        ObjectOwnership binding = objects.current(projectile.getUUID().toString()).orElseThrow();
        objects.requireCurrentOwner(binding.objectId(), binding.objectEpoch(), OwnershipContext.current().orElseThrow().region());
        var token = replacements.begin(new CrossDimensionReplacementBoundary.SourceIdentity(binding.objectId(),
            ((FullEntityFacts.Access) projectile).phoenium$entityIncarnation(), binding.incarnation()));
        Entry entry = new Entry(projectile, level, processor, portal, binding, token, capture.hitResult);
        if (active.putIfAbsent(key(projectile), entry) != null) {
            replacements.cancel(token);
            throw new IllegalStateException("projectile portal captured twice");
        }
        capture.entry = entry;
        NativePortalTransfer.retained(projectile, binding, token, null, "CAPTURED_ARROW_STEP_MISS");
        return true;
    }

    public boolean suspended(AbstractArrow projectile) { return active.containsKey(key(projectile)); }
    public boolean complete(AbstractArrow projectile) {
        Capture capture = ticking.get();
        if (capture == null || capture.projectile != projectile || capture.entry == null) return false;
        capture.entry.retained = true;
        return true;
    }
    public void finish(AbstractArrow projectile) {
        Capture capture = ticking.get();
        if (capture != null && capture.projectile == projectile) {
            if (capture.entry != null && !capture.entry.retained) cancel(capture.entry, "UNRETAINED_PREFIX");
            ticking.remove();
        }
    }

    public int advance() {
        if (OwnershipContext.current().isPresent())
            throw new IllegalStateException("arrow coordinator retained source owner");
        if (closed || !authority.running()) return 0;
        List<Entry> entries = new ArrayList<>(active.values());
        entries.sort(Comparator.comparingLong(e -> e.token.generation()));
        int settled = 0;
        for (Entry entry : entries) {
            if (!entry.retained || entry.advancing || active.get(key(entry.projectile)) != entry) continue;
            if (!current(entry)) { cancel(entry, "STALE_BEFORE_NATIVE"); continue; }
            entry.advancing = true;
            try {
                if (!entry.destinationPrepared) {
                    NativePortalTransfer.retained(entry.projectile, entry.binding, entry.token, null, "DESTINATION_PREPARING");
                    entry.transition = PortalDestinationObservation.prepare(entry.projectile, entry.binding, entry.token,
                        authority, () -> current(entry),
                        () -> entry.processor.getPortalDestination(entry.level, entry.projectile));
                    if (!current(entry)) { cancel(entry, "STALE_AFTER_PREPARATION"); continue; }
                    replacements.prepared(entry.token);
                    entry.destinationPrepared = true;
                }
                boolean permitted = entry.transition != null && entry.level.isAllowedToEnterPortal(entry.transition.newLevel())
                    && (entry.level.dimension() == entry.transition.newLevel().dimension()
                        || entry.projectile.canTeleport(entry.level, entry.transition.newLevel()));
                if (permitted && entry.level.dimension() == entry.transition.newLevel().dimension())
                    throw new IllegalStateException("same-dimension arrow portal needs a separate adapter");
                if (permitted) {
                    if (entry.visibilityReady == null)
                        entry.visibilityReady = authority.demandDestinationEntityVisibility(entry.projectile, entry.transition);
                    if (!entry.visibilityReady.getAsBoolean()) {
                        if (++entry.visibilityWaits > 200)
                            throw new IllegalStateException("arrow destination visibility deadline: " + entry.visibilityReady);
                        continue;
                    }
                }
                var transfer = permitted ? new NativePortalTransfer(authority, objects, replacements, entry.token,
                    entry.projectile, entry.binding, entry.transition, () -> active.get(key(entry.projectile)) == entry
                        && entry.visibilityReady.getAsBoolean()) : null;
                Entity result = transfer == null ? null : transfer.run();
                var continuation = result == null
                    ? replacements.authorizeUnchangedSourceContinuation(entry.token, entry.token.source())
                    : replacements.authorizeReplacementContinuation(entry.token);
                authority.execute(entry.binding.routingKey(), RegionalServiceKind.ENTITY_PORTAL_SOURCE, () -> {
                    if (active.get(key(entry.projectile)) != entry || entry.consumed || !authority.worldActive(entry.level)
                            || ((FullEntityFacts.Access) entry.projectile).phoenium$entityIncarnation() != entry.token.source().nativeIncarnation()
                            || result == null && !current(entry)
                            || result != null && entry.projectile.getRemovalReason() != Entity.RemovalReason.CHANGED_DIMENSION)
                        throw new IllegalStateException("stale arrow tick tail");
                    entry.consumed = true;
                    ((ArrowPortalNativeAccess) entry.projectile)
                        .phoenium$resumeArrowClearStepSuffix(entry.physicsEnabled);
                });
                replacements.settle(continuation);
                if (transfer != null) transfer.settled();
                else NativePortalTransfer.retained(entry.projectile, entry.binding, entry.token, null,
                    "SETTLED_NATIVE_NO_TRANSFER");
                active.remove(key(entry.projectile), entry);
                settled++;
            } catch (RuntimeException | Error failure) {
                cancel(entry, "FAILED:" + failure.getClass().getSimpleName());
                throw failure;
            } finally { entry.advancing = false; }
        }
        return settled;
    }

    private boolean current(Entry entry) {
        ObjectOwnership current = objects.current(entry.token.source().objectId()).orElse(null);
        return replacements.isCurrent(entry.token) && authority.worldActive(entry.level)
            && (entry.transition == null || authority.worldActive(entry.transition.newLevel()))
            && !entry.projectile.isRemoved() && entry.projectile.level() == entry.level
            && entry.projectile.portalProcess == entry.processor
            && entry.projectile.position().equals(entry.position)
            && entry.projectile.getDeltaMovement().equals(entry.motion)
            && entry.projectile.getYRot() == entry.yRot && entry.projectile.getXRot() == entry.xRot
            && entry.projectile.getPose() == entry.pose
            && ((FullEntityFacts.Access) entry.projectile).phoenium$entityIncarnation() == entry.token.source().nativeIncarnation()
            && entry.projectile.getUUID().toString().equals(entry.token.source().objectId())
            && entry.processor.getEntryPosition().equals(entry.entryPosition)
            && ((PearlPortalProcessorAccess) entry.processor).phoenium$portal() == entry.portal
            && entry.processor.getPortalTime() == entry.portalTime
            && entry.processor.isInsidePortalThisTick() == entry.insidePortal
            && current != null && current.incarnation() == entry.binding.incarnation()
            && current.objectEpoch().equals(entry.binding.objectEpoch())
            && current.routingKey().equals(entry.binding.routingKey());
    }
    public boolean onRemoval(Entity entity, Entity.RemovalReason reason) {
        if (!(entity instanceof AbstractArrow projectile)) return false;
        Entry entry = active.get(key(projectile));
        if (entry == null) return false;
        if (entry.consumed && projectile.getRemovalReason() == Entity.RemovalReason.CHANGED_DIMENSION) return true;
        cancel(entry, "EXTERNAL_REMOVAL:" + reason);
        return false;
    }
    public void worldClosing(ServerLevel level) {
        for (Entry entry : List.copyOf(active.values()))
            if (entry.level == level || entry.transition != null && entry.transition.newLevel() == level)
                cancel(entry, "WORLD_CLOSED");
    }
    private void cancel(Entry entry, String reason) {
        NativePortalTransfer.retained(entry.projectile, entry.binding, entry.token,
            entry.transition == null ? null : entry.transition.newLevel(), reason);
        replacements.cancel(entry.token);
        active.remove(key(entry.projectile), entry);
    }
    public int pendingCount() { return active.size(); }
    @Override public void close() {
        closed = true;
        for (Entry entry : List.copyOf(active.values())) cancel(entry, "SHUTDOWN");
        replacements.close();
        ticking.remove();
    }
    private static IdentityKey key(AbstractArrow projectile) { return new IdentityKey(projectile); }
    private record IdentityKey(AbstractArrow projectile) {
        @Override public int hashCode() { return System.identityHashCode(projectile); }
        @Override public boolean equals(Object other) {
            return other instanceof IdentityKey key && projectile == key.projectile;
        }
    }
    private static final class Capture {
        final AbstractArrow projectile;
        BlockHitResult hitResult;
        List<EntityHitResult> entityHits = List.of();
        Entry entry;
        Capture(AbstractArrow projectile) { this.projectile = projectile; }
    }
    private static final class Entry {
        final AbstractArrow projectile;
        final ServerLevel level;
        final PortalProcessor processor;
        final Portal portal;
        final ObjectOwnership binding;
        final CrossDimensionReplacementBoundary.Token token;
        final boolean physicsEnabled;
        final BlockPos entryPosition;
        final int portalTime;
        final boolean insidePortal;
        final net.minecraft.world.phys.Vec3 position, motion;
        final float yRot, xRot;
        final net.minecraft.world.entity.Pose pose;
        volatile boolean retained, consumed, destinationPrepared, advancing;
        int visibilityWaits;
        java.util.function.BooleanSupplier visibilityReady;
        TeleportTransition transition;
        Entry(AbstractArrow projectile, ServerLevel level, PortalProcessor processor, Portal portal,
                ObjectOwnership binding, CrossDimensionReplacementBoundary.Token token, BlockHitResult hitResult) {
            this.projectile = projectile; this.level = level; this.processor = processor; this.portal = portal;
            this.binding = binding; this.token = token; this.physicsEnabled = !projectile.isNoPhysics();
            this.entryPosition = processor.getEntryPosition().immutable();
            this.portalTime = processor.getPortalTime(); this.insidePortal = processor.isInsidePortalThisTick();
            this.position = projectile.position(); this.motion = projectile.getDeltaMovement();
            this.yRot = projectile.getYRot(); this.xRot = projectile.getXRot(); this.pose = projectile.getPose();
        }
    }
}
