package dev.phoenium.live;

import dev.phoenium.chunk.FullEntityFacts;
import dev.phoenium.ownership.*;
import dev.phoenium.service.RegionalServiceKind;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

/** The native onHit suffix before player cooldown/teleport, after collision and particles.
 * The pearl callback returns first. Only the player's own source turn can invoke teleport. */
final class PearlPlayerHitHandoff implements AutoCloseable {
    interface Sessions {
        ServerPlayer current(UUID uuid);
        long session(ServerPlayer player);
    }
    private final NativePortalTransfer.Authority authority;
    private final ObjectOwnershipRegistry objects;
    private final PlayerDimensionTransitionManager dimensions;
    private final Sessions sessions;
    private final Map<IdentityKey, Hit> pending = new ConcurrentHashMap<>();
    private final Set<Hit> advancing = ConcurrentHashMap.newKeySet();
    private final AtomicLong generations = new AtomicLong();
    private boolean closed;
    PearlPlayerHitHandoff(NativePortalTransfer.Authority authority, ObjectOwnershipRegistry objects,
            PlayerDimensionTransitionManager dimensions, Sessions sessions) {
        this.authority=authority; this.objects=objects; this.dimensions=dimensions; this.sessions=sessions;
    }
    Entity currentOwner(Entity owner) {
        if (!(owner instanceof ServerPlayer player)) return owner;
        ServerPlayer current=sessions.current(player.getUUID());
        return current != null && sessions.session(current) >= 0 ? current : null;
    }
    boolean capture(ThrownEnderpearl pearl, ServerLevel destination, Vec3 position, ServerPlayer player) {
        if (closed || !authority.running() || OwnershipContext.current().isEmpty()) return false;
        ObjectOwnership source=objects.current(player.getUUID().toString()).orElseThrow();
        if (source.owner().equals(OwnershipContext.current().orElseThrow().region())) return false;
        ObjectOwnership projectile=objects.current(pearl.getUUID().toString()).orElseThrow();
        objects.requireCurrentOwner(projectile.objectId(), projectile.objectEpoch(), OwnershipContext.current().orElseThrow().region());
        long session=sessions.session(player);
        if (session < 0 || sessions.current(player.getUUID()) != player || player.isRemoved()
                || pearl.isRemoved() || pearl.level() != destination || source.kind() != OwnedObjectKind.PLAYER)
            throw new IllegalStateException("pearl hit player/session is not current");
        Hit hit=new Hit(generations.incrementAndGet(),pearl,player,player.level(),destination,position,
            pearl.isOnPortalCooldown(),source,projectile,incarnation(player),incarnation(pearl),session);
        if(pending.putIfAbsent(new IdentityKey(pearl),hit)!=null) throw new IllegalStateException("pearl player hit captured twice");
        marker(hit,"HIT_CAPTURED"); return true;
    }
    boolean pending(ThrownEnderpearl pearl) { return pending.containsKey(new IdentityKey(pearl)) || dimensions.pendingPearlHit(pearl); }
    int advance() {
        if(OwnershipContext.current().isPresent()) throw new IllegalStateException("pearl hit coordinator retained regional owner");
        if(closed || !authority.running()) return 0;
        var entries=new ArrayList<>(pending.values()); entries.sort(Comparator.comparingLong(Hit::generation));
        int settled=0;
        for(Hit hit:entries) {
            if(pending.get(new IdentityKey(hit.pearl)) != hit || !advancing.add(hit)) continue;
            try {
                if(!valid(hit)) { retirePearl(hit,"STALE_HIT"); continue; }
                ServerPlayer[] result=new ServerPlayer[1];
                authority.execute(hit.playerBinding.routingKey(),RegionalServiceKind.ENTITY_PORTAL_SOURCE,()->{
                    if(!valid(hit)) throw new IllegalStateException("pearl hit source changed before admission");
                    objects.requireCurrentOwner(hit.playerBinding.objectId(), hit.playerBinding.objectEpoch(),OwnershipContext.current().orElseThrow().region());
                    if(hit.cooldown) hit.player.setPortalCooldown();
                    result[0]=hit.player.teleport(new TeleportTransition(hit.destination,hit.position,Vec3.ZERO,
                        0.0F,0.0F,Relative.union(Relative.ROTATION,Relative.DELTA),TeleportTransition.DO_NOTHING));
                });
                if(result[0] != null && dimensions.retainPearlHit(hit.pearl,hit.destination,hit.position,hit.player)) {
                    marker(hit,"PLAYER_TELEPORT_SOURCE_RETIRED");
                } else if(result[0] == null) { pearlEffects(hit); marker(hit,"PLAYER_TELEPORT_NATIVE_NULL"); }
                else {
                    // Same-world native teleport has no dimension continuation.
                    ObjectOwnership current=objects.current(hit.playerBinding.objectId()).orElseThrow();
                    authority.execute(current.routingKey(),RegionalServiceKind.PLAYER_DIMENSION_DESTINATION,()->{
                        if(sessions.current(hit.player.getUUID()) != hit.player || sessions.session(hit.player) != hit.session)
                            throw new IllegalStateException("pearl hit session changed after native teleport");
                        ((PlayerDimensionTransitionNativeAccess.PearlHitSuffix)hit.pearl).phoenium$resumePearlPlayerDamage(hit.player);
                    });
                    pearlEffects(hit); marker(hit,"PLAYER_TELEPORT_SETTLED");
                }
                settled++;
            } catch(RuntimeException | Error failure) { marker(hit,"FAILED:"+failure.getClass().getSimpleName()); throw failure; }
            finally { pending.remove(new IdentityKey(hit.pearl),hit); advancing.remove(hit); }
        }
        return settled;
    }
    private boolean valid(Hit hit) {
        var player=objects.current(hit.playerBinding.objectId()).orElse(null);
        var pearl=objects.current(hit.pearlBinding.objectId()).orElse(null);
        return !closed && pending.get(new IdentityKey(hit.pearl)) == hit
            && authority.running() && authority.worldActive(hit.source) && authority.worldActive(hit.destination)
            && sessions.current(hit.player.getUUID()) == hit.player && sessions.session(hit.player) == hit.session
            && hit.player.connection.isAcceptingMessages() && hit.player.level() == hit.source && !hit.player.isRemoved()
            && incarnation(hit.player) == hit.playerIncarnation && same(player,hit.playerBinding)
            && !hit.pearl.isRemoved() && hit.pearl.level() == hit.destination && incarnation(hit.pearl) == hit.pearlIncarnation
            && same(pearl,hit.pearlBinding);
    }
    private void pearlEffects(Hit hit) {
        authority.execute(hit.pearlBinding.routingKey(),RegionalServiceKind.ENTITY_PORTAL_DESTINATION,()->{
            requirePearl(hit);
            ((PlayerDimensionTransitionNativeAccess.PearlHitSuffix)hit.pearl).phoenium$resumePearlHitEffects(hit.destination,hit.position);
        });
    }
    private void retirePearl(Hit hit,String reason) {
        if(authority.worldActive(hit.destination) && !hit.pearl.isRemoved()
                && same(objects.current(hit.pearlBinding.objectId()).orElse(null),hit.pearlBinding))
            authority.execute(hit.pearlBinding.routingKey(),RegionalServiceKind.ENTITY_PORTAL_DESTINATION,()->{
                requirePearl(hit); hit.pearl.discard();
            });
        marker(hit,reason);
    }
    private void requirePearl(Hit hit) {
        if(hit.pearl.level()!=hit.destination || hit.pearl.isRemoved() || incarnation(hit.pearl)!=hit.pearlIncarnation
                || !same(objects.current(hit.pearlBinding.objectId()).orElse(null),hit.pearlBinding))
            throw new IllegalStateException("retained pearl hit incarnation changed");
        objects.requireCurrentOwner(hit.pearlBinding.objectId(),hit.pearlBinding.objectEpoch(),OwnershipContext.current().orElseThrow().region());
    }
    private static boolean same(ObjectOwnership current,ObjectOwnership expected) {
        return current!=null && current.incarnation()==expected.incarnation()
            && current.objectEpoch().equals(expected.objectEpoch()) && current.routingKey().equals(expected.routingKey());
    }
    void worldClosing(ServerLevel level) { pending.values().removeIf(hit->hit.source==level || hit.destination==level); }
    @Override public void close() { closed=true; pending.clear(); }
    private static long incarnation(Entity entity) { return ((FullEntityFacts.Access)entity).phoenium$entityIncarnation(); }
    private static void marker(Hit hit,String phase) {
        if(!Boolean.getBoolean("phoenium.test.diagnostics")) return;
        try { org.slf4j.LoggerFactory.getLogger(PearlPlayerHitHandoff.class).info(
            "PHOENIUM_PEARL_PLAYER_HIT generation={} pearl={} native={} player={} playerNative={} session={} sourceWorld={} destinationWorld={} owner={} phase={}",
            hit.generation,hit.pearl.getUUID(),hit.pearlIncarnation,hit.player.getUUID(),hit.playerIncarnation,hit.session,
            hit.source.dimension().identifier(),hit.destination.dimension().identifier(),OwnershipContext.current().map(Object::toString).orElse("NONE"),phase);
        } catch(Throwable ignored) { }
    }
    private record Hit(long generation,ThrownEnderpearl pearl,ServerPlayer player,ServerLevel source,
        ServerLevel destination,Vec3 position,boolean cooldown,ObjectOwnership playerBinding,ObjectOwnership pearlBinding,
        long playerIncarnation,long pearlIncarnation,long session) { }
    private static final class IdentityKey {
        final ThrownEnderpearl pearl;
        IdentityKey(ThrownEnderpearl pearl) { this.pearl=pearl; }
        @Override public int hashCode() { return System.identityHashCode(pearl); }
        @Override public boolean equals(Object value) { return value instanceof IdentityKey key && pearl == key.pearl; }
    }
}
