package dev.phoenium.live;

import dev.phoenium.chunk.FullEntityFacts;
import dev.phoenium.core.WorldId;
import dev.phoenium.mixin.PearlPortalProcessorAccess;
import dev.phoenium.ownership.ObjectOwnership;
import dev.phoenium.ownership.ObjectOwnershipRegistry;
import dev.phoenium.ownership.OwnedObjectKind;
import dev.phoenium.ownership.OwnershipContext;
import dev.phoenium.service.RegionalServiceKind;
import dev.phoenium.topology.RoutingKey;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.PortalProcessor;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.HitResult;

/** Owner-free preparation plus serial source/destination authority for native pearl replacement. */
final class PearlPortalTransitionManager implements AutoCloseable {
    interface Authority extends NativePortalTransfer.Authority { }

    private final Authority authority;
    private final ObjectOwnershipRegistry objects;
    private final CrossDimensionReplacementBoundary replacements = new CrossDimensionReplacementBoundary();
    private final Map<IdentityKey<ThrownEnderpearl>, Entry> active = new ConcurrentHashMap<>();
    private final ThreadLocal<Capture> capture = new ThreadLocal<>();
    private volatile boolean closed;

    PearlPortalTransitionManager(Authority authority, ObjectOwnershipRegistry objects) {
        this.authority = Objects.requireNonNull(authority, "authority");
        this.objects = Objects.requireNonNull(objects, "objects");
    }

    void beginTick(ThrownEnderpearl pearl, int previousSectionX, int previousSectionZ, Entity retainedOwner) {
        if (!authority.running() || closed || !(pearl.level() instanceof ServerLevel)) return;
        if (capture.get() != null) throw new IllegalStateException("nested pearl portal tick capture");
        if (active.containsKey(key(pearl))) throw new IllegalStateException("pending pearl entered its native prefix");
        capture.set(new Capture(pearl, previousSectionX, previousSectionZ, retainedOwner));
    }

    void retainHitResult(ThrownEnderpearl pearl, HitResult result) {
        Capture current = capture.get();
        if (current != null && current.pearl == pearl) current.hitResult = Objects.requireNonNull(result, "result");
    }

    boolean suspendDestination(ThrownEnderpearl pearl, PortalProcessor processor) {
        Capture current = capture.get();
        if (current == null || current.pearl != pearl || current.hitResult == null || closed || !authority.running()) return false;
        if (OwnershipContext.current().isEmpty())
            throw new IllegalStateException("pearl portal suspension was reached without source owner authority");
        if (!(pearl.level() instanceof ServerLevel sourceLevel) || pearl.portalProcess != processor || pearl.isRemoved()
                || pearl.getClass() != ThrownEnderpearl.class) return false;
        Portal portal = ((PearlPortalProcessorAccess) processor).phoenium$portal();
        if (!(portal instanceof NetherPortalBlock)) return false;
        if (pearl.isPassenger() || pearl.isVehicle())
            throw new IllegalStateException("ridden pearl cannot use the retained portal continuation");
        FullEntityFacts.Access access = access(pearl);
        ObjectOwnership binding = objects.current(pearl.getUUID().toString()).orElse(null);
        if (binding == null || binding.kind() != OwnedObjectKind.ENTITY) return false;
        objects.requireCurrentOwner(binding.objectId(), binding.objectEpoch(), OwnershipContext.current().orElseThrow().region());
        var sourceIdentity = new CrossDimensionReplacementBoundary.SourceIdentity(
            binding.objectId(), access.phoenium$entityIncarnation(), binding.incarnation());
        var token = replacements.begin(sourceIdentity);
        Entry entry = new Entry(token, pearl, sourceLevel, processor, portal, processor.getEntryPosition().immutable(), binding);
        active.put(key(pearl), entry);
        current.entry = entry;
        NativePortalTransfer.retained(pearl, binding, token, null, "CAPTURED");
        return true;
    }

    boolean suspended(ThrownEnderpearl pearl) {
        Capture current = capture.get();
        return current != null && current.pearl == pearl && current.entry != null;
    }

