package dev.phoenium.live;

import dev.phoenium.ownership.ObjectOwnership;
import dev.phoenium.ownership.ObjectOwnershipRegistry;
import dev.phoenium.ownership.OwnershipContext;
import dev.phoenium.topology.RoutingKey;
import dev.phoenium.chunk.FullEntityFacts;
import dev.phoenium.service.RegionalServiceKind;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.portal.TeleportTransition;

/**
 * Prepared native recursion for a source-owned passenger graph. The root's original 26.2
 * teleport method still ejects, visits children, constructs, reattaches and calls back in its
 * original order. Each recursive child invocation receives its own retained transfer boundary.
 */
public final class NativePortalGraphTransferPlan {
    private final NativePortalTransfer.Authority authority;
    private final ObjectOwnershipRegistry objects;
    private final CrossDimensionReplacementBoundary boundary;
    private final PortalPassengerGraphSnapshot graph;
    private final IdentityHashMap<Entity, CrossDimensionReplacementBoundary.Token> tokens;
    private final IdentityHashMap<Entity, NativePortalTransfer> transfers = new IdentityHashMap<>();
    private final IdentityHashMap<Entity, TeleportTransition> transitions = new IdentityHashMap<>();
    private final IdentityHashMap<Entity, BooleanSupplier> visibility = new IdentityHashMap<>();
    private final IdentityHashMap<Entity, Entity> results = new IdentityHashMap<>();
    private boolean invoked, completed;

    public NativePortalGraphTransferPlan(NativePortalTransfer.Authority authority,
            ObjectOwnershipRegistry objects, CrossDimensionReplacementBoundary boundary,
            PortalPassengerGraphSnapshot graph,
            Map<Entity, CrossDimensionReplacementBoundary.Token> tokens,
            TeleportTransition rootTransition, BooleanSupplier live) {
        this.authority = Objects.requireNonNull(authority);
        this.objects = Objects.requireNonNull(objects);
        this.boundary = Objects.requireNonNull(boundary);
        this.graph = Objects.requireNonNull(graph);
        this.tokens = new IdentityHashMap<>(Objects.requireNonNull(tokens));
        Objects.requireNonNull(rootTransition);
        Objects.requireNonNull(live);
        if (OwnershipContext.current().isPresent())
            throw new IllegalStateException("passenger graph destination preparation held source owner");
        for (Entity entity : graph.preorder()) {
            Entity parent = graph.member(entity).parent();
            TeleportTransition transition = parent == null ? rootTransition
                : graph.passengerTransition(transitions.get(parent), parent, entity);
            transitions.put(entity, transition);
            CrossDimensionReplacementBoundary.Token token = this.tokens.get(entity);
            if (token == null || !token.source().objectId().equals(entity.getUUID().toString()))
                throw new IllegalStateException("passenger graph member lost captured boundary");
            ObjectOwnership binding = graph.member(entity).binding();
            transfers.put(entity, new NativePortalTransfer(authority, objects, boundary, token,
                entity, binding, transition, live));
        }
        for (Entity entity : graph.preorder()) {
            List<Entity> children = graph.member(entity).children();
            if (children.isEmpty()) continue;
            IdentityHashMap<Entity, NativePortalTransfer> childTransfers = new IdentityHashMap<>();
            for (Entity child : children) childTransfers.put(child, transfers.get(child));
            transfers.get(entity).bindGraphChildren(childTransfers);
        }
    }

    /** No source graph mutation can occur until every child and root is fully prepared. */
    public boolean ready() {
        if (invoked) throw new IllegalStateException("passenger graph preparation followed native invocation");
        if (OwnershipContext.current().isPresent())
            throw new IllegalStateException("passenger graph readiness held owner authority");
        for (TeleportTransition transition : transitions.values())
            if (transition.relatives().contains(Relative.X)
                    || transition.relatives().contains(Relative.Y)
                    || transition.relatives().contains(Relative.Z))
                throw new IllegalStateException("relative-position passenger graph cannot be prepared safely");
        for (Entity entity : graph.preorder()) {
            NativePortalTransfer transfer = transfers.get(entity);
            if (!transfer.prepareReadiness()) return false;
            BooleanSupplier visible = visibility.get(entity);
            if (visible == null) {
                visible = authority.demandDestinationEntityVisibility(entity, transitions.get(entity));
                visibility.put(entity, visible);
            }
            if (!visible.getAsBoolean()) return false;
        }
        // The current owner-only startRiding primitive requires one destination closure.
        // Reject an unprepared cross-region graph before native ejection can occur.
        var rootKey = transfers.get(graph.root()).destinationKey();
        for (Entity entity : graph.preorder())
            if (!transfers.get(entity).destinationKey().equals(rootKey))
                throw new IllegalStateException("passenger graph destination spans region owners");
        for (Entity entity : graph.preorder()) {
            if (entity == graph.root()) continue;
            var token = tokens.get(entity);
            var phase = boundary.phase(token);
            if (phase == CrossDimensionReplacementBoundary.Phase.CAPTURED)
                boundary.prepared(token);
            else if (phase != CrossDimensionReplacementBoundary.Phase.PREPARED)
                throw new IllegalStateException("passenger graph child boundary changed before native invocation");
        }
        return true;
    }

