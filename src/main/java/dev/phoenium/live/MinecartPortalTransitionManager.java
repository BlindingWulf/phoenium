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
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PortalProcessor;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.entity.vehicle.minecart.Minecart;
import net.minecraft.world.entity.vehicle.minecart.MinecartChest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.EndPortalBlock;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.phys.Vec3;

/** Retains the pinned AbstractMinecart.tick:280 portal cut for exact supported standalone minecart types. */
public final class MinecartPortalTransitionManager implements AutoCloseable {
    private final NativePortalTransfer.Authority authority;
    private final ObjectOwnershipRegistry objects;
    private final CrossDimensionReplacementBoundary replacements = new CrossDimensionReplacementBoundary();
    private final ConcurrentHashMap<IdentityKey, Entry> active = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<EntityKey, Entry> graphPending = new ConcurrentHashMap<>();
    private final ThreadLocal<Capture> ticking = new ThreadLocal<>();
    private volatile boolean closed;

    public MinecartPortalTransitionManager(NativePortalTransfer.Authority authority,
            ObjectOwnershipRegistry objects) {
        this.authority = Objects.requireNonNull(authority);
        this.objects = Objects.requireNonNull(objects);
    }

    public void begin(AbstractMinecart cart) {
        if (closed || !authority.running() || !supported(cart)) return;
        if (ticking.get() != null) throw new IllegalStateException("nested minecart portal tick");
        if (active.containsKey(key(cart))) throw new IllegalStateException("pending minecart resumed native tick");
        ticking.set(new Capture(cart, cart.getYRot(), cart.position()));
    }

    public boolean suspendDestination(AbstractMinecart cart, PortalProcessor processor) {
        Capture capture = ticking.get();
        if (capture == null && active.containsKey(key(cart)))
            throw new IllegalStateException("second minecart portal call collided with retained continuation");
        if (capture == null || capture.cart != cart || closed || !authority.running() || cart.isRemoved()
                || !(cart.level() instanceof ServerLevel level) || cart.portalProcess != processor
                || !supported(cart)) return false;
        if (OwnershipContext.current().isEmpty())
            throw new IllegalStateException("minecart portal suspension lacks source owner");
        Portal portal = ((PearlPortalProcessorAccess) processor).phoenium$portal();
        if (!(portal instanceof NetherPortalBlock) && !(portal instanceof EndPortalBlock)) return false;
        List<net.minecraft.server.level.ServerPlayer> riders = cart.isVehicle() ? PortalRiders.leafPlayerRiders(cart) : List.of();
        if (cart.isPassenger())
            throw new IllegalStateException("minecart passenger cannot be a native portal root");
        PortalPassengerGraphSnapshot graph = cart.isVehicle() && riders.isEmpty()
            ? PortalPassengerGraphSnapshot.capture(cart, objects) : null;
        if (active.containsKey(key(cart))) throw new IllegalStateException("minecart portal captured twice");
        ObjectOwnership binding = objects.current(cart.getUUID().toString()).orElseThrow();
        objects.requireCurrentOwner(binding.objectId(), binding.objectEpoch(),
            OwnershipContext.current().orElseThrow().region());
        var token = replacements.begin(new CrossDimensionReplacementBoundary.SourceIdentity(binding.objectId(),
            ((FullEntityFacts.Access) cart).phoenium$entityIncarnation(), binding.incarnation()));
        Entry entry = new Entry(cart, level, processor, portal, binding, token, capture);
        entry.riders = riders;
        entry.graph = graph;
        entry.graphTokens.put(cart, token);
        try {
            if (graph != null) {
                for (Entity member : graph.preorder()) {
                    if (member == cart) continue;
                    var captured = graph.member(member);
                    var childToken = replacements.begin(new CrossDimensionReplacementBoundary.SourceIdentity(
                        captured.uuid().toString(), captured.nativeIncarnation(), captured.binding().incarnation()));
                    entry.graphTokens.put(member, childToken);
                    NativePortalTransfer.retained(member, captured.binding(), childToken, null, "CAPTURED_GRAPH_PASSENGER");
                }
            }
            if (active.putIfAbsent(key(cart), entry) != null)
                throw new IllegalStateException("minecart portal captured twice");
            if (graph != null)
                for (Entity member : graph.preorder())
                    if (graphPending.putIfAbsent(new EntityKey(member), entry) != null)
                        throw new IllegalStateException("minecart portal graph member already pending");
        } catch (RuntimeException | Error failure) {
            active.remove(key(cart), entry);
            releaseGraphMembers(entry);
            for (var captured : entry.graphTokens.values()) replacements.cancel(captured);
            throw failure;
        }
        capture.entry = entry;
        NativePortalTransfer.retained(cart, binding, token, null, "CAPTURED_MINECART_DIRECT_TICK");
        return true;
    }

