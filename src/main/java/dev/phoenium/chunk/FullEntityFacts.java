package dev.phoenium.chunk;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

/** D1-F owner-published scalars. An exact retained entity pointer remains only an alias. */
public final class FullEntityFacts {
    public interface Access {
        Fact phoenium$referenceFact();
        void phoenium$referenceFact(Fact fact);
        long phoenium$entityIncarnation();
        int phoenium$rawEntityId();
    }
    public record Fact(Entity alias, MinecraftServer server, ServerLevel world, long incarnation, long revision,
                       UUID uuid, int runtimeId, EntityType<?> type, Class<?> dispatch, String scoreboardName, boolean removed,
                       boolean enemy, boolean creative, boolean spectator) { }
    public record Candidate(Entity alias, Fact fact, ChunkPos position, long routingRevision) { }
    public record PublishedPosition(Candidate candidate, Vec3 position) { }
    public record VehicleRelation(Candidate passenger, Candidate vehicle, long revision) { }
    public record Cut(ServerLevel world, long revision, List<Candidate> candidates) { }
    record Estimate(ServerLevel world, long revision, int candidates) { }
    private record Route(ChunkPos position, long revision) { }
    private static final AtomicLong INCARNATIONS = new AtomicLong();
    private static final ThreadLocal<Boolean> PUBLISHING = ThreadLocal.withInitial(() -> false);
    private final FullFinishingServices service;
    private final IdentityHashMap<ServerLevel, Map<UUID, Entity>> directories = new IdentityHashMap<>();
    private final IdentityHashMap<ServerLevel, LinkedHashMap<Integer, Entity>> candidates = new IdentityHashMap<>();
    private final IdentityHashMap<ServerLevel, Long> candidateRevisions = new IdentityHashMap<>();
    private final IdentityHashMap<Entity, Route> routes = new IdentityHashMap<>();
    private final IdentityHashMap<Entity, Object> stored = new IdentityHashMap<>();
    private final IdentityHashMap<Entity, Vec3> precisePositions = new IdentityHashMap<>();
    private final IdentityHashMap<Entity, List<Entity>> passengerRelations = new IdentityHashMap<>();
    private final IdentityHashMap<Entity, Entity> passengerVehicles = new IdentityHashMap<>();
    private final IdentityHashMap<Entity, Long> relationRevisions = new IdentityHashMap<>();
    private final IdentityHashMap<Entity, FreshPassengerAdmission> pendingPassengerIdentities = new IdentityHashMap<>();
    private final Set<ServerLevel> closedPassengerWorlds = Collections.newSetFromMap(new IdentityHashMap<>());
    private final java.util.concurrent.atomic.AtomicBoolean passengerFailureReported = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicBoolean directPassengerSuccessReported = new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.locks.ReentrantLock candidateMetadata = new java.util.concurrent.locks.ReentrantLock();
    private long routingSequence;
    private long published, insertions, removals, naturalPassengerScopes, directPassengerPrepared,
        directPassengerAccepted, directPassengerRejected, directPassengerSettled, directPassengerUpgraded;
    FullEntityFacts(FullFinishingServices service) { this.service = service; }
    public static long nextIncarnation() { return INCARNATIONS.incrementAndGet(); }
    public static void constructed(Entity entity) {
        FullFinishingServices.Reservation full = FullFinishingServices.currentReservation();
        if (full != null) full.constructed(entity);
    }
    public static FullFinishingServices.Access writer(Entity entity) {
        if (PUBLISHING.get() || !(entity instanceof Access access) || access.phoenium$referenceFact() == null) return null;
        Fact current = access.phoenium$referenceFact();
        FullFinishingServices.Reservation full = FullFinishingServices.currentReservation();
        if (full != null && !full.owns(entity))
            throw FullFinishingServices.failure(new ChunkProtocolException("reference fact turn does not authorize a foreign target mutation"));
        return FullFinishingServices.forServer(current.server).referenceAccess();
    }
    /** Newly constructed objects publish their complete initial state at actual lookup insertion. */
    public static void publish(Entity entity) { publish(entity, false); }
    public static void publish(Entity entity, boolean complete) {
        if (PUBLISHING.get() || !(entity instanceof Access access)) return;
        Fact previous = access.phoenium$referenceFact();
        if (!complete && previous == null) return;
        PUBLISHING.set(true);
        try {
            if (!(entity.level() instanceof ServerLevel level)) return;
            var service = FullFinishingServices.forServer(level.getServer());
            try (var turn = service.referenceAccess()) {
                Fact fact = new Fact(entity, level.getServer(), level, access.phoenium$entityIncarnation(),
                        previous == null ? 1 : previous.revision + 1, entity.getUUID(), access.phoenium$rawEntityId(), entity.getType(),
                        entity.getClass(), entity.getScoreboardName(), entity.isRemoved(), entity instanceof LivingEntity living && living.canBeSeenAsEnemy(),
                        entity instanceof Player player && player.isCreative(), entity.isSpectator());
                access.phoenium$referenceFact(fact); service.entityFacts.published++;
                service.changed(FullFinishingServices.Kind.ENTITY_REGISTRY);
            }
        } finally { PUBLISHING.remove(); }
    }
    /** Source hook follows the successful original UUID-map put, before original tracking callbacks. */
    public static void inserted(Entity entity) {
        publish(entity, true);
        Fact fact = ((Access) entity).phoenium$referenceFact();
        if (fact == null) return;
        var service = FullFinishingServices.forServer(fact.server);
        try (var turn = service.referenceAccess()) {
            service.entityFacts.directories.computeIfAbsent(fact.world, ignored -> new HashMap<>()).put(fact.uuid, entity);
            service.entityFacts.insertions++; service.changed(FullFinishingServices.Kind.ENTITY_REGISTRY);
        }
    }
    /** Original by-id insertion order is the selector's no-AABB iteration order. */
    public static void indexed(Entity entity, int id, Entity displaced) {
        Fact fact = entity instanceof Access access ? access.phoenium$referenceFact() : null;
        if (fact == null) return;
        var facts = FullFinishingServices.forServer(fact.server).entityFacts;
        ChunkPos position = entity.chunkPosition();
        Vec3 precise = entity.position();
        facts.candidateMetadata.lock();
        try {
            if (displaced != null && displaced != entity) {
                if (!facts.stored.containsKey(displaced)) {
                    facts.routes.remove(displaced); facts.precisePositions.remove(displaced);
                    facts.removeRelationsLocked(displaced);
                }
            }
            long routeRevision = Math.incrementExact(facts.routingSequence);
            long worldRevision = facts.nextCandidateRevision(fact.world);
            facts.candidates.computeIfAbsent(fact.world, ignored -> new LinkedHashMap<>()).put(id, entity);
            facts.routes.put(entity, new Route(position, routeRevision));
            facts.precisePositions.put(entity, precise);
            facts.routingSequence = routeRevision; facts.candidateRevisions.put(fact.world, worldRevision);
        } finally { facts.candidateMetadata.unlock(); }
        facts.service.changed(FullFinishingServices.Kind.ENTITY_REGISTRY);
        passengersChanged(entity);
    }
    /** Original remove(key) is unconditional; retain that prefix even for a displaced same UUID. */
    public static void removed(Entity entity, UUID originalKey) {
        Fact fact = ((Access) entity).phoenium$referenceFact();
        if (fact == null) return;
        var service = FullFinishingServices.forServer(fact.server);
        try (var turn = service.referenceAccess()) {
            Map<UUID, Entity> world = service.entityFacts.directories.get(fact.world);
            if (world != null) world.remove(originalKey);
            service.entityFacts.removals++; service.changed(FullFinishingServices.Kind.ENTITY_REGISTRY);
        }
    }
    /** Original by-id removal is distinct from the earlier UUID-directory removal. */
    public static void unindexed(Entity entity, int id) {
        Fact fact = entity instanceof Access access ? access.phoenium$referenceFact() : null;
        if (fact == null) return;
        var facts = FullFinishingServices.forServer(fact.server).entityFacts;
        facts.candidateMetadata.lock();
        try {
            long worldRevision = facts.nextCandidateRevision(fact.world);
            var ordered = facts.candidates.get(fact.world);
            if (ordered != null) {
                ordered.remove(id, entity);
                if (ordered.isEmpty()) facts.candidates.remove(fact.world);
            }
            facts.candidateRevisions.put(fact.world, worldRevision);
            if (!facts.stored.containsKey(entity)) {
                facts.routes.remove(entity); facts.precisePositions.remove(entity);
                facts.removeRelationsLocked(entity);
            }
        } finally { facts.candidateMetadata.unlock(); }
        facts.service.changed(FullFinishingServices.Kind.ENTITY_REGISTRY);
    }
    /** Original persistent-section insertion precedes visibility. Hidden entities retain a
     * routing receipt until actual section removal, without entering the visible selector cut. */
    public static void stored(Entity entity, Object manager) {
        publish(entity, true);
        Fact fact = ((Access) entity).phoenium$referenceFact();
        if (fact == null) return;
        var facts = FullFinishingServices.forServer(fact.server).entityFacts;
        ChunkPos position = entity.chunkPosition();
        Vec3 precise = entity.position();
        facts.candidateMetadata.lock();
        try {
            Object previous = facts.stored.putIfAbsent(entity, Objects.requireNonNull(manager));
            if (previous != null) throw new ChunkProtocolException("entity already belongs to persistent storage");
            long revision = facts.routingSequence = Math.incrementExact(facts.routingSequence);
            facts.routes.put(entity, new Route(position, revision));
            facts.precisePositions.put(entity, precise);
        } finally { facts.candidateMetadata.unlock(); }
        passengersChanged(entity);
        facts.service.changed(FullFinishingServices.Kind.ENTITY_REGISTRY);
    }