    boolean completeTick(ThrownEnderpearl pearl) {
        Capture current = capture.get();
        if (current == null || current.pearl != pearl || current.entry == null) return false;
        Entry entry = current.entry;
        if (entry.continuation != null) throw new IllegalStateException("pearl portal continuation captured twice");
        entry.continuation = new RetainedPearlTickContinuation(entry.token, pearl, entry.sourceLevel,
            entry.processor, entry.portal, entry.entryPosition, Objects.requireNonNull(current.hitResult, "hitResult"),
            current.previousSectionX, current.previousSectionZ, current.retainedOwner);
        capture.remove();
        return true;
    }

    void finishTick(ThrownEnderpearl pearl) {
        Capture current = capture.get();
        if (current != null && current.pearl == pearl) {
            if (current.entry != null && current.entry.continuation == null) cancel(current.entry);
            capture.remove();
        }
    }

    boolean skipPendingTick(ThrownEnderpearl pearl) { return active.containsKey(key(pearl)); }

    int advance() {
        if (OwnershipContext.current().isPresent())
            throw new IllegalStateException("pearl portal coordinator retained owner authority");
        if (closed || !authority.running()) return 0;
        int settled = 0;
        List<Entry> snapshot = new ArrayList<>(active.values());
        snapshot.sort(Comparator.comparingLong(entry -> entry.token.generation()));
        for (Entry entry : snapshot) {
            if (active.get(key(entry.source)) != entry || entry.continuation == null || entry.advancing) continue;
            entry.advancing = true;
            try {
                if (replacements.phase(entry.token) == CrossDimensionReplacementBoundary.Phase.CAPTURED) {
                    ObjectOwnership current = currentSource(entry);
                    if (current == null) {
                        cancel(entry);
                        continue;
                    }
                    prepare(entry);
                }
                if (active.get(key(entry.source)) == entry
                        && replacements.phase(entry.token) == CrossDimensionReplacementBoundary.Phase.PREPARED) {
                    applyPrepared(entry);
                    if (active.get(key(entry.source)) != entry) settled++;
                }
            } catch (RuntimeException | Error failure) {
                NativePortalTransfer.retained(entry.source, entry.binding, entry.token,
                    entry.prepared == null ? null : entry.prepared.newLevel(), "FAILED:" + failure.getClass().getSimpleName());
                cancel(entry);
                throw failure;
            } finally { entry.advancing = false; }
        }
        return settled;
    }

    private void prepare(Entry entry) {
        if (!validNativeSource(entry)) { cancel(entry); return; }
        TeleportTransition prepared;
        Profiler.get().push("phoeniumPearlPortalPreparation");
        try {
            NativePortalTransfer.retained(entry.source, entry.binding, entry.token, null, "DESTINATION_PREPARING");
            prepared = PortalDestinationObservation.prepare(entry.source,entry.binding,entry.token,
                () -> entry.processor.getPortalDestination(entry.sourceLevel, entry.source));
        } finally {
            Profiler.get().pop();
        }
        if (currentSource(entry) == null) { cancel(entry); return; }
        entry.prepared = prepared;
        entry.preparedGeneration = entry.token.generation();
        replacements.prepared(entry.token);
        NativePortalTransfer.retained(entry.source, entry.binding, entry.token,
            prepared == null ? null : prepared.newLevel(), "DESTINATION_READY");
    }

    private void applyPrepared(Entry entry) {
        if (entry.preparedGeneration != entry.token.generation()) {
            cancel(entry);
            return;
        }
        ObjectOwnership sourceBinding = currentSource(entry);
        if (sourceBinding == null) {
            cancel(entry);
            return;
        }
        TeleportTransition transition = entry.prepared;
        if (transition == null) {
            resumeUnchanged(entry, sourceBinding);
            return;
        }
        ServerLevel destinationLevel = transition.newLevel();
        if (!authority.worldActive(destinationLevel)) { cancel(entry); return; }
        if (!entry.sourceLevel.isAllowedToEnterPortal(destinationLevel)
                || (destinationLevel.dimension() != entry.sourceLevel.dimension()
                    && !entry.source.canTeleport(entry.sourceLevel, destinationLevel))) {
            resumeUnchanged(entry, sourceBinding);
            return;
        }
        if (destinationLevel.dimension() == entry.sourceLevel.dimension()) {
            throw new IllegalStateException("vanilla Nether portal produced a same-dimension pearl transition");
        }
        replaceCrossDimension(entry, sourceBinding, transition);
    }

