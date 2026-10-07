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
import net.minecraft.world.level.block.EndPortalBlock;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.level.portal.TeleportTransition;
import org.slf4j.LoggerFactory;

/** Exact vanilla mob tick families retained before portal lookup. */
public final class MobPortalTransitionManager implements AutoCloseable {
    private final NativePortalTransfer.Authority authority;
    private final ObjectOwnershipRegistry objects;
    private final CrossDimensionReplacementBoundary replacements = new CrossDimensionReplacementBoundary();
    private final Map<IdentityKey, Entry> active = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<IdentityKey, Entry> graphPending = new java.util.concurrent.ConcurrentHashMap<>();
    private final ThreadLocal<Mob> ticking = new ThreadLocal<>();
    private volatile boolean closed;
    public MobPortalTransitionManager(NativePortalTransfer.Authority authority, ObjectOwnershipRegistry objects) {
        this.authority = Objects.requireNonNull(authority); this.objects = Objects.requireNonNull(objects);
    }
    private enum NativeProfile { DIRECT_MOB_TICK, ENDERMITE_PREFIX, SILVERFISH_PREFIX, ZOMBIE_PREFIX, CUBE_OUTER_TAIL, HORSE_OUTER_TAIL, VILLAGER_OUTER_TAIL,
        /** Any other Mob whose every tick()/baseTick() override is a reviewed MobPortalOuterTails pin. */
        REVIEWED_OUTER_TAILS }
    /** Exact vanilla classes with Mob.tick and Mob.baseTick as their declaring callers. */
    private static final Set<Class<?>> DIRECT_MOB_TICK = Set.of(
        net.minecraft.world.entity.monster.Blaze.class,
        net.minecraft.world.entity.monster.skeleton.Bogged.class,
        net.minecraft.world.entity.animal.chicken.Chicken.class,
        net.minecraft.world.entity.animal.cow.Cow.class,
        net.minecraft.world.entity.monster.ElderGuardian.class,
        net.minecraft.world.entity.monster.EnderMan.class,
        net.minecraft.world.entity.monster.Ghast.class,
        net.minecraft.world.entity.animal.goat.Goat.class,
        net.minecraft.world.entity.monster.Guardian.class,
        net.minecraft.world.entity.monster.hoglin.Hoglin.class,
        net.minecraft.world.entity.animal.golem.IronGolem.class,
        net.minecraft.world.entity.animal.cow.MushroomCow.class,
        net.minecraft.world.entity.animal.feline.Ocelot.class,
        net.minecraft.world.entity.monster.skeleton.Parched.class,
        net.minecraft.world.entity.animal.pig.Pig.class,
        net.minecraft.world.entity.monster.piglin.Piglin.class,
        net.minecraft.world.entity.monster.piglin.PiglinBrute.class,
        net.minecraft.world.entity.monster.illager.Pillager.class,
        net.minecraft.world.entity.monster.Ravager.class,
        net.minecraft.world.entity.animal.sheep.Sheep.class,
        net.minecraft.world.entity.monster.skeleton.Skeleton.class,
        net.minecraft.world.entity.animal.golem.SnowGolem.class,
        net.minecraft.world.entity.monster.skeleton.Stray.class,
        net.minecraft.world.entity.animal.turtle.Turtle.class,
        net.minecraft.world.entity.monster.illager.Vindicator.class,
        net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader.class,
        net.minecraft.world.entity.monster.Witch.class,
        net.minecraft.world.entity.monster.skeleton.WitherSkeleton.class,
        net.minecraft.world.entity.monster.Zoglin.class);
    private static final Set<Class<?>> ZOMBIE_PREFIX = Set.of(
        net.minecraft.world.entity.monster.zombie.Zombie.class,
        net.minecraft.world.entity.monster.zombie.Drowned.class,
        net.minecraft.world.entity.monster.zombie.Husk.class,
        net.minecraft.world.entity.monster.zombie.ZombifiedPiglin.class);
    private static final Set<Class<?>> HORSE_OUTER_TAIL = Set.of(
        net.minecraft.world.entity.animal.equine.Horse.class,
        net.minecraft.world.entity.animal.equine.Donkey.class,
        net.minecraft.world.entity.animal.equine.Mule.class,
        net.minecraft.world.entity.animal.equine.SkeletonHorse.class,
        net.minecraft.world.entity.animal.equine.ZombieHorse.class,
        net.minecraft.world.entity.animal.equine.Llama.class,
        net.minecraft.world.entity.animal.equine.TraderLlama.class);
    private static final Set<Class<?>> VILLAGER_OUTER_TAIL = Set.of(
        net.minecraft.world.entity.npc.villager.Villager.class);
    private static NativeProfile profile(Entity entity) { return profile(entity.getClass()); }
    private static NativeProfile profile(Class<?> type) {
        if (DIRECT_MOB_TICK.contains(type)) return NativeProfile.DIRECT_MOB_TICK;
        // Endermite.tick sets yBodyRot before super.tick and has no suffix after it.
        // The Mob.tick suspension point therefore retains its exact already-run prefix.
        if (type == net.minecraft.world.entity.monster.Endermite.class) return NativeProfile.ENDERMITE_PREFIX;
        // Silverfish.tick has the same pinned method body and no outer suffix.
        if (type == net.minecraft.world.entity.monster.Silverfish.class) return NativeProfile.SILVERFISH_PREFIX;
        if (ZOMBIE_PREFIX.contains(type)) return NativeProfile.ZOMBIE_PREFIX;
        if (HORSE_OUTER_TAIL.contains(type)) return NativeProfile.HORSE_OUTER_TAIL;
        if (VILLAGER_OUTER_TAIL.contains(type)) return NativeProfile.VILLAGER_OUTER_TAIL;
        if (type == net.minecraft.world.entity.monster.cubemob.MagmaCube.class
                || type == net.minecraft.world.entity.monster.cubemob.Slime.class) return NativeProfile.CUBE_OUTER_TAIL;
        if (Mob.class.isAssignableFrom(type) && MobPortalTailPlans.plan(type) != null) return NativeProfile.REVIEWED_OUTER_TAILS;
        return null;
    }
    public static boolean supports(Entity entity) { return profile(entity) != null; }
    /** Class-level selection used by the completeness gate; identical to the per-instance decision. */
    public static boolean supportsClass(Class<?> type) { return profile(type) != null; }
    public void begin(Mob mob) {
        if (closed || !authority.running() || !supports(mob)) return;
        if (ticking.get() != null) throw new IllegalStateException("nested mob portal tick");
        ticking.set(mob);
    }
    public boolean suspendDestination(Mob mob, PortalProcessor processor) {
        NativeProfile profile = profile(mob);
        String rejected = profile == null ? "EXACT_CLASS_NOT_BOUND"
            : ticking.get() != mob ? "NO_RETAINED_MOB_TICK"
            : closed ? "CLOSED" : !authority.running() ? "RUNTIME_NOT_RUNNING"
            : mob.isRemoved() ? "REMOVED" : !(mob.level() instanceof ServerLevel) ? "NON_SERVER_WORLD"
            : mob.portalProcess != processor || processor == null ? "PROCESSOR_CHANGED" : null;
        if (rejected != null) { selection(mob, null, false, rejected); return false; }
        ServerLevel level = (ServerLevel) mob.level();
        Portal portal = ((PearlPortalProcessorAccess) processor).phoenium$portal();
        if (!(portal instanceof NetherPortalBlock) && !(portal instanceof EndPortalBlock)) {
            selection(mob, null, false, "PORTAL_FAMILY_NOT_BOUND"); return false;
        }
        if (mob.isPassenger()) {
            selection(mob, null, false, "PASSENGER_GRAPH_NOT_BOUND");
            throw new IllegalStateException("mob passenger portal entry is not a native root");
        }
        if(active.containsKey(key(mob)))throw new IllegalStateException("mob portal was captured twice");
        List<net.minecraft.server.level.ServerPlayer> riders = mob.isVehicle() ? PortalRiders.leafPlayerRiders(mob) : List.of();
        PortalPassengerGraphSnapshot graph = mob.isVehicle() && riders.isEmpty()
            ? PortalPassengerGraphSnapshot.capture(mob, objects) : null;
        ObjectOwnership binding = objects.current(mob.getUUID().toString()).orElseThrow();
        objects.requireCurrentOwner(binding.objectId(), binding.objectEpoch(), OwnershipContext.current().orElseThrow().region());
        var token = replacements.begin(new CrossDimensionReplacementBoundary.SourceIdentity(binding.objectId(),
            ((FullEntityFacts.Access) mob).phoenium$entityIncarnation(), binding.incarnation()));
        Entry entry = new Entry(mob, level, processor, null, binding, token, profile);
        entry.riders = riders;
        // The one reviewed caller local (int airSupply = getAirSupply()) is unchanged until the cut.
        if (profile == NativeProfile.REVIEWED_OUTER_TAILS) entry.airSupply = mob.getAirSupply();
        if (graph != null) {
            entry.graph = graph;
            entry.graphTokens.put(mob, token);
            try {
                for (Entity node : graph.preorder()) {
                    if (node == mob) continue;
                    var member = graph.member(node);
                    var childToken = replacements.begin(new CrossDimensionReplacementBoundary.SourceIdentity(
                        member.uuid().toString(), member.nativeIncarnation(), member.binding().incarnation()));
                    entry.graphTokens.put(node, childToken);
                    NativePortalTransfer.retained(node, member.binding(), childToken, null, "CAPTURED_GRAPH_PASSENGER");
                }
            } catch (RuntimeException | Error failure) {
                for (var childToken : entry.graphTokens.values()) replacements.cancel(childToken);
                throw failure;
            }
        }
        entry.destinationPending = true;
        if (active.putIfAbsent(key(mob), entry) != null) {
            for (var memberToken : entry.graphTokens.values()) replacements.cancel(memberToken);
            if (graph == null) replacements.cancel(token);
            throw new IllegalStateException("mob portal was captured twice");
        }
        if (graph != null) {
            try {
                for (Entity member : graph.preorder())
                    if (graphPending.putIfAbsent(key(member), entry) != null)
                        throw new IllegalStateException("passenger graph member already has a retained transfer");
            } catch (RuntimeException | Error failure) {
                releaseGraphMembers(entry);
                active.remove(key(mob), entry);
                for (var memberToken : entry.graphTokens.values()) replacements.cancel(memberToken);
                throw failure;
            }
        }
        NativePortalTransfer.retained(mob, binding, token, null, "CAPTURED");
        selection(mob, entry, true, "REVIEWED_NATIVE_CALLER");
        return true;
    }
    private void selection(Mob mob, Entry entry, boolean selected, String reason) {
        if (!Boolean.getBoolean("phoenium.test.diagnostics")) return;
        try {
            LoggerFactory.getLogger(MobPortalTransitionManager.class).info(
                "PHOENIUM_MOB_PORTAL_SELECTION type={} class={} uuid={} runtimeId={} nativeIncarnation={} selection={} profile={} reason={} phase=BEFORE_DESTINATION_EFFECTS generation={} sourceWorld={} sourceRoute={} currentRoute={} destinationWorld={} activeContext={} disposition={}",
                mob.getType(), mob.getClass().getName(), mob.getUUID(), mob.getId(),
                mob instanceof FullEntityFacts.Access access ? access.phoenium$entityIncarnation() : "UNAVAILABLE",
                selected, entry == null ? "UNAVAILABLE" : entry.profile, reason,
                entry == null ? "UNAVAILABLE" : entry.token.generation(),
                mob.level() instanceof ServerLevel level ? authority.worldId(level) : "UNAVAILABLE",
                entry == null ? "UNAVAILABLE" : entry.binding.routingKey(),
                objects.current(mob.getUUID().toString()).map(Object::toString).orElse("UNAVAILABLE"),
                entry == null || entry.transition == null ? "UNAVAILABLE" : authority.worldId(entry.transition.newLevel()),
                OwnershipContext.current().map(Object::toString).orElse("NONE"),
                selected ? "RETAINED" : "NATIVE_FALLTHROUGH");
        } catch (Throwable ignored) { /* Never replace the native failure with diagnostic work. */ }
    }
    public boolean suspended(Mob mob) { return active.containsKey(key(mob)); }
    /** Called only at the native ServerLevel root/passenger tick boundary. */
    public boolean graphTickSuspended(Entity entity, boolean passenger) {
        Entry entry = graphPending.get(key(entity));
        return entry != null && entry.retained && !entry.consumed
            && active.get(key(entry.mob)) == entry && (entry.mob != entity) == passenger;
    }
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
                        authority,() -> current(entry),
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
                if (permitted && entry.graph == null) {
                    if (entry.visibilityReady == null)
                        entry.visibilityReady = authority.demandDestinationEntityVisibility(entry.mob, entry.transition);
                    if (!entry.visibilityReady.getAsBoolean()) {
                        if (entry.visibilityDeadline.expired(authority.serverTick()))
                            throw new IllegalStateException("mob destination entity visibility deadline: " + entry.visibilityReady);
                        continue;
                    }
                }
                NativePortalTransfer transfer = null;
                NativePortalGraphTransferPlan graphPlan = null;
                if (permitted && entry.graph != null) {
                    if (entry.graphPlan == null)
                        entry.graphPlan = new NativePortalGraphTransferPlan(authority, objects, replacements,
                            entry.graph, entry.graphTokens, entry.transition,
                            () -> active.get(key(entry.mob)) == entry && authority.running()
                                && entry.graphPlan != null && entry.graphPlan.visibilityCurrent());
                    graphPlan = entry.graphPlan;
                    if (!graphPlan.ready()) {
                        if (entry.visibilityDeadline.expired(authority.serverTick()))
                            throw new IllegalStateException("mob graph destination visibility deadline");
                        continue;
                    }
                    authority.execute(entry.binding.routingKey(), RegionalServiceKind.ENTITY_PORTAL_SOURCE,
                        () -> entry.graph.validateIntact(objects));
                } else if (permitted) {
                    transfer = new NativePortalTransfer(authority, objects, replacements, entry.token,
                        entry.mob, entry.binding, entry.transition, () -> active.get(key(entry.mob)) == entry
                            && entry.visibilityReady.getAsBoolean());
                    if (!entry.riders.isEmpty()) transfer.bindPlayerRiders(entry.riders);
                }
                Entity result = graphPlan != null ? graphPlan.invoke() : transfer == null ? null : transfer.run();
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
                    if (entry.profile == NativeProfile.REVIEWED_OUTER_TAILS)
                        MobPortalTailPlans.plan(entry.mob.getClass()).resumeBase(entry.mob, entry.airSupply);
                    ((MobPortalNativeAccess.LivingSuffix) entry.mob).phoenium$resumeMobLivingTickSuffix();
                    ((MobPortalNativeAccess.MobSuffix) entry.mob).phoenium$resumeMobTickSuffix();
                    if (entry.mob instanceof net.minecraft.world.entity.monster.cubemob.AbstractCubeMob cube)
                        ((MobPortalNativeAccess.CubeSuffix) cube).phoenium$resumeMobCubeTickSuffix();
                    if (entry.profile == NativeProfile.HORSE_OUTER_TAIL)
                        ((MobPortalNativeAccess.HorseSuffix) entry.mob).phoenium$resumeMobHorseTickSuffix();
                    if (entry.profile == NativeProfile.VILLAGER_OUTER_TAIL)
                        ((MobPortalNativeAccess.VillagerSuffix) entry.mob).phoenium$resumeMobVillagerTickSuffix();
                    if (entry.profile == NativeProfile.REVIEWED_OUTER_TAILS)
                        MobPortalTailPlans.plan(entry.mob.getClass()).resumeTick(entry.mob);
                });
                replacements.settle(suffix);
                if (graphPlan != null) {
                    graphPlan.settleChildren();
                    graphPlan.settleRoot(result);
                } else if (transfer != null) transfer.settled();
                else NativePortalTransfer.retained(entry.mob, entry.binding, entry.token, null, "SETTLED_NATIVE_NO_TRANSFER");
                active.remove(key(entry.mob), entry); releaseGraphMembers(entry); settled++;
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
            && (entry.riders.isEmpty() || entry.riders.equals(PortalRiders.leafPlayerRiders(entry.mob)))
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
        if (entry.graphPlan != null) entry.graphPlan.cancelUnsettled();
        else for (var member : entry.graphTokens.entrySet()) {
            if (member.getKey() == entry.mob) continue;
            if (replacements.cancel(member.getValue()))
                NativePortalTransfer.retained(member.getKey(), entry.graph.member(member.getKey()).binding(),
                    member.getValue(), entry.transition == null ? null : entry.transition.newLevel(), reason);
        }
        replacements.cancel(entry.token); active.remove(key(entry.mob), entry);
        releaseGraphMembers(entry);
    }
    private void releaseGraphMembers(Entry entry) {
        if (entry.graph == null) return;
        for (Entity member : entry.graph.preorder()) graphPending.remove(key(member), entry);
    }
    public int pendingCount() { return active.size(); }
    @Override public void close() {
        closed = true;
        List<Entry> entries=List.copyOf(active.values());
        for(Entry entry:entries) cancel(entry,"SHUTDOWN");
        replacements.close(); ticking.remove();
    }
    private static IdentityKey key(Entity entity) { return new IdentityKey(entity); }
    private static final class IdentityKey {
        final Entity entity;
        IdentityKey(Entity entity) { this.entity=Objects.requireNonNull(entity); }
        @Override public int hashCode() { return System.identityHashCode(entity); }
        @Override public boolean equals(Object other) { return other instanceof IdentityKey key && entity == key.entity; }
    }
    private static final class Entry {
        final Mob mob; final ServerLevel level; final PortalProcessor processor;
        TeleportTransition transition; final ObjectOwnership binding;
        final Portal portal; final BlockPos entryPosition; final int portalTime; final boolean insidePortal;
        final net.minecraft.world.phys.Vec3 position,motion;
        final float yRot,xRot;final net.minecraft.world.entity.Pose pose;
        final CrossDimensionReplacementBoundary.Token token;
        final NativeProfile profile;
        PortalPassengerGraphSnapshot graph;
        final IdentityHashMap<Entity, CrossDimensionReplacementBoundary.Token> graphTokens = new IdentityHashMap<>();
        NativePortalGraphTransferPlan graphPlan;
        List<net.minecraft.server.level.ServerPlayer> riders = List.of();
        volatile boolean retained, consumed, destinationPending, advancing;
        int airSupply;
        final PortalVisibilityDeadline visibilityDeadline = new PortalVisibilityDeadline();
        java.util.function.BooleanSupplier visibilityReady;
        Entry(Mob mob, ServerLevel level, PortalProcessor processor, TeleportTransition transition,
                ObjectOwnership binding, CrossDimensionReplacementBoundary.Token token, NativeProfile profile) {
            this.mob=mob; this.level=level; this.processor=processor; this.transition=transition; this.binding=binding; this.token=token; this.profile=profile;
            this.position=mob.position();this.motion=mob.getDeltaMovement();this.yRot=mob.getYRot();this.xRot=mob.getXRot();this.pose=mob.getPose();
            this.portal=processor == null ? null : ((PearlPortalProcessorAccess) processor).phoenium$portal();
            this.entryPosition=processor == null ? null : processor.getEntryPosition().immutable();
            this.portalTime=processor == null ? 0 : processor.getPortalTime();
            this.insidePortal=processor != null && processor.isInsidePortalThisTick();
        }
    }
}