    /**
     * Exact native saved-reader batch at its serial delivery edge. A parent's original tracking
     * callback can inspect passengers that occur later in that same batch. Publish their input
     * scalars/routes without adding visible candidates, UUID-directory entries or object owners.
     * Actual storage/lookup insertion remains authoritative; unused input facts expire at close.
     */
    public static LoadedBatch loadedBatch(ServerLevel world, List<Entity> inputs) {
        FullFinishingServices.requireWorld(world, FullFinishingServices.Kind.ENTITY_REGISTRY);
        var facts = FullFinishingServices.forServer(world.getServer()).entityFacts;
        LoadedBatch batch = new LoadedBatch(facts);
        try {
            for (Entity entity : inputs) {
                if (entity.level() != world || !(entity instanceof Access))
                    throw new ChunkProtocolException("saved entity batch world or native identity changed");
                facts.candidateMetadata.lock();
                try { if (facts.routes.containsKey(entity)) continue; }
                finally { facts.candidateMetadata.unlock(); }
                batch.staged.add(entity);
                batch.previousFacts.put(entity, ((Access) entity).phoenium$referenceFact());
                publish(entity, true);
                ChunkPos position = entity.chunkPosition();
                Vec3 precise = entity.position();
                facts.candidateMetadata.lock();
                try {
                    long revision = facts.routingSequence = Math.incrementExact(facts.routingSequence);
                    facts.routes.put(entity, new Route(position, revision));
                    facts.precisePositions.put(entity, precise);
                } finally { facts.candidateMetadata.unlock(); }
            }
            for (Entity entity : batch.staged) passengersChanged(entity);
            return batch;
        } catch (Throwable failure) {
            try { batch.close(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    public static final class LoadedBatch implements AutoCloseable {
        private final FullEntityFacts facts;
        private final List<Entity> staged = new ArrayList<>();
        private final IdentityHashMap<Entity, Fact> previousFacts = new IdentityHashMap<>();
        private boolean closed;
        private LoadedBatch(FullEntityFacts facts) { this.facts = facts; }
        @Override public void close() {
            if (closed) return;
            closed = true;
            facts.candidateMetadata.lock();
            try {
                for (Entity entity : staged) {
                    Fact fact = ((Access) entity).phoenium$referenceFact();
                    boolean visible = fact != null && facts.candidates.getOrDefault(fact.world, new LinkedHashMap<>())
                        .get(fact.runtimeId) == entity;
                    if (facts.stored.containsKey(entity) || visible) continue;
                    facts.routes.remove(entity); facts.precisePositions.remove(entity);
                    facts.removeRelationsLocked(entity);
                    ((Access) entity).phoenium$referenceFact(previousFacts.get(entity));
                }
                staged.clear(); previousFacts.clear();
            } finally { facts.candidateMetadata.unlock(); }
        }
    }

    /** Runs after the original removal callback, including tracker removal and UUID retirement. */
    public static void unstored(Entity entity, Object manager) {
        Fact fact = entity instanceof Access access ? access.phoenium$referenceFact() : null;
        if (fact == null) return;
        var facts = FullFinishingServices.forServer(fact.server).entityFacts;
        facts.candidateMetadata.lock();
        try {
            if (!facts.stored.remove(entity, manager)) throw new ChunkProtocolException("entity storage membership changed");
            var visible = facts.candidates.get(fact.world);
            if (visible != null && visible.containsValue(entity))
                throw new ChunkProtocolException("persistent entity removal retained visible identity");
            facts.routes.remove(entity); facts.precisePositions.remove(entity);
            facts.removeRelationsLocked(entity);
        } finally { facts.candidateMetadata.unlock(); }
        facts.service.changed(FullFinishingServices.Kind.ENTITY_REGISTRY);
    }

    /** Owner-side movement publishes routing metadata without reading through a foreign alias. */
    public static void routed(Entity entity) {
        Fact fact = entity instanceof Access access ? access.phoenium$referenceFact() : null;
        if (fact == null) return;
        var facts = FullFinishingServices.forServer(fact.server).entityFacts;
        ChunkPos position = entity.chunkPosition();
        Vec3 precise = entity.position();
        boolean indexed;
        facts.candidateMetadata.lock();
        try {
            indexed = facts.routes.containsKey(entity);
            if (indexed) {
                long routeRevision = Math.incrementExact(facts.routingSequence);
                long worldRevision = facts.nextCandidateRevision(fact.world);
                facts.routes.put(entity, new Route(position, routeRevision));
                facts.precisePositions.put(entity, precise);
                facts.routingSequence = routeRevision; facts.candidateRevisions.put(fact.world, worldRevision);
            }
        } finally { facts.candidateMetadata.unlock(); }
        if (indexed) facts.service.changed(FullFinishingServices.Kind.ENTITY_REGISTRY);
    }
    public Cut cut(ServerLevel world) {
        return cut(world, Integer.MAX_VALUE);
    }
    Cut cut(ServerLevel world, int maximumCandidates) {
        FullFinishingServices.requireWorld(world, FullFinishingServices.Kind.ENTITY_REGISTRY);
        candidateMetadata.lock();
        try {
            var indexed = candidates.getOrDefault(world, new LinkedHashMap<>());
            if (indexed.size() > maximumCandidates)
                throw new ChunkProtocolException("selector candidate cut exceeds its admitted saved-input allowance");
            var result = new ArrayList<Candidate>(indexed.size());
            for (Entity entity : indexed.values()) {
                Fact fact = entity instanceof Access access ? access.phoenium$referenceFact() : null;
                Route route = routes.get(entity);
                if (fact == null || fact.alias != entity || fact.world != world || route == null)
                    throw new ChunkProtocolException("selector candidate lacks current owner-published membership facts");
                result.add(new Candidate(entity, fact, route.position, route.revision));
            }
            return new Cut(world, candidateRevisions.getOrDefault(world, 0L), List.copyOf(result));
        } finally { candidateMetadata.unlock(); }
    }
    public Cut observe(ServerLevel world) {
        try (var turn = service.referenceAccess()) { return cut(world); }
    }
    /** Exact addPassenger/removePassenger writer publishes only direct retained aliases. Reading
     * an indirect passenger here would traverse a descendant before its owner had been enclosed;
     * the retained direct graph is expanded and revalidated without dereferencing those aliases. */
    public static void passengersChanged(Entity vehicle) {
        Fact fact = vehicle instanceof Access access ? access.phoenium$referenceFact() : null;
        if (fact == null) return;
        List<Entity> passengers = List.copyOf(vehicle.getPassengers());
        var facts = FullFinishingServices.forServer(fact.server).entityFacts;
        facts.candidateMetadata.lock();
        try {
            for (Entity passenger : passengers) {
                Entity retainedVehicle = facts.passengerVehicles.get(passenger);
                if (retainedVehicle != null && retainedVehicle != vehicle)
                    throw new ChunkProtocolException("passenger relation has multiple retained vehicles");
            }
            List<Entity> previous = facts.passengerRelations.put(vehicle, passengers);
            long revision = Math.incrementExact(facts.routingSequence); facts.routingSequence = revision;
            if (previous != null) for (Entity passenger : previous) {
                facts.passengerVehicles.remove(passenger, vehicle); facts.relationRevisions.put(passenger, revision);
            }
            for (Entity passenger : passengers) {
                facts.passengerVehicles.put(passenger, vehicle);
                facts.relationRevisions.put(passenger, revision);
            }
            facts.relationRevisions.put(vehicle, revision);
        }
        finally { facts.candidateMetadata.unlock(); }
        facts.service.changed(FullFinishingServices.Kind.TRACKER);
    }
    /** Exact owner-published routing receipt for one alias already selected by an original
     * service directory. The returned pointer is retained only together with its incarnation,
     * fact identity, and routing revision; it grants no authority to dereference the alias. */
    public Candidate trackingCandidate(ServerLevel world, Entity alias) {
        Objects.requireNonNull(alias, "alias");
        FullFinishingServices.requireWorld(world, FullFinishingServices.Kind.ENTITY_REGISTRY);
        candidateMetadata.lock();
        try {
            Fact fact = alias instanceof Access access ? access.phoenium$referenceFact() : null;
            Route route = routes.get(alias);
            if (fact == null || fact.alias != alias || fact.world != world || route == null
                    || fact.incarnation != ((Access) alias).phoenium$entityIncarnation())
                throw new ChunkProtocolException("tracking alias lacks current owner-published identity and route");
            return new Candidate(alias, fact, route.position, route.revision);
        } finally { candidateMetadata.unlock(); }
    }

    public boolean validTrackingCandidate(Candidate candidate) {
        Objects.requireNonNull(candidate, "candidate");
        FullFinishingServices.requireWorld(candidate.fact.world, FullFinishingServices.Kind.ENTITY_REGISTRY);
        candidateMetadata.lock();
        try {
            if (!(candidate.alias instanceof Access access)
                    || access.phoenium$referenceFact() != candidate.fact
                    || access.phoenium$entityIncarnation() != candidate.fact.incarnation) return false;
            Route route = routes.get(candidate.alias);
            return Objects.equals(route, new Route(candidate.position, candidate.routingRevision));
        } finally { candidateMetadata.unlock(); }
    }

    /** Exact immutable position from the same original insertion/movement edge as the
     * routing receipt. Nearby packet selection can read this published value without
     * acquiring or dereferencing another owner's live player. No entity scan is needed. */
    public PublishedPosition publishedPosition(Entity alias) {
        Objects.requireNonNull(alias, "alias");
        FullFinishingServices.requireServer(service.server(), FullFinishingServices.Kind.ENTITY_REGISTRY);
        candidateMetadata.lock();
        try {
            Fact fact = alias instanceof Access access ? access.phoenium$referenceFact() : null;
            Route route = routes.get(alias);
            Vec3 precise = precisePositions.get(alias);
            if (fact == null || fact.alias != alias || fact.server != service.server() || route == null || precise == null
                    || fact.incarnation != ((Access) alias).phoenium$entityIncarnation())
                throw new ChunkProtocolException("nearby recipient lacks its current published position");
            return new PublishedPosition(new Candidate(alias, fact, route.position, route.revision), precise);
        } finally { candidateMetadata.unlock(); }
    }

    /** Exact reverse edge used by ServerLevel's riding check. */
    public VehicleRelation trackingVehicle(Candidate passenger) {
        Objects.requireNonNull(passenger, "passenger");
        FullFinishingServices.requireWorld(passenger.fact.world, FullFinishingServices.Kind.ENTITY_REGISTRY);
        candidateMetadata.lock();
        try {
            if (!validCandidateLocked(passenger)) throw new ChunkProtocolException("tracking passenger source became stale");
            Entity vehicleAlias = passengerVehicles.get(passenger.alias);
            Candidate vehicle = vehicleAlias == null ? null : candidateLocked(passenger.fact.world, vehicleAlias, "tracking vehicle");
            return new VehicleRelation(passenger, vehicle, relationRevisions.getOrDefault(passenger.alias, 0L));
        } finally { candidateMetadata.unlock(); }
    }
    public boolean validTrackingVehicle(VehicleRelation retained) {
        Objects.requireNonNull(retained, "retained");
        FullFinishingServices.requireWorld(retained.passenger.fact.world, FullFinishingServices.Kind.ENTITY_REGISTRY);
        candidateMetadata.lock();
        try {
            if (!validCandidateLocked(retained.passenger)
                    || relationRevisions.getOrDefault(retained.passenger.alias, 0L) != retained.revision) return false;
            Entity current = passengerVehicles.get(retained.passenger.alias);
            if (retained.vehicle == null) return current == null;
            return current == retained.vehicle.alias && validCandidateLocked(retained.vehicle);
        } finally { candidateMetadata.unlock(); }
    }
    private Candidate candidateLocked(ServerLevel world, Entity alias, String role) {
        Fact fact = alias instanceof Access access ? access.phoenium$referenceFact() : null;
        Route route = routes.get(alias);
        if (fact == null || fact.alias != alias || fact.world != world || route == null
                || fact.incarnation != ((Access) alias).phoenium$entityIncarnation())
            throw new ChunkProtocolException(role + " lacks current owner-published identity and route");
        return new Candidate(alias, fact, route.position, route.revision);
    }
    private boolean validCandidateLocked(Candidate candidate) {
        if (!(candidate.alias instanceof Access access) || access.phoenium$referenceFact() != candidate.fact
                || access.phoenium$entityIncarnation() != candidate.fact.incarnation) return false;
        return Objects.equals(routes.get(candidate.alias), new Route(candidate.position, candidate.routingRevision));
    }
    private void removeRelationsLocked(Entity entity) {
        long revision = Math.incrementExact(routingSequence); routingSequence = revision;
        List<Entity> children = passengerRelations.remove(entity);
        if (children != null) for (Entity child : children) {
            passengerVehicles.remove(child, entity); relationRevisions.put(child, revision);
        }
        Entity parent = passengerVehicles.remove(entity);
        if (parent != null) relationRevisions.put(parent, revision);
        relationRevisions.remove(entity);
    }

    /** Refreshes a retained tracker's published route without admitting a new entity lifetime.
     * Original entity movement may publish after the previous tracker cycle and before a player
     * insertion/movement/removal callback. The old position is not a current selection receipt. */
    public Candidate refreshTrackingCandidate(Candidate retained) {
        Objects.requireNonNull(retained, "retained");
        FullFinishingServices.requireWorld(retained.fact.world, FullFinishingServices.Kind.ENTITY_REGISTRY);
        candidateMetadata.lock();
        try {
            Candidate current = candidateLocked(retained.fact.world, retained.alias, "retained tracker");
            if (current.fact.incarnation != retained.fact.incarnation
                    || current.fact.runtimeId != retained.fact.runtimeId
                    || !current.fact.uuid.equals(retained.fact.uuid))
                throw new ChunkProtocolException("retained tracker entity lifetime changed");
            return current;
        } finally { candidateMetadata.unlock(); }
    }

    public List<Candidate> trackingPassengers(Candidate vehicle) {
        Objects.requireNonNull(vehicle, "vehicle");
        FullFinishingServices.requireWorld(vehicle.fact.world, FullFinishingServices.Kind.ENTITY_REGISTRY);
        candidateMetadata.lock();
        try {
            FreshPassengerAdmission admission = pendingPassengerIdentities.get(vehicle.alias);
            if (admission != null) admission.validate();
            if (!(vehicle.alias instanceof Access access) || access.phoenium$referenceFact() != vehicle.fact
                    || !Objects.equals(routes.get(vehicle.alias), new Route(vehicle.position, vehicle.routingRevision)))
                throw new ChunkProtocolException("tracking vehicle relation source became stale");
            List<Entity> aliases = orderedDescendants(vehicle.alias,
                alias -> passengerRelations.getOrDefault(alias, List.of()));
            var result = new ArrayList<Candidate>(aliases.size());
            for (Entity alias : aliases) {
                var passengerAdmission = pendingPassengerIdentities.get(alias);
                if (passengerAdmission != null && passengerAdmission != admission)
                    throw passengerFailure(vehicle.alias, alias, passengerDepth(vehicle.alias, alias),
                        "passenger belongs to an unrelated pending graph", passengerAdmission);
                Fact fact = alias instanceof Access passenger ? passenger.phoenium$referenceFact() : null;
                Route route = routes.get(alias);
                if (fact == null || fact.alias != alias || fact.world != vehicle.fact.world || route == null
                        || fact.incarnation != ((Access) alias).phoenium$entityIncarnation())
                    throw passengerFailure(vehicle.alias, alias, passengerDepth(vehicle.alias, alias),
                        fact == null ? "missing reference fact" : route == null ? "missing route"
                            : "foreign world or stale exact native incarnation", admission);
                result.add(new Candidate(alias, fact, route.position, route.revision));
            }
            return List.copyOf(result);
        } finally { candidateMetadata.unlock(); }
    }
    /** Revalidates the exact retained relation edge graph as well as every routed identity. */
    public boolean validTrackingPassengers(Candidate vehicle, List<Candidate> retained) {
        Objects.requireNonNull(vehicle, "vehicle"); Objects.requireNonNull(retained, "retained");
        FullFinishingServices.requireWorld(vehicle.fact.world, FullFinishingServices.Kind.ENTITY_REGISTRY);
        candidateMetadata.lock();
        try {
            FreshPassengerAdmission admission = pendingPassengerIdentities.get(vehicle.alias);
            if (admission != null) admission.validate();
            if (!(vehicle.alias instanceof Access access) || access.phoenium$referenceFact() != vehicle.fact
                    || !Objects.equals(routes.get(vehicle.alias), new Route(vehicle.position, vehicle.routingRevision))) return false;
            List<Entity> current = orderedDescendants(vehicle.alias,
                alias -> passengerRelations.getOrDefault(alias, List.of()));
            if (current.size() != retained.size()) return false;
            for (int i = 0; i < current.size(); i++) {
                Candidate expected = retained.get(i); Entity alias = current.get(i);
                if (alias != expected.alias) return false;
                var passengerAdmission = pendingPassengerIdentities.get(alias);
                if (passengerAdmission != null && passengerAdmission != admission) return false;
                if (!(alias instanceof Access passenger) || passenger.phoenium$referenceFact() != expected.fact
                        || passenger.phoenium$entityIncarnation() != expected.fact.incarnation
                        || !Objects.equals(routes.get(alias), new Route(expected.position, expected.routingRevision))) return false;
            }
            return true;
        } finally { candidateMetadata.unlock(); }
    }
    /** Matches Entity.getIndirectPassengers' direct-child then descendant order while rejecting
     * malformed cycles in the retained identity graph. Package-visible for a focused order proof. */
    static <E> List<E> orderedDescendants(E root, Function<E, List<E>> children) {
        Objects.requireNonNull(root, "root"); Objects.requireNonNull(children, "children");
        var result = new ArrayList<E>();
        Set<E> visiting = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<E> emitted = Collections.newSetFromMap(new IdentityHashMap<>());
        visiting.add(root);
        appendDescendants(root, children, visiting, emitted, result);
        return List.copyOf(result);
    }
    private static <E> void appendDescendants(E parent, Function<E, List<E>> children,
                                               Set<E> visiting, Set<E> emitted, List<E> result) {
        for (E child : List.copyOf(children.apply(parent))) {
            if (child == null) throw new ChunkProtocolException("null passenger relation alias");
            if (!visiting.add(child)) throw new ChunkProtocolException("cyclic passenger relation alias");
            if (!emitted.add(child)) throw new ChunkProtocolException("duplicate passenger relation alias");
            result.add(child);
            appendDescendants(child, children, visiting, emitted, result);
            visiting.remove(child);
        }
    }
    Estimate estimate(ServerLevel world) {
        try (var turn = service.referenceAccess()) {
            FullFinishingServices.requireWorld(world, FullFinishingServices.Kind.ENTITY_REGISTRY);
            candidateMetadata.lock();
            try { return new Estimate(world, candidateRevisions.getOrDefault(world, 0L),
                    candidates.getOrDefault(world, new LinkedHashMap<>()).size()); }
            finally { candidateMetadata.unlock(); }
        }
    }
    Cut observe(ServerLevel world, Estimate estimate) {
        if (estimate.world != world) throw new ChunkProtocolException("foreign entity candidate estimate");
        try (var turn = service.referenceAccess()) {
            FullFinishingServices.requireWorld(world, FullFinishingServices.Kind.ENTITY_REGISTRY);
            candidateMetadata.lock();
            try {
                var indexed = candidates.getOrDefault(world, new LinkedHashMap<>());
                if (candidateRevisions.getOrDefault(world, 0L) != estimate.revision || indexed.size() != estimate.candidates)
                    return null;
                var result = new ArrayList<Candidate>(estimate.candidates);
                for (Entity entity : indexed.values()) {
                    Fact fact = entity instanceof Access access ? access.phoenium$referenceFact() : null;
                    Route route = routes.get(entity);
                    if (fact == null || fact.alias != entity || fact.world != world || route == null)
                        throw new ChunkProtocolException("selector candidate lacks current owner-published membership facts");
                    result.add(new Candidate(entity, fact, route.position, route.revision));
                }
                return new Cut(world, estimate.revision, List.copyOf(result));
            } finally { candidateMetadata.unlock(); }
        }
    }
    public boolean valid(Cut cut) {
        candidateMetadata.lock();
        try {
            if (candidateRevisions.getOrDefault(cut.world, 0L) != cut.revision) return false;
            for (Candidate candidate : cut.candidates) {
                if (!(candidate.alias instanceof Access access) || access.phoenium$referenceFact() != candidate.fact
                        || !Objects.equals(routes.get(candidate.alias), new Route(candidate.position, candidate.routingRevision))) return false;
            }
            return true;
        } finally { candidateMetadata.unlock(); }
    }
    /** Earlier roots from this exact saved FULL may extend the directory while its original
     * post-load stream is running. Existing entries remain an exact prefix, and every appended
     * occurrence must belong to that reservation and one of its predeclared saved routes. */
    boolean valid(Cut cut, FullFinishingServices.Reservation reservation, int maximumCandidates,
                  Set<ChunkPos> declaredPositions) {
        if (reservation == null) return valid(cut);
        candidateMetadata.lock();
        try {
            var indexed = candidates.getOrDefault(cut.world, new LinkedHashMap<>());
            if (indexed.size() > maximumCandidates || indexed.size() < cut.candidates.size()) return false;
            var iterator = indexed.values().iterator();
            for (Candidate expected : cut.candidates) {
                if (!iterator.hasNext() || iterator.next() != expected.alias) return false;
                if (!(expected.alias instanceof Access access) || access.phoenium$referenceFact() != expected.fact
                        || !Objects.equals(routes.get(expected.alias), new Route(expected.position, expected.routingRevision))) return false;
            }
            while (iterator.hasNext()) {
                Entity entity = iterator.next();
                Fact fact = entity instanceof Access access ? access.phoenium$referenceFact() : null;
                Route route = routes.get(entity);
                if (fact == null || fact.alias != entity || fact.world != cut.world || route == null
                        || !reservation.owns(entity) || !declaredPositions.contains(route.position)) return false;
            }
            return true;
        } finally { candidateMetadata.unlock(); }
    }
    private long nextCandidateRevision(ServerLevel world) {
        return Math.incrementExact(candidateRevisions.getOrDefault(world, 0L));
    }
    public Entity lookup(ServerLevel origin, UUID id) {
        FullFinishingServices.requireWorld(origin, FullFinishingServices.Kind.ENTITY_REGISTRY);
        FullFinishingServices.Reservation full = FullFinishingServices.currentReservation(origin.getServer());
        Entity found = directories.getOrDefault(origin, Map.of()).get(id);
        if (found != null) { require(found); return found; }
        for (ServerLevel world : full.lookupWorlds()) if (world != origin) {
            FullFinishingServices.requireWorld(world, FullFinishingServices.Kind.ENTITY_REGISTRY);
            found = directories.getOrDefault(world, Map.of()).get(id);
            if (found != null) { require(found); return found; }
        }
        return null;
    }
    public static boolean foreign(Entity entity) {
        if (PUBLISHING.get()) return false;
        FullFinishingServices.Reservation full = FullFinishingServices.currentReservation();
        return full != null && !full.owns(entity) && !FullSavedContextInputs.ownsSelectorCandidate(entity);
    }
    public static Fact require(Entity alias) {
        FullFinishingServices.Reservation full = FullFinishingServices.currentReservation();
        if (full == null) throw new ChunkProtocolException("reference facts requested outside their FULL operation");
        Fact fact = alias instanceof Access access ? access.phoenium$referenceFact() : null;
        if (fact == null || fact.alias != alias || fact.incarnation != ((Access) alias).phoenium$entityIncarnation()
                || fact.dispatch != alias.getClass())
            throw FullFinishingServices.failure(new ChunkProtocolException("present reference alias has no current owner-published facts"));
        FullFinishingServices.requireWorld(fact.world, FullFinishingServices.Kind.ENTITY_REGISTRY);
        return fact;
    }
    public static UUID uuid(Entity entity, Supplier<UUID> original) { return foreign(entity) ? require(entity).uuid : original.get(); }
    public static String scoreboardName(Entity entity, Supplier<String> original) { return foreign(entity) ? require(entity).scoreboardName : original.get(); }
    public static int id(Entity entity, java.util.function.IntSupplier original) {
        if (!foreign(entity)) return original.getAsInt();
        int id = require(entity).runtimeId;
        if (id == 0) throw new IllegalStateException("Tried to access entity ID before ID assignment");
        return id;
    }
    public static boolean removed(Entity entity, java.util.function.BooleanSupplier original) { return foreign(entity) ? require(entity).removed : original.getAsBoolean(); }
    public static boolean enemy(LivingEntity entity, java.util.function.BooleanSupplier original) { return foreign(entity) ? require(entity).enemy : original.getAsBoolean(); }
    public static boolean creative(Player entity, java.util.function.BooleanSupplier original) { return foreign(entity) ? require(entity).creative : original.getAsBoolean(); }
    public static boolean spectator(Player entity, java.util.function.BooleanSupplier original) { return foreign(entity) ? require(entity).spectator : original.getAsBoolean(); }
    public static Holder<EntityType<?>> type(Entity entity, Supplier<Holder<EntityType<?>>> original) {
        return foreign(entity) ? require(entity).type.builtInRegistryHolder() : original.get();
    }
    public Map<String, Long> measurements() { return Map.of(
            "factsPublished", published, "insertions", insertions, "removals", removals,
            "addressable", directories.values().stream().mapToLong(Map::size).sum(),
            "naturalPassengerScopes", naturalPassengerScopes, "directPassengerPrepared", directPassengerPrepared,
            "directPassengerAccepted", directPassengerAccepted, "directPassengerRejected", directPassengerRejected,
            "directPassengerSettled", directPassengerSettled, "directPassengerUpgraded", directPassengerUpgraded); }
    void naturalScopeEntered() { naturalPassengerScopes++; }
    void directAdmissionPrepared() { directPassengerPrepared++; }
    void directInsertionReturned(boolean accepted) {
        if (accepted) directPassengerAccepted++; else directPassengerRejected++;
    }
    void directAdmissionSettled(FreshPassengerAdmission admission, boolean upgraded) {
        directPassengerSettled++;
        if (upgraded) directPassengerUpgraded++;
        if (upgraded && directPassengerSuccessReported.compareAndSet(false, true))
            org.slf4j.LoggerFactory.getLogger("Phoenium").info(
                "PHOENIUM_PASSENGER_IDENTITY_DIRECT_PATH {} result=UPGRADED_AND_SETTLED", admission.successDescription());
    }
    boolean hasRoute(Entity entity) {
        candidateMetadata.lock();
        try { return routes.containsKey(entity); } finally { candidateMetadata.unlock(); }
    }
    void requireFreshWorld(ServerLevel world) {
        candidateMetadata.lock();
        try {
            if (closedPassengerWorlds.contains(world)) throw new ChunkProtocolException("fresh passenger world already closed");
        } finally { candidateMetadata.unlock(); }
    }
    void closePassengerWorld(ServerLevel world) {
        candidateMetadata.lock();
        try {
            closedPassengerWorlds.add(world);
            for (var admission : new HashSet<>(pendingPassengerIdentities.values()))
                if (admission.world() == world) admission.shutdown();
        } finally { candidateMetadata.unlock(); }
    }
    boolean hasStorage(Entity entity) {
        candidateMetadata.lock();
        try { return stored.containsKey(entity); } finally { candidateMetadata.unlock(); }
    }
    void admitPassengerIdentity(Entity entity, FreshPassengerAdmission admission) {
        candidateMetadata.lock();
        try {
            if (pendingPassengerIdentities.putIfAbsent(entity, admission) != null)
                throw new ChunkProtocolException("fresh passenger identity already admitted");
        } finally { candidateMetadata.unlock(); }
    }
    void releasePassengerIdentity(Entity entity, FreshPassengerAdmission admission) {
        candidateMetadata.lock();
        try { pendingPassengerIdentities.remove(entity, admission); } finally { candidateMetadata.unlock(); }
    }
    public static void passengerTrackingStarted(Entity entity) { passengerLifecycle(entity, 0); }
    public static void passengerTrackingCompleted(Entity entity) { passengerLifecycle(entity, 1); }
    public static void passengerRemoved(Entity entity) { passengerLifecycle(entity, 2); }
    private static void passengerLifecycle(Entity entity, int edge) {
        Fact fact = entity instanceof Access access ? access.phoenium$referenceFact() : null;
        if (fact == null) return;
        var facts = FullFinishingServices.existingPassengerFacts(fact.server);
        if (facts == null) return;
        facts.candidateMetadata.lock();
        try {
            var admission = facts.pendingPassengerIdentities.get(entity);
            if (admission == null) return;
            if (edge == 0) admission.trackingStarted(entity);
            else if (edge == 1) admission.trackingCompleted(entity);
            else admission.removed(entity);
        } finally { facts.candidateMetadata.unlock(); }
    }
    private int passengerDepth(Entity root, Entity passenger) {
        int depth = 0; Set<Entity> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Entity node = passenger; node != null && node != root && seen.add(node); node = passengerVehicles.get(node)) depth++;
        return depth;
    }
    ChunkProtocolException passengerFailure(Entity root, Entity passenger, int depth, String reason,
            FreshPassengerAdmission admission) {
        if (passengerFailureReported.compareAndSet(false, true))
            org.slf4j.LoggerFactory.getLogger("Phoenium").error(
                "PHOENIUM_PASSENGER_IDENTITY_FIRST_FAILURE root={} passenger={} depth={} pendingAdmission={} rootPublication={} passengerPublication={} reason={}",
                diagnosticIdentity(root), diagnosticIdentity(passenger), depth, admission != null,
                admission == null ? "NO_PENDING_ADMISSION" : admission.describe(root),
                admission == null ? "NO_PENDING_ADMISSION" : admission.describe(passenger), reason);
        return new ChunkProtocolException("tracking passenger lacks current owner-published identity and route: " + reason);
    }
    private static String diagnosticIdentity(Entity entity) {
        Fact fact = entity instanceof Access access ? access.phoenium$referenceFact() : null;
        return entity.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(entity))
            + ":type=" + entity.getType()
            + (fact == null ? ":fact=ABSENT" + (entity instanceof Access access
                ? ",nativeIncarnation=" + access.phoenium$entityIncarnation() + ",id=" + access.phoenium$rawEntityId() : "")
                : ":type=" + fact.type + ",uuid=" + fact.uuid + ",id=" + fact.runtimeId
                + ",nativeIncarnation=" + fact.incarnation + ",world=" + fact.world.dimension().identifier()
                + ",currentOwnership=" + dev.phoenium.Phoenium.liveRuntime().passengerOwnershipDiagnostic(fact.uuid.toString()));
    }
    void close() {
        directories.clear();
        candidateMetadata.lock();
        try {
            for (var admission : new HashSet<>(pendingPassengerIdentities.values())) admission.shutdown();
            pendingPassengerIdentities.clear();
            closedPassengerWorlds.clear();
            candidates.clear(); candidateRevisions.clear(); routes.clear(); stored.clear(); precisePositions.clear(); passengerRelations.clear();
            passengerVehicles.clear(); relationRevisions.clear(); }
        finally { candidateMetadata.unlock(); }
    }
}