    private void resumeUnchanged(Entry entry, ObjectOwnership sourceBinding) {
        var authorization = replacements.authorizeUnchangedSourceContinuation(entry.token, sourceIdentity(entry, sourceBinding));
        authority.execute(sourceBinding.routingKey(), RegionalServiceKind.ENTITY_PORTAL_SOURCE, () -> {
            requireCurrentSourceAuthority(entry);
            entry.continuation.resume();
        });
        replacements.settle(authorization);
        NativePortalTransfer.retained(entry.source, entry.binding, entry.token, null, "SETTLED_NATIVE_NO_TRANSFER");
        active.remove(key(entry.source), entry);
    }

    private void replaceCrossDimension(Entry entry, ObjectOwnership sourceBinding, TeleportTransition transition) {
        var transfer = new NativePortalTransfer(authority, objects, replacements, entry.token,
            entry.source, sourceBinding, transition, () -> active.get(key(entry.source)) == entry);
        Entity result = transfer.run();
        if (result == null) { resumeUnchanged(entry, sourceBinding); return; }
        if (!(result instanceof ThrownEnderpearl)) throw new IllegalStateException("native pearl returned another type");
        NativePortalTransfer.retained(entry.source, entry.binding, entry.token, transition.newLevel(), "PROJECTILE_TRANSFER");
        var continuation = replacements.authorizeReplacementContinuation(entry.token);
        authority.execute(sourceBinding.routingKey(), RegionalServiceKind.ENTITY_PORTAL_SOURCE, () -> {
            if (active.get(key(entry.source)) != entry || !authority.worldActive(entry.sourceLevel)
                    || entry.source.getRemovalReason() != Entity.RemovalReason.CHANGED_DIMENSION
                    || access(entry.source).phoenium$entityIncarnation() != entry.token.source().nativeIncarnation())
                throw new IllegalStateException("retired pearl continuation source changed");
            entry.continuation.resume();
        });
        replacements.settle(continuation);
        transfer.settled();
        active.remove(key(entry.source), entry);
    }
    boolean onRemoval(Entity entity, Entity.RemovalReason reason) {
        if (!(entity instanceof ThrownEnderpearl pearl)) return false;
        Entry entry = active.get(key(pearl));
        if (entry == null) return false;
        if (entry.continuation != null && entry.continuation.consumed()
                && pearl.getRemovalReason() == Entity.RemovalReason.CHANGED_DIMENSION) return true;
        CrossDimensionReplacementBoundary.SourceIdentity current = nativeSourceIdentity(entry);
        if (reason == Entity.RemovalReason.CHANGED_DIMENSION && entry.sourceRemoval != null
                && replacements.recognizesExpectedRemoval(entry.sourceRemoval, current)) return true;
        cancel(entry);
        return false;
    }

    void worldClosing(ServerLevel level) {
        List<Entry> snapshot = List.copyOf(active.values());
        for (Entry entry : snapshot)
            if (entry.sourceLevel == level || entry.prepared != null && entry.prepared.newLevel() == level) cancel(entry);
    }

    int pendingCount() { return active.size(); }

    @Override public void close() {
        if (closed) return;
        closed = true;
        Capture current = capture.get();
        if (current != null && current.entry != null && current.entry.continuation != null) current.entry.continuation.retire();
        capture.remove();
        List<Entry> snapshot = List.copyOf(active.values());
        for (Entry entry : snapshot) if (entry.continuation != null) entry.continuation.retire();
        active.clear();
        replacements.close();
    }

    private ObjectOwnership currentSource(Entry entry) {
        if (!validNativeSource(entry)) return null;
        ObjectOwnership binding = objects.current(entry.token.source().objectId()).orElse(null);
        if (binding == null || binding.kind() != OwnedObjectKind.ENTITY
                || binding.incarnation() != entry.token.source().registryIncarnation()
                || !binding.objectEpoch().equals(entry.binding.objectEpoch())
                || !binding.routingKey().equals(entry.binding.routingKey())) return null;
        return binding;
    }

    private ObjectOwnership requireCurrentSource(Entry entry) {
        ObjectOwnership binding = currentSource(entry);
        if (binding == null) throw new IllegalStateException("pearl portal source incarnation became stale");
        return binding;
    }

