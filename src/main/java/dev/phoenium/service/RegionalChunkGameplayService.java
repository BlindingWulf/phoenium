package dev.phoenium.service;

import dev.phoenium.chunk.FullEntityFacts;
import dev.phoenium.chunk.FullFinishingScoreboard;
import dev.phoenium.chunk.FullFinishingServices;
import dev.phoenium.core.WorldId;
import dev.phoenium.mixin.RegionalChunkMapAccess;
import dev.phoenium.mixin.RegionalServerChunkCacheAccess;
import dev.phoenium.mixin.RegionalSpawnStateAccess;
import dev.phoenium.mixin.RegionalVisibleChunkAccess;
import dev.phoenium.mixin.RegionalTimedCustomSpawnerAccess;
import dev.phoenium.mixin.RegionalTraderSpawnerAccess;
import dev.phoenium.ownership.ObjectOwnershipRegistry;
import dev.phoenium.ownership.OwnershipContext;
import dev.phoenium.topology.RegionDirectory;
import dev.phoenium.topology.RoutingKey;
import dev.phoenium.topology.TopologyPlanner;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.*;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.entity.EntityTickList;
import net.minecraft.world.phys.Vec3;

/** Owner-free coordinator for ServerChunkCache's exact selected gameplay cohorts. Temporary spawn
 * state follows original order; each chunk callback executes synchronously under its current owner. */
public final class RegionalChunkGameplayService {
    @FunctionalInterface public interface SpawnFactory {
        NaturalSpawner.SpawnState create(int count, Iterable<Entity> entities,
            NaturalSpawner.ChunkGetter chunks, LocalMobCapCalculator caps);
    }
    private final WorldId world;
    private final ServerLevel level;
    private final ServerChunkCache cache;
    private final ChunkMap map;
    private final RegionDirectory directory;
    private final RegionalServiceDispatcher dispatcher;
    private final ObjectOwnershipRegistry objects;
    private final FullFinishingServices finishing;
    private final int recipientRadius;
    private Cycle cycle;
    private long cycles, entityContributions, chunkCallbacks;

    public RegionalChunkGameplayService(WorldId world, ServerLevel level, ServerChunkCache cache,
            RegionDirectory directory, RegionalServiceDispatcher dispatcher, ObjectOwnershipRegistry objects) {
        this.world = Objects.requireNonNull(world); this.level = Objects.requireNonNull(level);
        this.cache = Objects.requireNonNull(cache); this.map = cache.chunkMap;
        this.directory = Objects.requireNonNull(directory); this.dispatcher = Objects.requireNonNull(dispatcher);
        this.objects = Objects.requireNonNull(objects); this.finishing = FullFinishingServices.forServer(level.getServer());
        int maximum = 0;
        for (EntityType<?> type : net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE)
            maximum = Math.max(maximum, type.clientTrackingRange() * 16);
        int tracking = level.getServer().getScaledTrackingDistance(maximum);
        this.recipientRadius = (tracking + 15) / 16 + 2;
    }

    public void runOriginal() {
        requireOwnerFree();
        if (cycle != null) throw new IllegalStateException("nested regional chunk gameplay cycle");
        FullFinishingServices.requireWorld(level, FullFinishingServices.Kind.ENTITY_REGISTRY);
        FullFinishingServices.requireWorld(level, FullFinishingServices.Kind.TRACKER);
        FullFinishingServices.requireServer(level.getServer(), FullFinishingServices.Kind.CONNECTIONS);
        PlayerCut players = capturePlayers();
        cycle = new Cycle(players.cut, players.players);
        try { ((RegionalServerChunkCacheAccess) cache).phoenium$invokeTickChunks(); cycles = Math.incrementExact(cycles); }
        finally { cycle = null; }
    }