    /** Included in every retained effect's existing live check after native recursion begins. */
    public boolean visibilityCurrent() {
        if (!invoked) return true;
        for (Entity entity : graph.preorder()) {
            BooleanSupplier visible = visibility.get(entity);
            if (visible == null || !visible.getAsBoolean()) return false;
        }
        return true;
    }

    public Entity invoke() {
        if (invoked || completed) throw new IllegalStateException("passenger graph native call repeated");
        if (!ready()) throw new IllegalStateException("passenger graph native call lacked readiness");
        invoked = true;
        Entity rootResult = transfers.get(graph.root()).run();
        results.put(graph.root(), rootResult);
        for (Entity parent : graph.preorder())
            for (Entity child : graph.member(parent).children())
                results.put(child, transfers.get(parent).graphChildResult(child));
        return rootResult;
    }

    /** Validate native partial-null results without claiming that every original edge survived. */
    public void settleChildren() {
        if (!invoked || completed || results.size() != graph.preorder().size())
            throw new IllegalStateException("passenger graph result set incomplete");
        var destination = transitions.get(graph.root()).newLevel();
        var destinationKey = transfers.get(graph.root()).destinationKey();
        authority.execute(destinationKey, RegionalServiceKind.ENTITY_PORTAL_DESTINATION, () -> {
            var context = OwnershipContext.current().orElseThrow();
            for (Entity source : graph.preorder()) {
                Entity result = results.get(source);
                if (result == null) continue;
                if (result.isRemoved() || result.level() != destination
                        || !result.getUUID().equals(graph.member(source).uuid()))
                    throw new IllegalStateException("passenger graph destination member changed");
                ObjectOwnership binding = objects.current(result.getUUID().toString()).orElseThrow();
                if (!binding.routingKey().equals(transfers.get(source).destinationKey())
                        || binding.incarnation() <= graph.member(source).binding().incarnation())
                    throw new IllegalStateException("passenger graph member lacks replacement publication");
                objects.requireCurrentOwner(binding.objectId(), binding.objectEpoch(), context.region());
                Entity parent = graph.member(source).parent();
                Entity expectedParent = parent == null ? null : results.get(parent);
                if (result.getVehicle() != expectedParent)
                    throw new IllegalStateException("passenger graph destination parent edge changed");
                List<Entity> expected = graph.member(source).children().stream()
                    .map(results::get).filter(Objects::nonNull).toList();
                List<Entity> actual = result.getPassengers();
                if (actual.size() != expected.size())
                    throw new IllegalStateException("passenger graph destination child count changed");
                for (int i = 0; i < expected.size(); i++)
                    if (actual.get(i) != expected.get(i))
                        throw new IllegalStateException("passenger graph destination child order changed");
            }
        });
        authority.execute(graph.member(graph.root()).binding().routingKey(),
            RegionalServiceKind.ENTITY_PORTAL_SOURCE, () -> {
                var context = OwnershipContext.current().orElseThrow();
                for (Entity source : graph.preorder()) {
                    Entity result = results.get(source);
                    if (result == null) {
                        if (source.isRemoved() || source.getVehicle() != null
                                || !source.getPassengers().isEmpty())
                            throw new IllegalStateException("native null graph result changed source edges");
                        ObjectOwnership current = objects.current(source.getUUID().toString()).orElseThrow();
                        if (!current.equals(transfers.get(source).effectiveSourceBinding()))
                            throw new IllegalStateException("native null graph result changed source publication");
                        objects.requireCurrentOwner(current.objectId(), current.objectEpoch(), context.region());
                    } else if (source.getRemovalReason() != Entity.RemovalReason.CHANGED_DIMENSION)
                        throw new IllegalStateException("successful graph member did not retire its source");
                }
            });
        for (Entity source : graph.postorder()) {
            if (source == graph.root()) continue;
            var token = tokens.get(source);
            Entity result = results.get(source);
            if (result == null) {
                var continuation = boundary.authorizeUnchangedSourceContinuation(token, token.source());
                boundary.settle(continuation);
                NativePortalTransfer.retained(source, graph.member(source).binding(), token,
                    destination, "SETTLED_NATIVE_NO_TRANSFER");
            } else {
                var continuation = boundary.authorizeReplacementContinuation(token);
                boundary.settle(continuation);
                transfers.get(source).settled();
            }
        }
        completed = true;
    }

