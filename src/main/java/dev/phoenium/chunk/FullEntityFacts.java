package dev.phoenium.chunk;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;

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
    private static final AtomicLong INCARNATIONS = new AtomicLong();
    private static final ThreadLocal<Boolean> PUBLISHING = ThreadLocal.withInitial(() -> false);
    private final FullFinishingServices service;
    private final IdentityHashMap<ServerLevel, Map<UUID, Entity>> directories = new IdentityHashMap<>();
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
        return full != null && !full.owns(entity);
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
    void close() { directories.clear(); }
}