    public NaturalSpawner.SpawnState createSpawnState(int count, Iterable<Entity> originalEntities,
            NaturalSpawner.ChunkGetter chunks, SpawnFactory factory) {
        Cycle active = requireCycle();
        List<Entity> originalOrder = new ArrayList<>(); originalEntities.forEach(originalOrder::add);
        FullEntityFacts.Cut cut = finishing.entityFacts.cut(level);
        if (cut.candidates().size() != originalOrder.size())
            throw new IllegalStateException("spawn entity cohort differs from retained registry");
        for (int i = 0; i < originalOrder.size(); i++) if (cut.candidates().get(i).alias() != originalOrder.get(i))
            throw new IllegalStateException("spawn entity cohort order differs from retained registry");
        var caps = new RetainedLocalMobCaps(map, () -> active.players);
        NaturalSpawner.SpawnState state = factory.create(count, List.of(), chunks, caps);
        var access = (RegionalSpawnStateAccess) (Object) state;
        Object2IntOpenHashMap<MobCategory> counts = access.phoenium$getMobCategoryCounts();
        PotentialCalculator potential = access.phoenium$getSpawnPotential();
        for (FullEntityFacts.Candidate candidate : cut.candidates()) {
            Contribution[] captured = new Contribution[1]; RoutingKey key = key(candidate);
            dispatcher.executeAndDrain(key, RegionalServiceKind.SPAWN_STATE_CAPTURE, ignored -> {
                requireEntity(candidate, key);
                Entity entity = candidate.alias();
                if (entity instanceof Mob mob && (mob.isPersistenceRequired() || mob.requiresCustomPersistence())) return;
                MobCategory category = entity.getType().getCategory();
                if (category == MobCategory.MISC) return;
                BlockPos position = entity.blockPosition();
                ((RegionalServerChunkCacheAccess) cache).phoenium$invokeGetFullChunk(ChunkPos.pack(position), chunk -> {
                    dispatcher.requireCurrentOwner(new RoutingKey(world, chunk.getPos().x(), chunk.getPos().z()));
                    MobSpawnSettings.MobSpawnCost cost = chunk.getNoiseBiome(QuartPos.fromBlock(position.getX()),
                        QuartPos.fromBlock(position.getY()), QuartPos.fromBlock(position.getZ()))
                        .value().getMobSettings().getMobSpawnCost(entity.getType());
                    captured[0] = new Contribution(position.immutable(), category,
                        cost == null ? 0.0 : cost.charge(), entity instanceof Mob);
                });
            });
            Contribution contribution = captured[0];
            if (contribution != null) {
                potential.addCharge(contribution.position, contribution.charge);
                if (contribution.mob) caps.addMob(ChunkPos.containing(contribution.position), contribution.category);
                counts.addTo(contribution.category, 1); entityContributions = Math.incrementExact(entityContributions);
            }
        }
        active.spawnState = state;
        return state;
    }

    public void collectSpawningChunks(ChunkMap expected, List<LevelChunk> output) {
        Cycle active = requireCycle(); requireMap(expected);
        var distance = map.getDistanceManager();
        var keys = distance.getSpawnCandidateChunks();
        while (keys.hasNext()) {
            long packed = keys.nextLong();
            ChunkHolder holder = ((RegionalVisibleChunkAccess) map).phoenium$visible(packed);
            if (holder == null) continue;
            LevelChunk chunk = holder.getTickingChunk();
            if (chunk != null && active.players.stream().anyMatch(player -> close(player, holder.getPos()))) output.add(chunk);
        }
    }

    public void forEachBlockTickingChunk(ChunkMap expected, Consumer<LevelChunk> output) {
        requireCycle(); requireMap(expected);
        map.getDistanceManager().forEachEntityTickingChunk(packed -> {
            ChunkHolder holder = ((RegionalVisibleChunkAccess) map).phoenium$visible(packed);
            if (holder == null) return;
            LevelChunk chunk = holder.getTickingChunk();
            if (chunk != null) blockTickCallback(chunk, () -> output.accept(chunk));
        });
    }

