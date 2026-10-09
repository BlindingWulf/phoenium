package dev.phoenium.live;

import dev.phoenium.chunk.FullEntityFacts;
import dev.phoenium.core.WorldId;
import dev.phoenium.ownership.ObjectOwnership;
import dev.phoenium.ownership.ObjectOwnershipRegistry;
import dev.phoenium.ownership.OwnedObjectKind;
import dev.phoenium.ownership.OwnershipContext;
import dev.phoenium.service.RegionalServiceKind;
import dev.phoenium.topology.RegionDirectory;
import dev.phoenium.topology.RegionRoute;
import dev.phoenium.topology.RoutingKey;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

/**
 * Retains the exact native {@link ServerPlayer#teleport(TeleportTransition)} suffix across its
 * source/destination authority cut.  Source removal runs in the caller's source turn.  Only after
 * that turn releases does the coordinator re-resolve the destination, publish the existing player
 * ownership incarnation there, and run the remaining vanilla suffix under the destination owner.
 */
final class PlayerDimensionTransitionManager implements AutoCloseable {
    interface Authority {
        boolean running();
        boolean worldActive(ServerLevel level);
        WorldId worldId(ServerLevel level);
        void execute(RoutingKey key, RegionalServiceKind kind, Runnable body);
        void retireSourceIndex(ServerPlayer player, WorldId sourceWorld, long registryIncarnation);
        long currentSession(ServerPlayer player);
    }

    private final Authority authority;
    private final RegionDirectory directory;
    private final ObjectOwnershipRegistry objects;
    private final PlayerDimensionTransitionBoundary transitions = new PlayerDimensionTransitionBoundary();
    private final Map<IdentityKey<ServerPlayer>, Entry> active = new ConcurrentHashMap<>();
    private final ThreadLocal<Entry> nativeCall = new dev.phoenium.core.FastThreadLocal<>();
    private volatile boolean closed;

    PlayerDimensionTransitionManager(Authority authority, RegionDirectory directory,
                                     ObjectOwnershipRegistry objects) {
        this.authority = Objects.requireNonNull(authority, "authority");
        this.directory = Objects.requireNonNull(directory, "directory");
        this.objects = Objects.requireNonNull(objects, "objects");
    }

    /**
     * The native player prefix through oldLevel.removePlayerImmediately is a source effect.
     * Portal lookup may call ServerPlayer.teleport from the owner-free server turn, so deliver
     * that prefix synchronously to the player's current source owner before it can mutate the
     * player or source storage. The existing suspension point then releases the source lease;
     * advance() alone runs the retained native suffix under the destination owner.
     */
    ServerPlayer withSourceOwner(ServerPlayer player, TeleportTransition transition,
                                 Supplier<ServerPlayer> nativePrefix) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(transition, "transition");
        Objects.requireNonNull(nativePrefix, "nativePrefix");
        if (closed || !authority.running()) return nativePrefix.get();

        ObjectOwnership source = objects.current(player.getUUID().toString()).orElse(null);
        if (source == null || source.kind() != OwnedObjectKind.PLAYER) return null;
        // Pinned ServerPlayer.teleport has a separate same-dimension branch before source
        // retirement. Its far-target gateway effects need their own boundary, not this one.
        if (source.routingKey().world().equals(authority.worldId(transition.newLevel())))
            return nativePrefix.get();
        if (OwnershipContext.current().isPresent()) {
            if (!currentSourceOwner(player, source) || !authority.worldActive(transition.newLevel())) return null;
            return nativePrefix.get();
        }

