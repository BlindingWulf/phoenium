package dev.phoenium.service;

import dev.phoenium.chunk.FullEntityFacts;
import dev.phoenium.chunk.FullFinishingScoreboard;
import dev.phoenium.chunk.FullFinishingServices;
import dev.phoenium.core.WorldId;
import dev.phoenium.mixin.RegionalChunkMapAccess;
import dev.phoenium.mixin.RegionalServerChunkCacheAccess;
import dev.phoenium.mixin.RegionalSpawnStateAccess;
import dev.phoenium.mixin.RegionalVisibleChunkAccess;
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
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.chunk.LevelChunk;
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
    private final int callbackRadius;
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
        this.callbackRadius = Math.max(9, (tracking + 15) / 16 + 2);
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
            if (chunk != null) chunkCallback(chunk, () -> output.accept(chunk));
        });
    }

    public void chunkCallback(LevelChunk chunk, Runnable original) {
        requireCycle(); Objects.requireNonNull(chunk); Objects.requireNonNull(original);
        cycle.players = capturePlayers().players;
        RoutingKey source = new RoutingKey(world, chunk.getPos().x(), chunk.getPos().z());
        Set<RoutingKey> footprint = new LinkedHashSet<>(new TopologyPlanner().requiredClosure(Set.of(source), callbackRadius));
        for (PlayerProjection player : cycle.players)
            if (Math.abs((long) player.key.x() - source.x()) <= callbackRadius
                    && Math.abs((long) player.key.z() - source.z()) <= callbackRadius) footprint.add(player.key);
        dispatcher.ensureClosure(Set.copyOf(footprint));
        dispatcher.executeAndDrain(source, RegionalServiceKind.CHUNK_GAMEPLAY, ignored -> {
            dispatcher.requireCurrentOwner(source); original.run();
        });
        chunkCallbacks = Math.incrementExact(chunkCallbacks);
    }

    private PlayerCut capturePlayers() {
        List<ServerPlayer> order = List.copyOf(((RegionalChunkMapAccess) map).phoenium$getPlayerMap().getAllPlayers());
        FullFinishingScoreboard.TrackingCut cut = finishing.scoreboard.trackingCut(order);
        Map<ServerPlayer, FullFinishingScoreboard.TrackingSession> sessions = new IdentityHashMap<>();
        cut.sessions().forEach(session -> sessions.put(session.player(), session));
        List<PlayerProjection> result = new ArrayList<>(order.size());
        for (ServerPlayer player : order) {
            var session = Objects.requireNonNull(sessions.get(player));
            var candidate = finishing.entityFacts.trackingCandidate(level, player); RoutingKey key = key(candidate);
            PlayerProjection[] captured = new PlayerProjection[1];
            dispatcher.executeAndDrain(key, RegionalServiceKind.TRACKING_UPDATE, ignored -> {
                requireEntity(candidate, key); finishing.scoreboard.requireTrackingSession(cut, session);
                captured[0] = new PlayerProjection(player, session.listener(), candidate, key,
                    player.position(), player.isSpectator());
            });
            result.add(Objects.requireNonNull(captured[0]));
        }
        return new PlayerCut(cut, List.copyOf(result));
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
    private void requireMap(ChunkMap expected) { if (expected != map) throw new IllegalStateException("chunk gameplay map changed"); }

    private static final class Cycle {
        final FullFinishingScoreboard.TrackingCut connectionCut;
        List<PlayerProjection> players; NaturalSpawner.SpawnState spawnState;
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
            if (!map.getDistanceManager().hasPlayersNearby(pos.pack()).toBoolean(true)) return List.of();
            return near.computeIfAbsent(pos.pack(), ignored -> players.get().stream()
                .filter(player -> close(player, pos)).toList());
        }
    }
}