    /** Radius eight is the pinned tickThunder lightning-rod POI query's 128-block terrain reach.
     * NaturalSpawner's unbounded player-distance selection is handled from published positions. */
    public void spawningChunkCallback(LevelChunk chunk, Runnable original) { chunkCallback(chunk, 8, original); }
    public void blockTickCallback(LevelChunk chunk, Runnable original) { chunkCallback(chunk, 1, original); }
    public void broadcastChunkCallback(LevelChunk chunk, Runnable original) { chunkCallback(chunk, 0, original); }
    /** The pinned custom spawner set chooses terrain only relative to the current level-player
     * cohort. Its widest path is wandering-trader meeting POI radius 48 followed by a radius-48
     * spawn search; seven chunk keys cover that path and the final 2x3x2 space probe. */
    public void customSpawnerCallback(CustomSpawner spawner, boolean spawnEnemies, Runnable original) {
        requireCycle(); Objects.requireNonNull(original);
        int radius = customSpawnerRadius(spawner, spawnEnemies);
        if (radius < 0) { original.run(); return; }
        List<PlayerProjection> players = captureLevelPlayers(); cycle.levelPlayers = players;
        LinkedHashSet<RoutingKey> footprint = new LinkedHashSet<>();
        TopologyPlanner planner = new TopologyPlanner();
        for (PlayerProjection player : players) footprint.addAll(planner.requiredClosure(Set.of(player.key), radius));
        RoutingKey source = players.isEmpty() ? new RoutingKey(world, 0, 0) : players.get(0).key;
        if (footprint.isEmpty()) footprint.add(source);
        dispatcher.ensureClosure(Set.copyOf(footprint));
        dispatcher.executeAndDrain(source, RegionalServiceKind.CHUNK_GAMEPLAY, ignored -> {
            dispatcher.requireCurrentClosure(Set.copyOf(footprint));
            for (PlayerProjection player : players) requireEntity(player.candidate, player.key);
            original.run();
        });
        chunkCallbacks = Math.incrementExact(chunkCallbacks);
    }
    static int customSpawnerRadius(CustomSpawner spawner, boolean spawnEnemies) {
        if (spawner instanceof net.minecraft.world.level.levelgen.PhantomSpawner)
            return customSpawnerRadius(spawner.getClass(), ((RegionalTimedCustomSpawnerAccess) spawner).phoenium$nextTick(), spawnEnemies);
        if (spawner instanceof net.minecraft.world.level.levelgen.PatrolSpawner)
            return customSpawnerRadius(spawner.getClass(), ((RegionalTimedCustomSpawnerAccess) spawner).phoenium$nextTick(), spawnEnemies);
        if (spawner instanceof net.minecraft.world.entity.npc.CatSpawner)
            return customSpawnerRadius(spawner.getClass(), ((RegionalTimedCustomSpawnerAccess) spawner).phoenium$nextTick(), spawnEnemies);
        if (spawner instanceof net.minecraft.world.entity.ai.village.VillageSiege)
            return customSpawnerRadius(spawner.getClass(), 0, spawnEnemies);
        if (spawner instanceof net.minecraft.world.entity.npc.wanderingtrader.WanderingTraderSpawner)
            return customSpawnerRadius(spawner.getClass(), ((RegionalTraderSpawnerAccess) spawner).phoenium$tickDelay(), spawnEnemies);
        return customSpawnerRadius(spawner.getClass(), 0, spawnEnemies);
    }
    static int customSpawnerRadius(Class<?> type, int counter, boolean spawnEnemies) {
        if (type == net.minecraft.world.level.levelgen.PhantomSpawner.class) return spawnEnemies && counter <= 1 ? 1 : -1;
        if (type == net.minecraft.world.level.levelgen.PatrolSpawner.class) return spawnEnemies && counter <= 1 ? 4 : -1;
        if (type == net.minecraft.world.entity.npc.CatSpawner.class) return counter <= 1 ? 5 : -1;
        if (type == net.minecraft.world.entity.ai.village.VillageSiege.class) return spawnEnemies ? 3 : -1;
        if (type == net.minecraft.world.entity.npc.wanderingtrader.WanderingTraderSpawner.class) return counter <= 1 ? 7 : -1;
        throw new IllegalStateException("unreviewed custom spawner " + type.getName());
    }
    private void chunkCallback(LevelChunk chunk, int terrainRadius, Runnable original) {
        requireCycle(); Objects.requireNonNull(chunk); Objects.requireNonNull(original);
        cycle.players = capturePlayers().players;
        cycle.levelPlayers = captureLevelPlayers();
        RoutingKey source = new RoutingKey(world, chunk.getPos().x(), chunk.getPos().z());
        Set<RoutingKey> footprint = new LinkedHashSet<>(new TopologyPlanner().requiredClosure(Set.of(source), terrainRadius));
        for (PlayerProjection player : cycle.players)
            if (Math.abs((long) player.key.x() - source.x()) <= recipientRadius
                    && Math.abs((long) player.key.z() - source.z()) <= recipientRadius) footprint.add(player.key);
        dispatcher.ensureClosure(Set.copyOf(footprint));
        dispatcher.executeAndDrain(source, RegionalServiceKind.CHUNK_GAMEPLAY, ignored -> {
            dispatcher.requireCurrentOwner(source); cycle.callbackKey = source;
            try { original.run(); }
            finally { cycle.callbackKey = null; cycle.nearestPlayer = null; }
        });
        chunkCallbacks = Math.incrementExact(chunkCallbacks);
    }