    public void settleRoot(Entity result) {
        if (!completed || results.get(graph.root()) != result)
            throw new IllegalStateException("passenger graph root settlement changed result");
        if (result == null)
            NativePortalTransfer.retained(graph.root(), graph.member(graph.root()).binding(),
                tokens.get(graph.root()), transitions.get(graph.root()).newLevel(),
                "SETTLED_NATIVE_NO_TRANSFER");
        else transfers.get(graph.root()).settled();
    }

    public void cancelUnsettled() {
        if (completed) return;
        for (Entity source : graph.preorder()) {
            if (source == graph.root()) continue;
            var token = tokens.get(source);
            if (boundary.cancel(token))
                NativePortalTransfer.retained(source, graph.member(source).binding(), token,
                    transitions.get(source).newLevel(), "CANCELLED_GRAPH");
        }
    }

    /** Restore only the original live two-member source graph before any native publication commits. */
    public void restoreUncommittedSourceGraph() {
        if (!invoked || completed) return;
        if (graph.preorder().size() != 2 || graph.member(graph.root()).children().size() != 1)
            throw new IllegalStateException("PARTIAL_PORTAL_GRAPH: source graph is outside repair bound");
        Entity child = graph.member(graph.root()).children().getFirst();
        var rootBinding = graph.member(graph.root()).binding();
        authority.execute(rootBinding.routingKey(), RegionalServiceKind.ENTITY_PORTAL_SOURCE, () -> {
            var region = OwnershipContext.current().orElseThrow().region();
            for (Entity member : graph.preorder()) {
                var captured = graph.member(member);
                var phase = boundary.phase(tokens.get(member));
                if (phase != CrossDimensionReplacementBoundary.Phase.CAPTURED
                        && phase != CrossDimensionReplacementBoundary.Phase.PREPARED)
                    throw new IllegalStateException("PARTIAL_PORTAL_GRAPH: member already committed " + phase);
                ObjectOwnership current = objects.current(captured.uuid().toString()).orElse(null);
                var physicalChunk = member.chunkPosition();
                RoutingKey physical = new RoutingKey(captured.binding().routingKey().world(),
                    physicalChunk.x(), physicalChunk.z());
                if (member.isRemoved() || member.level() != graph.sourceLevel()
                        || !member.getUUID().equals(captured.uuid()) || member.getId() != captured.runtimeId()
                        || ((FullEntityFacts.Access) member).phoenium$entityIncarnation() != captured.nativeIncarnation()
                        || current == null || current.kind() != captured.binding().kind()
                        || current.incarnation() != captured.binding().incarnation()
                        || !current.objectEpoch().equals(captured.binding().objectEpoch())
                        || !current.owner().equals(captured.binding().owner())
                        || !current.topologyGeneration().equals(captured.binding().topologyGeneration())
                        || !current.regionOwnerEpoch().equals(captured.binding().regionOwnerEpoch())
                        || !current.routingKey().equals(physical) || !current.owner().equals(region))
                    throw new IllegalStateException("PARTIAL_PORTAL_GRAPH: original source member changed "
                        + captured.uuid());
                objects.requireCurrentOwner(current.objectId(), current.objectEpoch(), region);
            }
            if (child.getVehicle() == graph.root()
                    && graph.root().getPassengers().equals(List.of(child))) return;
            if (child.getVehicle() != null || !graph.root().getPassengers().isEmpty()
                    || !child.getPassengers().isEmpty() || !child.startRiding(graph.root(), true, false)
                    || child.getVehicle() != graph.root()
                    || !graph.root().getPassengers().equals(List.of(child)))
                throw new IllegalStateException("PARTIAL_PORTAL_GRAPH: source attachment could not be restored");
        });
    }
}