    /** Same pinned ServerLevel root/passenger tick fence used by captured mob and boat graphs. */
    public boolean graphTickSuspended(Entity entity, boolean passenger) {
        Entry entry = graphPending.get(new EntityKey(entity));
        return entry != null && entry.retained && !entry.consumed
            && active.get(key(entry.cart)) == entry && (entry.cart != entity) == passenger;
    }

    public boolean suspended(AbstractMinecart cart) { return active.containsKey(key(cart)); }

    public boolean complete(AbstractMinecart cart) {
        Capture capture = ticking.get();
        if (capture == null || capture.cart != cart || capture.entry == null) return false;
        capture.entry.retained = true;
        return true;
    }

    public void finish(AbstractMinecart cart) {
        Capture capture = ticking.get();
        if (capture != null && capture.cart == cart) {
            if (capture.entry != null && !capture.entry.retained) cancel(capture.entry, "UNRETAINED_PREFIX");
            ticking.set(null);
        }
    }

    public int advance() {
        if (OwnershipContext.current().isPresent())
            throw new IllegalStateException("minecart coordinator retained source owner");
        if (closed || !authority.running()) return 0;
        List<Entry> entries = new ArrayList<>(active.values());
        entries.sort(Comparator.comparingLong(e -> e.token.generation()));
        int settled = 0;
        for (Entry entry : entries) {
            if (!entry.retained || entry.advancing || active.get(key(entry.cart)) != entry) continue;
            if (!current(entry)) { cancel(entry, "STALE_BEFORE_NATIVE"); continue; }
            entry.advancing = true;
            try {
                if (!entry.destinationPrepared) {
                    NativePortalTransfer.retained(entry.cart, entry.binding, entry.token, null,
                        "DESTINATION_PREPARING");
                    entry.transition = PortalDestinationObservation.prepare(entry.cart, entry.binding, entry.token,
                        authority, () -> current(entry),
                        () -> entry.processor.getPortalDestination(entry.level, entry.cart));
                    if (!current(entry)) { cancel(entry, "STALE_AFTER_PREPARATION"); continue; }
                    replacements.prepared(entry.token);
                    entry.destinationPrepared = true;
                }
                boolean permitted = entry.transition != null
                    && entry.level.isAllowedToEnterPortal(entry.transition.newLevel())
                    && (entry.level.dimension() == entry.transition.newLevel().dimension()
                        || entry.cart.canTeleport(entry.level, entry.transition.newLevel()));
                if (permitted && entry.level.dimension() == entry.transition.newLevel().dimension())
                    throw new IllegalStateException("same-dimension minecart portal needs a separate adapter");
                if (permitted && entry.graph == null) {
                    if (entry.visibilityReady == null)
                        entry.visibilityReady = authority.demandDestinationEntityVisibility(entry.cart, entry.transition);
                    if (!entry.visibilityReady.getAsBoolean()) {
                        if (entry.visibilityDeadline.expired(authority.serverTick()))
                            throw new IllegalStateException("minecart destination visibility deadline: " + entry.visibilityReady);
                        continue;
                    }
                }
                NativePortalTransfer transfer = null;
                NativePortalGraphTransferPlan graphPlan = null;
                if (permitted && entry.graph != null) {
                    if (entry.graphPlan == null)
                        entry.graphPlan = new NativePortalGraphTransferPlan(authority, objects, replacements,
                            entry.graph, entry.graphTokens, entry.transition,
                            () -> active.get(key(entry.cart)) == entry && authority.running()
                                && entry.graphPlan != null && entry.graphPlan.visibilityCurrent());
                    graphPlan = entry.graphPlan;
                    if (!graphPlan.ready()) {
                        if (entry.visibilityDeadline.expired(authority.serverTick()))
                            throw new IllegalStateException("minecart graph destination visibility deadline");
                        continue;
                    }
                    authority.execute(entry.binding.routingKey(), RegionalServiceKind.ENTITY_PORTAL_SOURCE,
                        () -> entry.graph.validateIntact(objects));
                } else if (permitted) {
                    transfer = new NativePortalTransfer(authority, objects, replacements, entry.token,
                        entry.cart, entry.binding, entry.transition, () -> active.get(key(entry.cart)) == entry
                            && entry.visibilityReady.getAsBoolean());
                    if (!entry.riders.isEmpty()) transfer.bindPlayerRiders(entry.riders);
                }
                Entity result = graphPlan != null ? graphPlan.invoke() : transfer == null ? null : transfer.run();
                var continuation = result == null
                    ? replacements.authorizeUnchangedSourceContinuation(entry.token, entry.token.source())
                    : replacements.authorizeReplacementContinuation(entry.token);
                authority.execute(entry.binding.routingKey(), RegionalServiceKind.ENTITY_PORTAL_SOURCE, () -> {
                    if (active.get(key(entry.cart)) != entry || entry.consumed || !authority.worldActive(entry.level)
                            || ((FullEntityFacts.Access) entry.cart).phoenium$entityIncarnation()
                                != entry.token.source().nativeIncarnation()
                            || result == null && !current(entry)
                            || result != null && entry.cart.getRemovalReason() != Entity.RemovalReason.CHANGED_DIMENSION)
                        throw new IllegalStateException("stale minecart tick tail");
                    entry.consumed = true;
                    AbstractMinecartPortalNativeAccess suffix = (AbstractMinecartPortalNativeAccess) entry.cart;
                    suffix.phoenium$resumeAbstractMinecartPortalSuffix();
                    if (entry.cart.getClass() == Minecart.class)
                        ((MinecartPortalNativeAccess) entry.cart)
                            .phoenium$resumeMinecartPortalSuffix(entry.lastKnownYRot, entry.lastKnownPos);
                });
                replacements.settle(continuation);
                if (graphPlan != null) {
                    graphPlan.settleChildren();
                    graphPlan.settleRoot(result);
                } else {
                    if (entry.graph != null) settleUnchangedChildren(entry);
                    if (transfer != null) transfer.settled();
                    else NativePortalTransfer.retained(entry.cart, entry.binding, entry.token, null,
                        "SETTLED_NATIVE_NO_TRANSFER");
                }
                active.remove(key(entry.cart), entry);
                releaseGraphMembers(entry);
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
            && !entry.cart.isRemoved() && entry.cart.level() == entry.level
            && entry.cart.portalProcess == entry.processor
            && entry.cart.position().equals(entry.position)
            && entry.cart.getDeltaMovement().equals(entry.motion)
            && entry.cart.getYRot() == entry.yRot && entry.cart.getXRot() == entry.xRot
            && entry.cart.getPose() == entry.pose
            && (entry.riders.isEmpty() || entry.riders.equals(PortalRiders.leafPlayerRiders(entry.cart)))
            && ((FullEntityFacts.Access) entry.cart).phoenium$entityIncarnation()
                == entry.token.source().nativeIncarnation()
            && entry.cart.getUUID().toString().equals(entry.token.source().objectId())
            && entry.processor.getEntryPosition().equals(entry.entryPosition)
            && ((PearlPortalProcessorAccess) entry.processor).phoenium$portal() == entry.portal
            && entry.processor.getPortalTime() == entry.portalTime
            && entry.processor.isInsidePortalThisTick() == entry.insidePortal
            && chestContentsCurrent(entry)
            && current != null && current.incarnation() == entry.binding.incarnation()
            && current.objectEpoch().equals(entry.binding.objectEpoch())
            && current.routingKey().equals(entry.binding.routingKey());
    }

    public boolean onRemoval(Entity entity, Entity.RemovalReason reason) {
        if (!(entity instanceof AbstractMinecart cart) || !supported(cart)) return false;
        Entry entry = active.get(key(cart));
        if (entry == null) return false;
        if (entry.consumed && cart.getRemovalReason() == Entity.RemovalReason.CHANGED_DIMENSION) return true;
        cancel(entry, "EXTERNAL_REMOVAL:" + reason);
        return false;
    }

    public void worldClosing(ServerLevel level) {
        for (Entry entry : List.copyOf(active.values()))
            if (entry.level == level || entry.transition != null && entry.transition.newLevel() == level)
                cancel(entry, "WORLD_CLOSED");
    }

    private void cancel(Entry entry, String reason) {
        IllegalStateException partial = null;
        if (entry.graphPlan != null) {
            try { entry.graphPlan.restoreUncommittedSourceGraph(); }
            catch (RuntimeException | Error repairFailure) {
                partial = new IllegalStateException("PARTIAL_PORTAL_GRAPH: exact source graph was not restored", repairFailure);
            }
        }
        NativePortalTransfer.retained(entry.cart, entry.binding, entry.token,
            entry.transition == null ? null : entry.transition.newLevel(), reason);
        if (entry.graphPlan != null) entry.graphPlan.cancelUnsettled();
        else if (entry.graph != null) {
            for (var member : entry.graphTokens.entrySet()) {
                if (member.getKey() == entry.cart) continue;
                if (replacements.cancel(member.getValue()))
                    NativePortalTransfer.retained(member.getKey(), entry.graph.member(member.getKey()).binding(),
                        member.getValue(), entry.transition == null ? null : entry.transition.newLevel(), reason);
            }
        }
        replacements.cancel(entry.token);
        active.remove(key(entry.cart), entry);
        releaseGraphMembers(entry);
        if (partial != null) throw partial;
    }

    private void settleUnchangedChildren(Entry entry) {
        for (Entity member : entry.graph.postorder()) {
            if (member == entry.cart) continue;
            var childToken = entry.graphTokens.get(member);
            if (replacements.phase(childToken) == CrossDimensionReplacementBoundary.Phase.CAPTURED)
                replacements.prepared(childToken);
            var continuation = replacements.authorizeUnchangedSourceContinuation(childToken, childToken.source());
            replacements.settle(continuation);
            NativePortalTransfer.retained(member, entry.graph.member(member).binding(), childToken,
                entry.transition == null ? null : entry.transition.newLevel(), "SETTLED_NATIVE_NO_TRANSFER");
        }
    }

    private void releaseGraphMembers(Entry entry) {
        if (entry.graph == null) return;
        for (Entity member : entry.graph.preorder()) graphPending.remove(new EntityKey(member), entry);
    }

    public int pendingCount() { return active.size(); }

    @Override public void close() {
        closed = true;
        for (Entry entry : List.copyOf(active.values())) cancel(entry, "SHUTDOWN");
        replacements.close();
        ticking.set(null);
    }

    private static boolean supported(AbstractMinecart cart) {
        return cart.getClass() == Minecart.class || cart.getClass() == MinecartChest.class;
    }

    private static boolean chestContentsCurrent(Entry entry) {
        if (!(entry.cart instanceof MinecartChest chest)) return true;
        if (!Objects.equals(chest.getContainerLootTable(), entry.lootTable)
                || chest.getContainerLootTableSeed() != entry.lootSeed) return false;
        List<ItemStack> stacks = chest.getItemStacks();
        if (stacks.size() != entry.contents.size()) return false;
        for (int slot = 0; slot < stacks.size(); slot++)
            if (!ItemStack.matches(stacks.get(slot), entry.contents.get(slot))) return false;
        return true;
    }

    private static IdentityKey key(AbstractMinecart cart) { return new IdentityKey(cart); }

    private record EntityKey(Entity entity) {
        @Override public int hashCode() { return System.identityHashCode(entity); }
        @Override public boolean equals(Object other) {
            return other instanceof EntityKey key && entity == key.entity;
        }
    }

    private record IdentityKey(AbstractMinecart cart) {
        @Override public int hashCode() { return System.identityHashCode(cart); }
        @Override public boolean equals(Object other) {
            return other instanceof IdentityKey key && cart == key.cart;
        }
    }

    private static final class Capture {
        final AbstractMinecart cart;
        final double lastKnownYRot;
        final Vec3 lastKnownPos;
        Entry entry;
        Capture(AbstractMinecart cart, double lastKnownYRot, Vec3 lastKnownPos) {
            this.cart = cart;
            this.lastKnownYRot = lastKnownYRot;
            this.lastKnownPos = lastKnownPos;
        }
    }

    private static final class Entry {
        final AbstractMinecart cart;
        List<net.minecraft.server.level.ServerPlayer> riders = List.of();
        PortalPassengerGraphSnapshot graph;
        final java.util.IdentityHashMap<Entity, CrossDimensionReplacementBoundary.Token> graphTokens = new java.util.IdentityHashMap<>();
        NativePortalGraphTransferPlan graphPlan;
        final ServerLevel level;
        final PortalProcessor processor;
        final Portal portal;
        final ObjectOwnership binding;
        final CrossDimensionReplacementBoundary.Token token;
        final double lastKnownYRot;
        final Vec3 lastKnownPos;
        final BlockPos entryPosition;
        final int portalTime;
        final boolean insidePortal;
        final Vec3 position, motion;
        final float yRot, xRot;
        final net.minecraft.world.entity.Pose pose;
        final List<ItemStack> contents;
        final ResourceKey<LootTable> lootTable;
        final long lootSeed;
        volatile boolean retained, consumed, destinationPrepared, advancing;
        final PortalVisibilityDeadline visibilityDeadline = new PortalVisibilityDeadline();
        java.util.function.BooleanSupplier visibilityReady;
        TeleportTransition transition;
        Entry(AbstractMinecart cart, ServerLevel level, PortalProcessor processor, Portal portal,
                ObjectOwnership binding, CrossDimensionReplacementBoundary.Token token, Capture capture) {
            this.cart = cart; this.level = level; this.processor = processor; this.portal = portal;
            this.binding = binding; this.token = token;
            this.lastKnownYRot = capture.lastKnownYRot; this.lastKnownPos = capture.lastKnownPos;
            this.entryPosition = processor.getEntryPosition().immutable();
            this.portalTime = processor.getPortalTime(); this.insidePortal = processor.isInsidePortalThisTick();
            this.position = cart.position(); this.motion = cart.getDeltaMovement();
            this.yRot = cart.getYRot(); this.xRot = cart.getXRot(); this.pose = cart.getPose();
            if (cart instanceof MinecartChest chest) {
                this.contents = chest.getItemStacks().stream().map(ItemStack::copy).toList();
                this.lootTable = chest.getContainerLootTable();
                this.lootSeed = chest.getContainerLootTableSeed();
            } else {
                this.contents = List.of(); this.lootTable = null; this.lootSeed = 0;
            }
        }
    }
}
