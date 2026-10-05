package dev.phoenium.live;

import dev.phoenium.chunk.FullEntityFacts;
import dev.phoenium.ownership.ObjectOwnership;
import dev.phoenium.ownership.ObjectOwnershipRegistry;
import dev.phoenium.ownership.OwnershipContext;
import dev.phoenium.topology.RoutingKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;

/**
 * Exact source-owned passenger graph captured before the native cross-dimension ejection.
 * This class only records and validates inputs. It does not detach, teleport, or publish nodes.
 * The native order is children in list order (recursively), then the root, followed by
 * reattachment of successfully transferred children to each replacement root.
 * <p>A connected leaf player is not a graph node: its session crosses worlds through the player dimension
 * boundary, so it is recorded as a retained rider of its vehicle (see {@link Member#players()}) and the
 * topology holds only the non-player members.
 */
public final class PortalPassengerGraphSnapshot {
    private final Topology<Entity> topology;
    private final IdentityHashMap<Entity, Member> members;
    private final ServerLevel sourceLevel;

    private PortalPassengerGraphSnapshot(Topology<Entity> topology,
            IdentityHashMap<Entity, Member> members, ServerLevel sourceLevel) {
        this.topology = topology;
        this.members = members;
        this.sourceLevel = sourceLevel;
    }