    /** Pinned NaturalSpawner calls the boolean getNearestPlayer overload and immediately asks the
     * selected alias for its squared distance. Preserve the level player-list order and strict tie
     * behavior while reading only exact position/spectator publications. */
    public Player nearestNaturalSpawnPlayer(double x, double y, double z) {
        Cycle active = requireOwnedCycle();
        PlayerProjection best = null; double bestDistance = -1.0;
        for (PlayerProjection player : active.levelPlayers) {
            if (player.spectator) continue;
            double distance = player.position.distanceToSqr(x, y, z);
            if (bestDistance == -1.0 || distance < bestDistance) { best = player; bestDistance = distance; }
        }
        active.nearestPlayer = best;
        return best == null ? null : best.player;
    }
    public double naturalSpawnPlayerDistance(Player player, double x, double y, double z) {
        Cycle active = requireOwnedCycle(); PlayerProjection selected = active.nearestPlayer;
        if (selected == null || selected.player != player)
            throw new IllegalStateException("natural spawn nearest-player observation changed");
        return selected.position.distanceToSqr(x, y, z);
    }

    public void entityTicks(EntityTickList entities, Consumer<Entity> output) {
        requireOwnerFree(); Objects.requireNonNull(entities); Objects.requireNonNull(output);
        entities.forEach(entity -> {
            FullEntityFacts.Candidate source = finishing.entityFacts.trackingCandidate(level, entity);
            List<FullEntityFacts.Candidate> passengers = finishing.entityFacts.trackingPassengers(source);
            FullEntityFacts.VehicleRelation vehicle = finishing.entityFacts.trackingVehicle(source);
            RoutingKey key = key(source); LinkedHashSet<RoutingKey> footprint = new LinkedHashSet<>();
            footprint.add(key); passengers.forEach(passenger -> footprint.add(key(passenger)));
            if (vehicle.vehicle() != null) footprint.add(key(vehicle.vehicle()));
            PlayerCut currentPlayers = capturePlayers();
            for (PlayerProjection player : currentPlayers.players)
                if (Math.abs((long) player.key.x() - key.x()) <= recipientRadius
                        && Math.abs((long) player.key.z() - key.z()) <= recipientRadius) footprint.add(player.key);
            dispatcher.ensureClosure(Set.copyOf(footprint));
            dispatcher.executeAndDrain(key, RegionalServiceKind.CHUNK_GAMEPLAY, ignored -> {
                requireEntity(source, key);
                if (!finishing.entityFacts.validTrackingPassengers(source, passengers))
                    throw new IllegalStateException("entity tick passenger relation changed");
                if (!finishing.entityFacts.validTrackingVehicle(vehicle))
                    throw new IllegalStateException("entity tick vehicle relation changed");
                output.accept(entity);
            });
        });
    }

