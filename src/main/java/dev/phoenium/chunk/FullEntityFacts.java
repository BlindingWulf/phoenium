package dev.phoenium.chunk;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;

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
    public record Cut(ServerLevel world, long revision, List<Candidate> candidates) { }
    private record Route(ChunkPos position, long revision) { }
    private static final AtomicLong INCARNATIONS = new AtomicLong();
    private static final ThreadLocal<Boolean> PUBLISHING = ThreadLocal.withInitial(() -> false);
    private final FullFinishingServices service;
    private final IdentityHashMap<ServerLevel, Map<UUID, Entity>> directories = new IdentityHashMap<>();
    private final IdentityHashMap<ServerLevel, LinkedHashMap<Integer, Entity>> candidates = new IdentityHashMap<>();
    private final IdentityHashMap<ServerLevel, Long> candidateRevisions = new IdentityHashMap<>();
    private final IdentityHashMap<Entity, Route> routes = new IdentityHashMap<>();
    private final java.util.concurrent.locks.ReentrantLock candidateMetadata = new java.util.concurrent.locks.ReentrantLock();
    private long routingSequence;
    private long published, insertions, removals;
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
        facts.candidateMetadata.lock();
        try {
            long routeRevision = Math.incrementExact(facts.routingSequence);
            long worldRevision = facts.nextCandidateRevision(fact.world);
            facts.candidates.computeIfAbsent(fact.world, ignored -> new LinkedHashMap<>()).put(id, entity);
            if (displaced != null && displaced != entity) facts.routes.remove(displaced);
            facts.routes.put(entity, new Route(position, routeRevision));
            facts.routingSequence = routeRevision; facts.candidateRevisions.put(fact.world, worldRevision);
        } finally { facts.candidateMetadata.unlock(); }
        facts.service.changed(FullFinishingServices.Kind.ENTITY_REGISTRY);
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
            facts.routes.remove(entity); facts.candidateRevisions.put(fact.world, worldRevision);
        } finally { facts.candidateMetadata.unlock(); }
        facts.service.changed(FullFinishingServices.Kind.ENTITY_REGISTRY);
    }
    /** Owner-side movement publishes routing metadata without reading through a foreign alias. */
    public static void routed(Entity entity) {
        Fact fact = entity instanceof Access access ? access.phoenium$referenceFact() : null;
        if (fact == null) return;
        var facts = FullFinishingServices.forServer(fact.server).entityFacts;
        ChunkPos position = entity.chunkPosition();
        boolean indexed;
        facts.candidateMetadata.lock();
        try {
            indexed = facts.routes.containsKey(entity);
            if (indexed) {
                long routeRevision = Math.incrementExact(facts.routingSequence);
                long worldRevision = facts.nextCandidateRevision(fact.world);
                facts.routes.put(entity, new Route(position, routeRevision));
                facts.routingSequence = routeRevision; facts.candidateRevisions.put(fact.world, worldRevision);
            }
        } finally { facts.candidateMetadata.unlock(); }
        if (indexed) facts.service.changed(FullFinishingServices.Kind.ENTITY_REGISTRY);
    }
    public Cut cut(ServerLevel world) {
        FullFinishingServices.requireWorld(world, FullFinishingServices.Kind.ENTITY_REGISTRY);
        candidateMetadata.lock();
        try {
            var result = new ArrayList<Candidate>();
            for (Entity entity : candidates.getOrDefault(world, new LinkedHashMap<>()).values()) {
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
    public Map<String, Long> measurements() { return Map.of("factsPublished", published, "insertions", insertions, "removals", removals,
            "addressable", directories.values().stream().mapToLong(Map::size).sum()); }
    void close() {
        directories.clear();
        candidateMetadata.lock();
        try { candidates.clear(); candidateRevisions.clear(); routes.clear(); }
        finally { candidateMetadata.unlock(); }
    }
}
