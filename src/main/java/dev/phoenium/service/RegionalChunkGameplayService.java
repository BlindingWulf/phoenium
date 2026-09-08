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
import dev.phoenium.mixin.RegionalSiegeSpawnerAccess;
import dev.phoenium.mixin.RegionalBlockEntityTickAccess;
import dev.phoenium.mixin.RegionalDragonFightAccess;
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
import net.minecraft.world.level.block.entity.TickingBlockEntity;
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
    private final IdentityHashMap<TickingBlockEntity, BlockEntityReceipt> blockEntities = new IdentityHashMap<>();
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
        var ticking = (RegionalBlockEntityTickAccess) level;
        for (TickingBlockEntity ticker : ticking.phoenium$blockEntityTickers()) bootstrapTicker(ticker);
        for (TickingBlockEntity ticker : ticking.phoenium$pendingBlockEntityTickers()) bootstrapTicker(ticker);
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
        var caps = new RetainedLocalMobCaps(map, () -> active.players);
        NaturalSpawner.SpawnState state = factory.create(count, List.of(), chunks, caps);
        var access = (RegionalSpawnStateAccess) (Object) state;
        Object2IntOpenHashMap<MobCategory> counts = access.phoenium$getMobCategoryCounts();
        PotentialCalculator potential = access.phoenium$getSpawnPotential();
        for (Entity originalEntity : originalEntities) {
            FullEntityFacts.Candidate candidate = finishing.entityFacts.trackingCandidate(level, originalEntity);
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

    /** The pinned built-in NaturalSpawner groups reach three chunk keys from their selected source.
     * Radius eight is admitted only when the exact retained rain/thunder prefix can reach the
     * pinned 128-block lightning-rod POI query. A callback with no spawning categories and no
     * thunder has only its selected chunk's inhabited-time mutation. */
    public void spawningChunkCallback(LevelChunk chunk, boolean canSpawn, Runnable original) {
        Cycle active = requireCycle();
        boolean raining = level.isRaining();
        boolean thundering = raining && level.isThundering();
        active.weather = new WeatherCut(raining, thundering);
        try { chunkCallback(chunk, thundering ? 8 : canSpawn ? 3 : 0, original); }
        finally { active.weather = null; }
    }
    public void blockTickCallback(LevelChunk chunk, Runnable original) { chunkCallback(chunk, 1, original); }
    public void broadcastChunkCallback(LevelChunk chunk, Runnable original) { chunkCallback(chunk, 0, original); }
    /** Custom spawners select terrain from current players or the original retained siege
     * origin. The widest player-relative path is the trader's meeting POI plus spawn search;
     * seven chunk keys cover it and the final 2x3x2 probe. */
    public void customSpawnerCallback(CustomSpawner spawner, boolean spawnEnemies, Runnable original) {
        requireCycle(); Objects.requireNonNull(original);
        int radius = customSpawnerRadius(spawner, spawnEnemies);
        if (radius < 0) { original.run(); return; }
        List<PlayerProjection> players = captureLevelPlayers(); cycle.levelPlayers = players;
        LinkedHashSet<RoutingKey> footprint = new LinkedHashSet<>();
        TopologyPlanner planner = new TopologyPlanner();
        for (PlayerProjection player : players) footprint.addAll(planner.requiredClosure(Set.of(player.key), radius));
        RegionalSiegeSpawnerAccess siege = spawner instanceof net.minecraft.world.entity.ai.village.VillageSiege
            ? (RegionalSiegeSpawnerAccess) spawner : null;
        boolean siegeSetup = siege != null && siege.phoenium$hasSetupSiege();
        int siegeX = siege == null ? 0 : siege.phoenium$spawnX();
        int siegeZ = siege == null ? 0 : siege.phoenium$spawnZ();
        if (siege != null) footprint.addAll(siegeFootprint(world, siegeSetup, siegeX, siegeZ));
        RoutingKey source = players.isEmpty() ? new RoutingKey(world, 0, 0) : players.get(0).key;
        if (footprint.isEmpty()) footprint.add(source);
        dispatcher.ensureClosure(Set.copyOf(footprint));
        dispatcher.executeAndDrain(source, RegionalServiceKind.CHUNK_GAMEPLAY, ignored -> {
            dispatcher.requireCurrentClosure(Set.copyOf(footprint));
            for (PlayerProjection player : players) requireEntity(player.candidate, player.key);
            if (siege != null && (siege.phoenium$hasSetupSiege() != siegeSetup
                    || siege.phoenium$spawnX() != siegeX || siege.phoenium$spawnZ() != siegeZ))
                throw new IllegalStateException("retained siege origin changed during regional admission");
            original.run();
        });
        chunkCallbacks = Math.incrementExact(chunkCallbacks);
    }
    static Set<RoutingKey> siegeFootprint(WorldId world, boolean setup, int x, int z) {
        LinkedHashSet<RoutingKey> footprint = new LinkedHashSet<>();
        // Pinned trySpawn reads difficulty at the new Zombie's initial position BEFORE
        // snapTo. Preserve that original origin read, even when the siege is elsewhere.
        footprint.add(new RoutingKey(world, 0, 0));
        // findRandomSpawnPos retains spawnX/Z across ticks and offsets each by [-8, 7].
        // Current player positions cannot replace the original stored spawn location.
        if (setup) footprint.addAll(new TopologyPlanner().requiredClosure(
            Set.of(new RoutingKey(world, Math.floorDiv(x, 16), Math.floorDiv(z, 16))), 1));
        return Set.copyOf(footprint);
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
            try {
                cycle.levelPlayers = refreshPlayerProjections(cycle.levelPlayers);
                original.run();
            }
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
        for (PlayerProjection retained : active.levelPlayers) {
            PlayerProjection player = refreshPlayerProjection(retained);
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
        selected = refreshPlayerProjection(selected); active.nearestPlayer = selected;
        return selected.position.distanceToSqr(x, y, z);
    }
    public boolean retainedWeather(boolean thunder) {
        Cycle active = requireOwnedCycle(); WeatherCut weather = Objects.requireNonNull(active.weather,
            "no retained spawning weather observation");
        return thunder ? weather.thundering : weather.raining;
    }

    public void entityTicks(EntityTickList entities, Consumer<Entity> output) {
        requireOwnerFree(); Objects.requireNonNull(entities); Objects.requireNonNull(output);
        FullFinishingServices.requireWorld(level, FullFinishingServices.Kind.ENTITY_REGISTRY);
        FullFinishingServices.requireWorld(level, FullFinishingServices.Kind.TRACKER);
        FullFinishingServices.requireServer(level.getServer(), FullFinishingServices.Kind.CONNECTIONS);
        entities.forEach(entity -> {
            FullEntityFacts.Candidate source = finishing.entityFacts.trackingCandidate(level, entity);
            List<FullEntityFacts.Candidate> passengers = finishing.entityFacts.trackingPassengers(source);
            FullEntityFacts.VehicleRelation vehicle = finishing.entityFacts.trackingVehicle(source);
            RoutingKey key = key(source); LinkedHashSet<RoutingKey> footprint = new LinkedHashSet<>();
            TopologyPlanner planner = new TopologyPlanner();
            footprint.addAll(planner.requiredClosure(Set.of(key), 1));
            passengers.forEach(passenger -> footprint.addAll(planner.requiredClosure(Set.of(key(passenger)), 1)));
            if (vehicle.vehicle() != null) footprint.addAll(planner.requiredClosure(Set.of(key(vehicle.vehicle())), 1));
            int entityRange = source.fact().type().clientTrackingRange() * 16;
            for (var passenger : passengers)
                entityRange = Math.max(entityRange, passenger.fact().type().clientTrackingRange() * 16);
            int entityRecipientRadius = (level.getServer().getScaledTrackingDistance(entityRange) + 15) / 16 + 2;
            PlayerCut currentPlayers = capturePlayers();
            for (PlayerProjection player : currentPlayers.players)
                if (Math.abs((long) player.key.x() - key.x()) <= entityRecipientRadius
                        && Math.abs((long) player.key.z() - key.z()) <= entityRecipientRadius) footprint.add(player.key);
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

    public void tickerAdded(TickingBlockEntity ticker) {
        FullFinishingServices.requireWorld(level, FullFinishingServices.Kind.TICK_LIST);
        Objects.requireNonNull(ticker, "ticker"); BlockPos position = ticker.getPos().immutable();
        ChunkPos chunk = ChunkPos.containing(position);
        RoutingKey key = new RoutingKey(world, chunk.x(), chunk.z());
        if (OwnershipContext.current().isPresent()) {
            dispatcher.requireCurrentOwner(key); blockEntities.put(ticker, new BlockEntityReceipt(position, key));
        } else dispatcher.executeAndDrain(key, RegionalServiceKind.BLOCK_ENTITY_TICK, ignored -> {
            dispatcher.requireCurrentOwner(key);
            if (!ticker.getPos().equals(position)) throw new IllegalStateException("new block entity ticker position changed");
            blockEntities.put(ticker, new BlockEntityReceipt(position, key));
        });
    }

    /** Replays Level.tickBlockEntities' exact mutable-list protocol and iterator order. Only each
     * selected ticker's original virtual observations and callback cross into its current owner. */
    public void tickBlockEntities() {
        requireOwnerFree();
        FullFinishingServices.requireWorld(level, FullFinishingServices.Kind.TICK_LIST);
        RegionalBlockEntityTickAccess access = (RegionalBlockEntityTickAccess) level;
        access.phoenium$tickingBlockEntities(true);
        List<TickingBlockEntity> pending = access.phoenium$pendingBlockEntityTickers();
        List<TickingBlockEntity> ticking = access.phoenium$blockEntityTickers();
        if (!pending.isEmpty()) { ticking.addAll(pending); pending.clear(); }
        Iterator<TickingBlockEntity> iterator = ticking.iterator();
        boolean runs = level.tickRateManager().runsNormally();
        while (iterator.hasNext()) {
            TickingBlockEntity ticker = iterator.next();
            BlockEntityReceipt receipt = blockEntities.get(ticker);
            if (receipt == null) throw new IllegalStateException("unpublished block entity ticker in original list");
            int[] outcome = {0};
            dispatcher.ensureClosure(receipt.key, 1);
            dispatcher.executeAndDrain(receipt.key, RegionalServiceKind.BLOCK_ENTITY_TICK, ignored -> {
                dispatcher.requireCurrentClosure(receipt.key, 1);
                if (tickTicker(ticker, runs, receipt.position, level::shouldTickBlocksAt)) outcome[0] = 1;
            });
            if (outcome[0] == 1) { iterator.remove(); blockEntities.remove(ticker); }
        }
        access.phoenium$tickingBlockEntities(false);
    }

    static boolean tickTicker(TickingBlockEntity ticker, boolean runs, BlockPos retainedPosition,
            java.util.function.Predicate<BlockPos> shouldTick) {
        if (ticker.isRemoved()) return true;
        if (runs) {
            if (!ticker.getPos().equals(retainedPosition))
                throw new IllegalStateException("block entity ticker position changed");
            if (shouldTick.test(retainedPosition)) ticker.tick();
        }
        return false;
    }

    /** EnderDragonFight's pinned arena reads are bounded to its origin +/-8 chunks and its
     * ticket uses radius nine. Current level players are exact alias recipients of updatePlayers,
     * so their owner keys join the cut without adding intervening terrain. */
    public void tickDragonFight(net.minecraft.world.level.dimension.end.EnderDragonFight fight) {
        requireOwnerFree(); Objects.requireNonNull(fight, "fight");
        FullFinishingServices.requireWorld(level, FullFinishingServices.Kind.ENTITY_REGISTRY);
        FullFinishingServices.requireServer(level.getServer(), FullFinishingServices.Kind.CONNECTIONS);
        BlockPos origin = ((RegionalDragonFightAccess) fight).phoenium$origin();
        ChunkPos chunk = ChunkPos.containing(origin);
        RoutingKey source = new RoutingKey(world, chunk.x(), chunk.z());
        LinkedHashSet<RoutingKey> footprint = new LinkedHashSet<>(
            new TopologyPlanner().requiredClosure(Set.of(source), 9));
        List<PlayerProjection> players = captureLevelPlayers();
        players.forEach(player -> footprint.add(player.key));
        dispatcher.ensureClosure(Set.copyOf(footprint));
        dispatcher.executeAndDrain(source, RegionalServiceKind.CHUNK_GAMEPLAY, ignored -> {
            dispatcher.requireCurrentClosure(Set.copyOf(footprint));
            for (PlayerProjection player : players) requireEntity(player.candidate, player.key);
            fight.tick();
        });
    }

    private void bootstrapTicker(TickingBlockEntity ticker) {
        BlockPos position = ticker.getPos().immutable();
        blockEntities.put(ticker, new BlockEntityReceipt(position,
            new RoutingKey(world, ChunkPos.getX(ChunkPos.pack(position)), ChunkPos.getZ(ChunkPos.pack(position)))));
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
    private List<PlayerProjection> refreshPlayerProjections(List<PlayerProjection> retained) {
        List<PlayerProjection> refreshed = new ArrayList<>(retained.size());
        retained.forEach(player -> refreshed.add(refreshPlayerProjection(player)));
        return List.copyOf(refreshed);
    }
    private PlayerProjection refreshPlayerProjection(PlayerProjection retained) {
        FullEntityFacts.PublishedPosition published = finishing.entityFacts.publishedPosition(retained.player);
        if (published.candidate().fact() != retained.candidate.fact())
            throw new IllegalStateException("natural spawn player identity changed after owner preflight");
        FullEntityFacts.Candidate candidate = published.candidate();
        if (candidate.fact().world() != level)
            throw new IllegalStateException("natural spawn player changed world after owner preflight");
        return new PlayerProjection(retained.player, retained.listener, candidate, key(candidate),
            published.position(), candidate.fact().spectator());
    }
    private void requireEntity(FullEntityFacts.Candidate candidate, RoutingKey key) {
        String objectId = candidate.fact().uuid().toString();
        var binding = objects.current(objectId).orElseThrow(() -> new IllegalStateException(
            "spawn-state candidate lacks regional lifecycle ownership id=" + objectId
                + " runtimeId=" + candidate.fact().runtimeId() + " type=" + candidate.fact().type()
                + " route=" + key.x() + "," + key.z()));
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
    public boolean activeWeather() { return cycle != null && cycle.weather != null; }
    private void requireMap(ChunkMap expected) { if (expected != map) throw new IllegalStateException("chunk gameplay map changed"); }

    private static final class Cycle {
        final FullFinishingScoreboard.TrackingCut connectionCut;
        List<PlayerProjection> players;
        List<PlayerProjection> levelPlayers = List.of();
        PlayerProjection nearestPlayer;
        RoutingKey callbackKey;
        WeatherCut weather;
        NaturalSpawner.SpawnState spawnState;
        Cycle(FullFinishingScoreboard.TrackingCut connectionCut, List<PlayerProjection> players) {
            this.connectionCut = connectionCut; this.players = players;
        }
    }
    private record PlayerCut(FullFinishingScoreboard.TrackingCut cut, List<PlayerProjection> players) { }
    private record PlayerProjection(ServerPlayer player, ServerGamePacketListenerImpl listener,
                                    FullEntityFacts.Candidate candidate, RoutingKey key, Vec3 position, boolean spectator) { }
    private record Contribution(BlockPos position, MobCategory category, double charge, boolean mob) { }
    private record BlockEntityReceipt(BlockPos position, RoutingKey key) { }
    private record WeatherCut(boolean raining, boolean thundering) { }
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