    private PlayerCut capturePlayers() {
        List<ServerPlayer> order = List.copyOf(((RegionalChunkMapAccess) map).phoenium$getPlayerMap().getAllPlayers());
        FullFinishingScoreboard.TrackingCut cut = finishing.scoreboard.trackingCut(order);
        Map<ServerPlayer, FullFinishingScoreboard.TrackingSession> sessions = new IdentityHashMap<>();
        cut.sessions().forEach(session -> sessions.put(session.player(), session));
        List<PlayerProjection> result = new ArrayList<>(order.size());
        for (ServerPlayer player : order) {
            var session = Objects.requireNonNull(sessions.get(player));
            FullEntityFacts.PublishedPosition published = finishing.entityFacts.publishedPosition(player);
            FullEntityFacts.Candidate candidate = published.candidate();
            if (candidate.fact().world() != level) throw new IllegalStateException("chunk gameplay player changed world");
            finishing.scoreboard.requireTrackingSession(cut, session);
            result.add(new PlayerProjection(player, session.listener(), candidate, key(candidate),
                published.position(), candidate.fact().spectator()));
        }
        return new PlayerCut(cut, List.copyOf(result));
    }
    private List<PlayerProjection> captureLevelPlayers() {
        List<PlayerProjection> result = new ArrayList<>();
        for (ServerPlayer player : List.copyOf(level.players())) {
            FullEntityFacts.PublishedPosition published = finishing.entityFacts.publishedPosition(player);
            FullEntityFacts.Candidate candidate = published.candidate();
            if (candidate.fact().world() != level) throw new IllegalStateException("natural spawn player changed world");
            result.add(new PlayerProjection(player, player.connection, candidate, key(candidate),
                published.position(), candidate.fact().spectator()));
        }
        return List.copyOf(result);
    }
    private void requireEntity(FullEntityFacts.Candidate candidate, RoutingKey key) {
        var binding = objects.current(candidate.fact().uuid().toString()).orElseThrow();
        objects.requireCurrentOwner(binding.objectId(), binding.objectEpoch(), directory.resolve(key).orElseThrow().region());
        dispatcher.requireCurrentOwner(key);
        if (!finishing.entityFacts.validTrackingCandidate(candidate))
            throw new IllegalStateException("chunk gameplay entity receipt changed");
    }
    private static boolean close(PlayerProjection player, ChunkPos chunk) {
        if (player.spectator) return false;
        double x = chunk.getMiddleBlockX() - player.position.x;
        double z = chunk.getMiddleBlockZ() - player.position.z;
        return x * x + z * z < 16384.0;
    }
    private RoutingKey key(FullEntityFacts.Candidate candidate) {
        return new RoutingKey(world, candidate.position().x(), candidate.position().z());
    }
    private void requireOwnerFree() {
        if (OwnershipContext.current().isPresent()) throw new IllegalStateException("chunk gameplay coordinator retained owner authority");
    }
    private Cycle requireCycle() { requireOwnerFree(); return Objects.requireNonNull(cycle, "no regional chunk gameplay cycle"); }
    private Cycle requireOwnedCycle() {
        Cycle active = Objects.requireNonNull(cycle, "no regional chunk gameplay cycle");
        RoutingKey callback = Objects.requireNonNull(active.callbackKey, "no active regional chunk callback");
        dispatcher.requireCurrentOwner(callback); return active;
    }
    public boolean active() { return cycle != null; }
    private void requireMap(ChunkMap expected) { if (expected != map) throw new IllegalStateException("chunk gameplay map changed"); }

    private static final class Cycle {
        final FullFinishingScoreboard.TrackingCut connectionCut;
        List<PlayerProjection> players;
        List<PlayerProjection> levelPlayers = List.of();
        PlayerProjection nearestPlayer;
        RoutingKey callbackKey;
        NaturalSpawner.SpawnState spawnState;
        Cycle(FullFinishingScoreboard.TrackingCut connectionCut, List<PlayerProjection> players) {
            this.connectionCut = connectionCut; this.players = players;
        }
    }
    private record PlayerCut(FullFinishingScoreboard.TrackingCut cut, List<PlayerProjection> players) { }
    private record PlayerProjection(ServerPlayer player, ServerGamePacketListenerImpl listener,
                                    FullEntityFacts.Candidate candidate, RoutingKey key, Vec3 position, boolean spectator) { }
    private record Contribution(BlockPos position, MobCategory category, double charge, boolean mob) { }
    private static final class RetainedLocalMobCaps extends LocalMobCapCalculator {
        private final ChunkMap map; private final java.util.function.Supplier<List<PlayerProjection>> players;
        private final IdentityHashMap<ServerPlayer, Object2IntOpenHashMap<MobCategory>> counts = new IdentityHashMap<>();
        private final Map<Long, List<PlayerProjection>> near = new HashMap<>();
        RetainedLocalMobCaps(ChunkMap map, java.util.function.Supplier<List<PlayerProjection>> players) {
            super(map); this.map = map; this.players = players;
        }
        @Override public void addMob(ChunkPos pos, MobCategory category) {
            for (PlayerProjection player : near(pos)) counts.computeIfAbsent(player.player,
                ignored -> new Object2IntOpenHashMap<>()).addTo(category, 1);
        }
        @Override public boolean canSpawn(MobCategory category, ChunkPos pos) {
            for (PlayerProjection player : near(pos)) {
                var values = counts.get(player.player);
                if (values == null || values.getOrDefault(category, 0) < category.getMaxInstancesPerChunk()) return true;
            }
            return false;
        }
        private List<PlayerProjection> near(ChunkPos pos) {
            return near.computeIfAbsent(pos.pack(), ignored -> {
                if (!map.getDistanceManager().hasPlayersNearby(pos.pack()).toBoolean(true)) return List.of();
                return players.get().stream().filter(player -> close(player, pos)).toList();
            });
        }
    }
}