    private ObjectOwnership requireCurrentSourceAuthority(Entry entry) {
        ObjectOwnership binding = requireCurrentSource(entry);
        return objects.requireCurrentOwner(binding.objectId(), binding.objectEpoch(),
            OwnershipContext.current().orElseThrow().region());
    }

    private boolean validNativeSource(Entry entry) {
        return active.get(key(entry.source)) == entry && replacements.isCurrent(entry.token)
            && authority.worldActive(entry.sourceLevel) && entry.source.level() == entry.sourceLevel
            && !entry.source.isRemoved() && entry.source.portalProcess == entry.processor
            && entry.processor.getEntryPosition().equals(entry.entryPosition)
            && entry.processor.getPortalTime() == entry.portalTime
            && entry.processor.isInsidePortalThisTick() == entry.insidePortal
            && ((PearlPortalProcessorAccess) entry.processor).phoenium$portal() == entry.portal
            && access(entry.source).phoenium$entityIncarnation() == entry.token.source().nativeIncarnation()
            && entry.source.getUUID().toString().equals(entry.token.source().objectId());
    }

    private CrossDimensionReplacementBoundary.SourceIdentity sourceIdentity(Entry entry, ObjectOwnership binding) {
        return new CrossDimensionReplacementBoundary.SourceIdentity(binding.objectId(),
            access(entry.source).phoenium$entityIncarnation(), binding.incarnation());
    }

    private CrossDimensionReplacementBoundary.SourceIdentity nativeSourceIdentity(Entry entry) {
        return new CrossDimensionReplacementBoundary.SourceIdentity(entry.source.getUUID().toString(),
            access(entry.source).phoenium$entityIncarnation(), entry.token.source().registryIncarnation());
    }

    private void cancel(Entry entry) {
        if (entry == null) return;
        replacements.cancel(entry.token);
        if (entry.continuation != null) entry.continuation.retire();
        active.remove(key(entry.source), entry);
    }

    private static FullEntityFacts.Access access(Entity entity) {
        if (!(entity instanceof FullEntityFacts.Access access))
            throw new IllegalStateException("pearl lacks native incarnation access");
        return access;
    }

    private static <T> IdentityKey<T> key(T value) {
        return new IdentityKey<>(value);
    }

    private static final class IdentityKey<T> {
        private final T value;
        private final int hash;

        private IdentityKey(T value) {
            this.value = Objects.requireNonNull(value, "value");
            this.hash = System.identityHashCode(value);
        }

        @Override public int hashCode() { return hash; }

        @Override public boolean equals(Object other) {
            return this == other || other instanceof IdentityKey<?> key && value == key.value;
        }
    }

    private static final class Capture {
        final ThrownEnderpearl pearl;
        final int previousSectionX;
        final int previousSectionZ;
        final Entity retainedOwner;
        HitResult hitResult;
        Entry entry;
        Capture(ThrownEnderpearl pearl, int previousSectionX, int previousSectionZ, Entity retainedOwner) {
            this.pearl = pearl;
            this.previousSectionX = previousSectionX;
            this.previousSectionZ = previousSectionZ;
            this.retainedOwner = retainedOwner;
        }
    }

    private static final class Entry {
        final CrossDimensionReplacementBoundary.Token token;
        final ThrownEnderpearl source;
        final ServerLevel sourceLevel;
        final PortalProcessor processor;
        final Portal portal;
        final BlockPos entryPosition;
        final ObjectOwnership binding;
        final int portalTime;
        final boolean insidePortal;
        volatile boolean advancing;
        volatile RetainedPearlTickContinuation continuation;
        volatile TeleportTransition prepared;
        volatile long preparedGeneration;
        volatile CrossDimensionReplacementBoundary.SourceRemoval sourceRemoval;
        Entry(CrossDimensionReplacementBoundary.Token token, ThrownEnderpearl source, ServerLevel sourceLevel,
                PortalProcessor processor, Portal portal, BlockPos entryPosition, ObjectOwnership binding) {
            this.token = token;
            this.source = source;
            this.sourceLevel = sourceLevel;
            this.processor = processor;
            this.portal = portal;
            this.entryPosition = entryPosition;
            this.binding = binding;
            this.portalTime = processor.getPortalTime();
            this.insidePortal = processor.isInsidePortalThisTick();
        }
    }
}