        AtomicReference<ServerPlayer> result = new AtomicReference<>();
        AtomicBoolean executed = new AtomicBoolean();
        authority.execute(source.routingKey(), RegionalServiceKind.ENTITY_PORTAL_SOURCE, () -> {
            if (currentSourceOwner(player, source) && authority.worldActive(transition.newLevel()))
                result.set(nativePrefix.get());
            executed.set(true);
        });
        if (!executed.get()) throw new IllegalStateException("player dimension source prefix was not delivered");
        return result.get();
    }

    private boolean currentSourceOwner(ServerPlayer player, ObjectOwnership expected) {
        ObjectOwnership current = objects.current(expected.objectId()).orElse(null);
        if (current == null || current.kind() != OwnedObjectKind.PLAYER
                || current.incarnation() != expected.incarnation()
                || !current.objectEpoch().equals(expected.objectEpoch())
                || !current.routingKey().equals(expected.routingKey()))
            return false;
        objects.requireCurrentOwner(current.objectId(), current.objectEpoch(),
            OwnershipContext.current().orElseThrow(
                () -> new IllegalStateException("player dimension source prefix lacks owner authority")).region());
        if (player.isRemoved() || !(player.level() instanceof ServerLevel sourceLevel)
                || !authority.worldActive(sourceLevel)
                || !current.routingKey().world().equals(authority.worldId(sourceLevel)))
            return false;
        ChunkPos chunk = player.chunkPosition();
        if (chunk.x() != current.routingKey().x() || chunk.z() != current.routingKey().z())
            return false;
        return authority.currentSession(player) >= 0;
    }

    /** Begins only the cross-world path. Same-dimension teleport remains entirely native. */
    boolean begin(ServerPlayer player, TeleportTransition transition) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(transition, "transition");
        if (closed || !authority.running() || player.isRemoved()) return false;
        if (!(player.level() instanceof ServerLevel sourceLevel)) return false;
        ServerLevel destinationLevel = transition.newLevel();
        if (destinationLevel == null || destinationLevel.dimension() == sourceLevel.dimension()) return false;
        if (nativeCall.get() != null) throw new IllegalStateException("nested player dimension transition");
        if (!authority.worldActive(sourceLevel) || !authority.worldActive(destinationLevel)) return false;

        FullEntityFacts.Access access = access(player);
        ObjectOwnership binding = objects.current(player.getUUID().toString()).orElse(null);
        if (binding == null || binding.kind() != OwnedObjectKind.PLAYER) return false;
        if (!currentSourceOwner(player, binding)) return false;

        WorldId sourceWorld = authority.worldId(sourceLevel);
        WorldId destinationWorld = authority.worldId(destinationLevel);
        if (!binding.routingKey().world().equals(sourceWorld))
            throw new IllegalStateException("player dimension source ownership names another world");
        var source = new PlayerDimensionTransitionBoundary.SourceIdentity(binding.objectId(),
            access.phoenium$entityIncarnation(), binding.incarnation(), sourceWorld, destinationWorld);
        var token = transitions.begin(source);
        Entry entry = new Entry(token, player, transition, sourceLevel, destinationLevel,
            binding.objectEpoch(), binding.owner(), authority.currentSession(player));
        if (entry.session < 0) { transitions.cancel(token); throw new IllegalStateException("player dimension session is not current"); }
        if (active.putIfAbsent(key(player), entry) != null) {
            transitions.cancel(token);
            throw new IllegalStateException("player already has a retained dimension transition");
        }
        nativeCall.set(entry);
        return true;
    }

    /** Recognizes only the exact CHANGED_DIMENSION removal belonging to the active generation. */
    boolean onRemoval(ServerPlayer player, Entity.RemovalReason reason) {
        Entry entry = active.get(key(player));
        if (entry == null) return false;
        var current = nativeSourceIdentity(entry);
        if (reason == Entity.RemovalReason.CHANGED_DIMENSION && current.equals(entry.token.source())) {
            if (entry.sourceRemoval == null)
                entry.sourceRemoval = transitions.authorizeSourceRemoval(entry.token, current);
            return transitions.recognizesExpectedRemoval(entry.sourceRemoval, current);
        }
        cancel(entry);
        return false;
    }

    /** Called immediately after vanilla's oldLevel.removePlayerImmediately returns. */
    boolean suspendAfterSourceRemoval(ServerPlayer player) {
        Entry entry = nativeCall.get();
        if (entry == null || entry.player != player || active.get(key(player)) != entry) return false;
        if (entry.sourceRemoval == null
                || !transitions.recognizesExpectedRemoval(entry.sourceRemoval, nativeSourceIdentity(entry)))
            throw new IllegalStateException("player source retirement lacks its exact removal authorization");
        if (player.level() != entry.sourceLevel
                || player.getRemovalReason() != Entity.RemovalReason.CHANGED_DIMENSION)
            throw new IllegalStateException("player source retirement changed native level or reason");
        transitions.sourceRetired(entry.sourceRemoval);
        authority.retireSourceIndex(player, entry.token.source().sourceWorld(),
            entry.token.source().registryIncarnation());
        entry.suspended = true;
        return true;
    }

    /** Ends the synchronous invocation scope. A retained suffix survives; every other entry retires. */
    boolean finishNativeCall(ServerPlayer player, boolean began) {
        if (!began) return false;
        Entry entry = nativeCall.get();
        if (entry == null || entry.player != player)
            throw new IllegalStateException("player dimension invocation scope changed");
        nativeCall.set(null);
        if (!entry.suspended) {
            cancel(entry);
            return false;
        }
        return true;
    }

    /**
     * Takes over the native "newPassenger.startRiding(newVehicle)" that follows a vehicle's replacement. The
     * player's destination suffix has not run yet, so the remount waits for it inside the same destination turn.
     */
    boolean deferRemount(ServerPlayer rider, Entity vehicle) {
        Entry entry = active.get(key(rider));
        if (entry == null || !entry.suspended || entry.remount != null
                || transitions.phase(entry.token) != PlayerDimensionTransitionBoundary.Phase.SOURCE_RETIRED)
            return false;
        if (vehicle.isRemoved() || vehicle.level() != entry.destinationLevel)
            throw new IllegalStateException("rider remount vehicle is not in the player destination world");
        entry.remount = vehicle;
        return true;
    }

    private void remount(Entry entry, RoutingKey destinationKey) {
        Entity vehicle = entry.remount;
        entry.remount = null;
        var context = OwnershipContext.current().orElseThrow();
        ObjectOwnership binding = objects.current(vehicle.getUUID().toString()).orElse(null);
        boolean mounted = false;
        if (!vehicle.isRemoved() && vehicle.level() == entry.destinationLevel && binding != null
                && binding.owner().equals(context.region()) && entry.player.getVehicle() == null)
            mounted = entry.player.startRiding(vehicle, true, false);
        remountMarker(entry, mounted ? "REMOUNTED" : "REMOUNT_SKIPPED");
    }
    private void remountMarker(Entry entry, String phase) {
        if (!Boolean.getBoolean("phoenium.test.diagnostics")) return;
        try { org.slf4j.LoggerFactory.getLogger(PlayerDimensionTransitionManager.class).info(
            "PHOENIUM_PORTAL_RIDER player={} generation={} destinationWorld={} phase={}",
            entry.token.source().objectId(), entry.token.generation(), entry.destinationLevel.dimension().identifier(), phase);
        } catch (Throwable ignored) { }
    }

    boolean pending(ServerPlayer player) {
        Entry entry = active.get(key(player));
        return entry != null && transitions.isCurrent(entry.token);
    }
    boolean pendingPearlHit(ThrownEnderpearl pearl) {
        return active.values().stream().anyMatch(entry -> entry.pearlHit != null && entry.pearlHit.pearl == pearl);
    }

    /** Retains only the native ThrownEnderpearl.onHit suffix after its player teleport call. */
    boolean retainPearlHit(ThrownEnderpearl pearl, ServerLevel destination, Vec3 teleportPosition,
                           ServerPlayer player) {
        Objects.requireNonNull(pearl, "pearl");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(teleportPosition, "teleportPosition");
        Entry entry = active.get(key(player));
        if (entry == null || !entry.suspended || entry.destinationLevel != destination
                || transitions.phase(entry.token) != PlayerDimensionTransitionBoundary.Phase.SOURCE_RETIRED)
            return false;
        if (entry.pearlHit != null) throw new IllegalStateException("pearl player-hit suffix retained twice");
        if (pearl.level() != destination || pearl.isRemoved())
            throw new IllegalStateException("pearl hit continuation is not in the player destination world");
        ObjectOwnership binding = objects.current(pearl.getUUID().toString()).orElseThrow();
        entry.pearlHit = new PearlHit(pearl, access(pearl).phoenium$entityIncarnation(),
            destination, teleportPosition, binding);
        return true;
    }

    int advance() {
        if (OwnershipContext.current().isPresent())
            throw new IllegalStateException("player dimension coordinator retained owner authority");
        if (closed || !authority.running()) return 0;
        int settled = 0;
        List<Entry> snapshot = new ArrayList<>(active.values());
        snapshot.sort(Comparator.comparingLong(entry -> entry.token.generation()));
        for (Entry entry : snapshot) {
            if (active.get(key(entry.player)) != entry
                    || entry.advancing
                    || transitions.phase(entry.token) != PlayerDimensionTransitionBoundary.Phase.SOURCE_RETIRED)
                continue;
            entry.advancing = true;
            try {
                if (!validRetiredSource(entry)) {
                    if (entry.pearlHit != null)
                        throw new IllegalStateException("pearl player transfer lost its session after committed source retirement");
                    cancel(entry); continue;
                }
                RoutingKey destinationKey = destinationKey(entry);
                RegionRoute destinationRoute = directory.resolve(destinationKey).orElse(null);
                if (destinationRoute == null) continue;
                ObjectOwnership source = currentSource(entry);
                if (source == null) {
                    if (entry.pearlHit != null)
                        throw new IllegalStateException("pearl player transfer lost its binding after committed source retirement");
                    cancel(entry); continue;
                }

                ObjectOwnership destination = source.owner().equals(destinationRoute.region())
                    ? objects.updateRoutingKey(source.objectId(), destinationKey, source.objectEpoch())
                    : objects.transitionOwner(source.objectId(), destinationKey, source.objectEpoch());
                var admission = transitions.authorizeDestinationAdmission(entry.token);
                authority.execute(destinationKey, RegionalServiceKind.PLAYER_DIMENSION_DESTINATION, () -> {
                    requireDestination(entry, destinationKey, destination);
                    ((PlayerDimensionTransitionNativeAccess.PlayerSuffix) entry.player)
                        .phoenium$resumePlayerDimensionChange(entry.transition, entry.sourceLevel);
                    ObjectOwnership admitted = requireDestination(entry, destinationKey, null);
                    if (entry.player.level() != entry.destinationLevel || entry.player.isRemoved())
                        throw new IllegalStateException("player destination suffix did not publish a live destination identity");
                    transitions.destinationAdmitted(admission,
                        new PlayerDimensionTransitionBoundary.DestinationIdentity(admitted.objectId(),
                            access(entry.player).phoenium$entityIncarnation(), admitted.incarnation(),
                            authority.worldId(entry.destinationLevel)));
                    if (entry.pearlHit != null) resumePearlDamage(entry, entry.pearlHit);
                    if (entry.remount != null) remount(entry, destinationKey);
                });
                var continuation = transitions.authorizeContinuation(entry.token);
                if (entry.pearlHit != null) {
                    PearlHit hit=entry.pearlHit;
                    authority.execute(hit.binding.routingKey(), RegionalServiceKind.ENTITY_PORTAL_DESTINATION, () -> {
                        requirePearlHit(entry, hit);
                        ObjectOwnership current=objects.current(hit.binding.objectId()).orElseThrow();
                        if(current.incarnation()!=hit.binding.incarnation() || !current.objectEpoch().equals(hit.binding.objectEpoch())
                                || !current.routingKey().equals(hit.binding.routingKey()))
                            throw new IllegalStateException("pearl hit destination binding changed");
                        objects.requireCurrentOwner(current.objectId(),current.objectEpoch(),OwnershipContext.current().orElseThrow().region());
                        ((PlayerDimensionTransitionNativeAccess.PearlHitSuffix)hit.pearl)
                            .phoenium$resumePearlHitEffects(hit.destination,hit.teleportPosition);
                    });
                    hitMarker(entry,"PLAYER_TELEPORT_SETTLED");
                }
                transitions.settle(continuation);
                active.remove(key(entry.player), entry);
                settled++;
            } catch (RuntimeException | Error failure) {
                if (entry.pearlHit != null) hitMarker(entry,"FAILED:"+failure.getClass().getSimpleName());
                cancel(entry);
                throw failure;
            } finally { entry.advancing = false; }
        }
        return settled;
    }

    void worldClosing(ServerLevel level) {
        for (Entry entry : List.copyOf(active.values()))
            if (entry.sourceLevel == level || entry.destinationLevel == level) cancel(entry);
    }

    int pendingCount() { return active.size(); }

    @Override public void close() {
        if (closed) return;
        closed = true;
        nativeCall.set(null);
        active.clear();
        transitions.close();
    }

    private void requirePearlHit(Entry entry, PearlHit hit) {
        if (access(hit.pearl).phoenium$entityIncarnation() != hit.nativeIncarnation
                || hit.pearl.level() != hit.destination || hit.pearl.isRemoved()
                || entry.player.level() != hit.destination || authority.currentSession(entry.player) != entry.session)
            throw new IllegalStateException("pearl player-hit continuation became stale");
    }
    private void resumePearlDamage(Entry entry, PearlHit hit) {
        requirePearlHit(entry,hit);
        ((PlayerDimensionTransitionNativeAccess.PearlHitSuffix) hit.pearl)
            .phoenium$resumePearlPlayerDamage(entry.player);
    }

    private ObjectOwnership requireDestination(Entry entry, RoutingKey destinationKey,
                                                ObjectOwnership expected) {
        if (active.get(key(entry.player)) != entry
                || transitions.phase(entry.token) == PlayerDimensionTransitionBoundary.Phase.RETIRED)
            throw new IllegalStateException("player destination transition became stale");
        if (!authority.worldActive(entry.destinationLevel) || !entry.player.connection.isAcceptingMessages()
                || authority.currentSession(entry.player) != entry.session)
            throw new IllegalStateException("player destination is unavailable");
        ObjectOwnership current = objects.current(entry.token.source().objectId()).orElseThrow(
            () -> new IllegalStateException("player destination ownership is absent or stale"));
        if (expected != null && (current.incarnation() != expected.incarnation()
                || !current.objectEpoch().equals(expected.objectEpoch())))
            throw new IllegalStateException("player destination ownership identity changed");
        if (!current.routingKey().equals(destinationKey)
                || current.incarnation() != entry.token.source().registryIncarnation()
                || access(entry.player).phoenium$entityIncarnation() != entry.token.source().nativeIncarnation())
            throw new IllegalStateException("player destination route or incarnation changed");
        return objects.requireCurrentOwner(current.objectId(), current.objectEpoch(),
            OwnershipContext.current().orElseThrow().region());
    }

    private boolean validRetiredSource(Entry entry) {
        return active.get(key(entry.player)) == entry && transitions.isCurrent(entry.token)
            && transitions.phase(entry.token) == PlayerDimensionTransitionBoundary.Phase.SOURCE_RETIRED
            && authority.worldActive(entry.sourceLevel) && authority.worldActive(entry.destinationLevel)
            && entry.player.level() == entry.sourceLevel
            && entry.player.getRemovalReason() == Entity.RemovalReason.CHANGED_DIMENSION
            && entry.player.connection.isAcceptingMessages()
            && authority.currentSession(entry.player) == entry.session
            && access(entry.player).phoenium$entityIncarnation() == entry.token.source().nativeIncarnation();
    }

    private ObjectOwnership currentSource(Entry entry) {
        ObjectOwnership current = objects.current(entry.token.source().objectId()).orElse(null);
        return current != null && current.kind() == OwnedObjectKind.PLAYER
            && current.incarnation() == entry.token.source().registryIncarnation()
            && current.objectEpoch().equals(entry.sourceObjectEpoch)
            && current.owner().equals(entry.sourceOwner) ? current : null;
    }

    private RoutingKey destinationKey(Entry entry) {
        ChunkPos chunk = ChunkPos.containing(net.minecraft.core.BlockPos.containing(entry.transition.position()));
        return new RoutingKey(entry.token.source().destinationWorld(), chunk.x(), chunk.z());
    }

    private PlayerDimensionTransitionBoundary.SourceIdentity nativeSourceIdentity(Entry entry) {
        return new PlayerDimensionTransitionBoundary.SourceIdentity(entry.player.getUUID().toString(),
            access(entry.player).phoenium$entityIncarnation(), entry.token.source().registryIncarnation(),
            authority.worldId(entry.sourceLevel), authority.worldId(entry.destinationLevel));
    }

    private void cancel(Entry entry) {
        if (entry == null) return;
        transitions.cancel(entry.token);
        active.remove(key(entry.player), entry);
    }
    private void hitMarker(Entry entry,String phase) {
        if(!Boolean.getBoolean("phoenium.test.diagnostics")) return;
        try { org.slf4j.LoggerFactory.getLogger(PlayerDimensionTransitionManager.class).info(
            "PHOENIUM_PEARL_PLAYER_HIT player={} playerNative={} generation={} session={} destinationWorld={} owner={} phase={}",
            entry.token.source().objectId(),entry.token.source().nativeIncarnation(),entry.token.generation(),entry.session,
            entry.destinationLevel.dimension().identifier(),OwnershipContext.current().map(Object::toString).orElse("NONE"),phase);
        } catch(Throwable ignored) { }
    }

    private static FullEntityFacts.Access access(Entity entity) {
        if (!(entity instanceof FullEntityFacts.Access access))
            throw new IllegalStateException("dimension-transition entity lacks native incarnation access");
        return access;
    }

    private static <T> IdentityKey<T> key(T value) { return new IdentityKey<>(value); }

    private static final class IdentityKey<T> {
        private final T value;
        private final int hash;
        private IdentityKey(T value) { this.value = Objects.requireNonNull(value); this.hash = System.identityHashCode(value); }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            return other instanceof IdentityKey<?> identity && identity.value == value;
        }
    }

    private static final class Entry {
        final PlayerDimensionTransitionBoundary.Token token;
        final ServerPlayer player;
        final TeleportTransition transition;
        final ServerLevel sourceLevel;
        final ServerLevel destinationLevel;
        final dev.phoenium.core.OwnerEpoch sourceObjectEpoch;
        final dev.phoenium.region.RegionId sourceOwner;
        final long session;
        volatile PlayerDimensionTransitionBoundary.SourceRemoval sourceRemoval;
        volatile PearlHit pearlHit;
        volatile Entity remount;
        volatile boolean suspended;
        volatile boolean advancing;

        Entry(PlayerDimensionTransitionBoundary.Token token, ServerPlayer player,
              TeleportTransition transition, ServerLevel sourceLevel, ServerLevel destinationLevel,
              dev.phoenium.core.OwnerEpoch sourceObjectEpoch, dev.phoenium.region.RegionId sourceOwner, long session) {
            this.token = token;
            this.player = player;
            this.transition = transition;
            this.sourceLevel = sourceLevel;
            this.destinationLevel = destinationLevel;
            this.sourceObjectEpoch = sourceObjectEpoch;
            this.sourceOwner = sourceOwner;
            this.session = session;
        }
    }

    private record PearlHit(ThrownEnderpearl pearl, long nativeIncarnation,
                            ServerLevel destination, Vec3 teleportPosition, ObjectOwnership binding) { }
}