    /** Capture must run in the source graph's owner closure, before ejectPassengers. */
    public static PortalPassengerGraphSnapshot capture(Entity root, ObjectOwnershipRegistry objects) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(objects, "objects");
        var context = OwnershipContext.current().orElseThrow(
            () -> new IllegalStateException("passenger graph capture lacks source authority"));
        if (!(root.level() instanceof ServerLevel sourceLevel))
            throw new IllegalStateException("passenger graph source is not a server world");
        Topology<Entity> topology = Topology.capture(root, PortalPassengerGraphSnapshot::nonPlayerPassengers);
        IdentityHashMap<Entity, Member> members = new IdentityHashMap<>();
        for (Entity entity : topology.preorder()) {
            if (entity.isRemoved() || entity.level() != sourceLevel
                    || entity.getVehicle() != topology.parent(entity))
                throw new IllegalStateException("passenger graph has a removed, foreign-world, or detached member");
            if (!(entity instanceof FullEntityFacts.Access access))
                throw new IllegalStateException("passenger graph member lacks native incarnation");
            ObjectOwnership binding = objects.current(entity.getUUID().toString()).orElseThrow(
                () -> new IllegalStateException("passenger graph member has no current ownership publication"));
            if (!physicalRoute(entity, binding).equals(binding.routingKey()))
                throw new IllegalStateException("passenger graph member route differs from physical chunk");
            objects.requireCurrentOwner(binding.objectId(), binding.objectEpoch(), context.region());
            List<ServerPlayer> players = PortalRiders.directPlayers(entity);
            requirePlayerLeaves(entity, players, topology.children(entity).size(), sourceLevel);
            members.put(entity, new Member(entity, topology.parent(entity), entity.getUUID(), entity.getId(),
                access.phoenium$entityIncarnation(), binding, entity.position(), entity.getDeltaMovement(),
                entity.getYRot(), entity.getXRot(), topology.children(entity), players));
        }
        PortalPassengerGraphSnapshot snapshot = new PortalPassengerGraphSnapshot(topology, members, sourceLevel);
        snapshot.validateIntact(objects);
        return snapshot;
    }

    public Entity root() { return topology.root(); }
    public ServerLevel sourceLevel() { return sourceLevel; }
    public List<Entity> preorder() { return topology.preorder(); }
    public List<Entity> postorder() { return topology.postorder(); }
    public Member member(Entity entity) {
        Member member = members.get(entity);
        if (member == null) throw new IllegalArgumentException("entity is outside the captured passenger graph");
        return member;
    }

    /** Revalidate before the first graph mutation. Never silently accept an altered edge. */
    public void validateIntact(ObjectOwnershipRegistry objects) {
        Objects.requireNonNull(objects, "objects");
        var context = OwnershipContext.current().orElseThrow(
            () -> new IllegalStateException("passenger graph validation lacks source authority"));
        topology.validate(PortalPassengerGraphSnapshot::nonPlayerPassengers);
        for (Entity entity : topology.preorder()) {
            Member member = member(entity);
            List<ServerPlayer> players = PortalRiders.directPlayers(entity);
            if (!players.equals(member.players()))
                throw new IllegalStateException("passenger graph player riders changed after capture");
            requirePlayerLeaves(entity, players, member.children().size(), sourceLevel);
            if (entity.isRemoved() || entity.level() != sourceLevel
                    || entity.getVehicle() != member.parent()
                    || !entity.getUUID().equals(member.uuid()) || entity.getId() != member.runtimeId()
                    || ((FullEntityFacts.Access) entity).phoenium$entityIncarnation() != member.nativeIncarnation()
                    || !entity.position().equals(member.position())
                    || !entity.getDeltaMovement().equals(member.movement())
                    || entity.getYRot() != member.yRot() || entity.getXRot() != member.xRot())
                throw new IllegalStateException("passenger graph member changed after capture");
            ObjectOwnership current = objects.current(member.uuid().toString()).orElse(null);
            if (!member.binding().equals(current))
                throw new IllegalStateException("passenger graph ownership generation changed after capture");
            if (!physicalRoute(entity, current).equals(current.routingKey()))
                throw new IllegalStateException("passenger graph member moved outside captured route");
            objects.requireCurrentOwner(current.objectId(), current.objectEpoch(), context.region());
        }
    }

    /** The graph's own members: every direct passenger that is not a player. */
    private static List<Entity> nonPlayerPassengers(Entity entity) {
        return entity.getPassengers().stream().filter(passenger -> !(passenger instanceof net.minecraft.world.entity.player.Player)).toList();
    }

    /** Every direct passenger is a graph member or a connected leaf player that still rides this vehicle in the source world. */
    private static void requirePlayerLeaves(Entity vehicle, List<ServerPlayer> players, int members, ServerLevel sourceLevel) {
        if (vehicle.getPassengers().size() != members + players.size())
            throw new IllegalStateException("passenger graph carries a player that is not a connected leaf rider");
        for (ServerPlayer player : players)
            if (player.isVehicle() || player.isRemoved() || player.level() != sourceLevel || player.getVehicle() != vehicle)
                throw new IllegalStateException("passenger graph player rider is not a live leaf of its vehicle");
    }

    private static RoutingKey physicalRoute(Entity entity, ObjectOwnership binding) {
        var chunk = entity.chunkPosition();
        return new RoutingKey(binding.routingKey().world(), chunk.x(), chunk.z());
    }

    /**
     * All-success settlement check after the native child-first transfers, root admission,
     * and native-order startRiding calls. Partial native results need an explicit outcome plan
     * and must not be passed off as an intact graph.
     */
    public void validateReattached(Function<Entity, Entity> replacementOf,
            ObjectOwnershipRegistry objects, ServerLevel destinationLevel) {
        Objects.requireNonNull(replacementOf, "replacementOf");
        Objects.requireNonNull(objects, "objects");
        Objects.requireNonNull(destinationLevel, "destinationLevel");
        var context = OwnershipContext.current().orElseThrow(
            () -> new IllegalStateException("passenger graph settlement lacks destination authority"));
        var replacements = new IdentityHashMap<Entity, Entity>();
        var seen = Collections.newSetFromMap(new IdentityHashMap<Entity, Boolean>());
        for (Entity source : topology.preorder()) {
            Entity replacement = Objects.requireNonNull(replacementOf.apply(source),
                "passenger graph member has no successful native result");
            Member captured = member(source);
            if (!seen.add(replacement) || replacement.isRemoved() || replacement.level() != destinationLevel
                    || !replacement.getUUID().equals(captured.uuid()))
                throw new IllegalStateException("passenger graph destination identity changed");
            ObjectOwnership current = objects.current(captured.uuid().toString()).orElseThrow(
                () -> new IllegalStateException("passenger graph destination lacks ownership publication"));
            if (current.incarnation() < captured.binding().incarnation())
                throw new IllegalStateException("passenger graph ownership incarnation regressed");
            objects.requireCurrentOwner(current.objectId(), current.objectEpoch(), context.region());
            replacements.put(source, replacement);
        }
        for (Entity source : topology.preorder()) {
            Entity replacement = replacements.get(source);
            Entity capturedParent = topology.parent(source);
            if (replacement.getVehicle() != (capturedParent == null ? null : replacements.get(capturedParent)))
                throw new IllegalStateException("passenger graph destination parent edge changed");
            List<Entity> children = replacement.getPassengers();
            List<Entity> expected = topology.children(source);
            if (children.size() != expected.size())
                throw new IllegalStateException("passenger graph destination child count changed");
            for (int i = 0; i < expected.size(); i++)
                if (children.get(i) != replacements.get(expected.get(i)))
                    throw new IllegalStateException("passenger graph destination child edge changed");
        }
    }

    /** The exact 26.2 Entity.calculatePassengerTransition position/rotation arithmetic. */
    public TeleportTransition passengerTransition(TeleportTransition parentTransition, Entity parent, Entity passenger) {
        Objects.requireNonNull(parentTransition, "parentTransition");
        Member parentMember = member(parent);
        Member passengerMember = member(passenger);
        if (passengerMember.parent() != parent)
            throw new IllegalArgumentException("member is not a direct passenger of the given parent");
        PassengerPlacement placement = calculatePassengerPlacement(parentTransition, parentMember.position(),
            parentMember.yRot(), parentMember.xRot(), passengerMember.position(),
            passengerMember.yRot(), passengerMember.xRot());
        return parentTransition.withPosition(placement.position())
            .withRotation(placement.yRot(), placement.xRot()).transitionAsPassenger();
    }

    public static PassengerPlacement calculatePassengerPlacement(TeleportTransition transition,
            Vec3 parentPosition, float parentYRot, float parentXRot, Vec3 passengerPosition,
            float passengerYRot, float passengerXRot) {
        Objects.requireNonNull(transition, "transition");
        Objects.requireNonNull(parentPosition, "parentPosition");
        Objects.requireNonNull(passengerPosition, "passengerPosition");
        Set<Relative> relatives = transition.relatives();
        float yRot = transition.yRot() + (relatives.contains(Relative.Y_ROT) ? 0.0F : passengerYRot - parentYRot);
        float xRot = transition.xRot() + (relatives.contains(Relative.X_ROT) ? 0.0F : passengerXRot - parentXRot);
        Vec3 offset = passengerPosition.subtract(parentPosition);
        Vec3 position = transition.position().add(
            relatives.contains(Relative.X) ? 0.0 : offset.x(),
            relatives.contains(Relative.Y) ? 0.0 : offset.y(),
            relatives.contains(Relative.Z) ? 0.0 : offset.z());
        return new PassengerPlacement(position, yRot, xRot);
    }

    public record Member(Entity entity, Entity parent, UUID uuid, int runtimeId, long nativeIncarnation,
                         ObjectOwnership binding, Vec3 position, Vec3 movement, float yRot, float xRot,
                         List<Entity> children, List<ServerPlayer> players) { }
    public record PassengerPlacement(Vec3 position, float yRot, float xRot) { }

    /** Identity-based topology useful for both the native graph and deterministic controls. */
    public static final class Topology<T> {
        private final T root;
        private final List<T> preorder;
        private final List<T> postorder;
        private final IdentityHashMap<T, T> parents;
        private final IdentityHashMap<T, List<T>> children;

        private Topology(T root, List<T> preorder, List<T> postorder,
                IdentityHashMap<T, T> parents, IdentityHashMap<T, List<T>> children) {
            this.root = root;
            this.preorder = List.copyOf(preorder);
            this.postorder = List.copyOf(postorder);
            this.parents = parents;
            this.children = children;
        }
        public static <T> Topology<T> capture(T root, Function<T, List<T>> childrenOf) {
            Objects.requireNonNull(root, "root");
            Objects.requireNonNull(childrenOf, "childrenOf");
            var preorder = new ArrayList<T>();
            var postorder = new ArrayList<T>();
            var parents = new IdentityHashMap<T, T>();
            var children = new IdentityHashMap<T, List<T>>();
            visit(root, null, childrenOf, preorder, postorder, parents, children);
            return new Topology<>(root, preorder, postorder, parents, children);
        }
        private static <T> void visit(T node, T parent, Function<T, List<T>> childrenOf,
                List<T> preorder, List<T> postorder, IdentityHashMap<T, T> parents,
                IdentityHashMap<T, List<T>> children) {
            if (node == null || children.containsKey(node))
                throw new IllegalStateException("cyclic, duplicated, or null passenger graph member");
            List<T> direct = List.copyOf(Objects.requireNonNull(childrenOf.apply(node), "passenger list"));
            parents.put(node, parent);
            children.put(node, direct);
            preorder.add(node);
            for (T child : direct) visit(child, node, childrenOf, preorder, postorder, parents, children);
            postorder.add(node);
        }
        public T root() { return root; }
        public List<T> preorder() { return preorder; }
        public List<T> postorder() { return postorder; }
        public T parent(T node) {
            if (!children.containsKey(node)) throw new IllegalArgumentException("node is outside the graph");
            return parents.get(node);
        }
        public List<T> children(T node) {
            List<T> direct = children.get(node);
            if (direct == null) throw new IllegalArgumentException("node is outside the graph");
            return direct;
        }
        public void validate(Function<T, List<T>> childrenOf) {
            Objects.requireNonNull(childrenOf, "childrenOf");
            for (T node : preorder) {
                List<T> before = children.get(node);
                List<T> now = Objects.requireNonNull(childrenOf.apply(node), "passenger list");
                if (before.size() != now.size()) throw new IllegalStateException("passenger graph edge count changed");
                for (int i = 0; i < before.size(); i++)
                    if (before.get(i) != now.get(i))
                        throw new IllegalStateException("passenger graph edge order or identity changed");
            }
        }
    }
}
