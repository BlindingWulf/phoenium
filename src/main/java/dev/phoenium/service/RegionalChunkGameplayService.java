package dev.phoenium.service;

import dev.phoenium.chunk.FullEntityFacts;
import dev.phoenium.chunk.FullFinishingScoreboard;
import dev.phoenium.chunk.FullFinishingServices;
import dev.phoenium.chunk.ResidentChunkSourceBinding;
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
import net.minecraft.util.Mth;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.*;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.entity.EntityTickList;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.levelgen.feature.EndPodiumFeature;
import net.minecraft.world.phys.Vec3;

/** Owner-free coordinator for ServerChunkCache's exact selected gameplay cohorts. Temporary spawn
 * state follows original order; each chunk callback executes synchronously under its current owner. */
public final class RegionalChunkGameplayService implements NaturalSpawnContinuation.PlayerQueries {
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
    private long naturalSpawnOrdinal;
    private long vaultTickOrdinal;
    private final java.util.concurrent.atomic.AtomicBoolean vaultBindingFailureReported = new java.util.concurrent.atomic.AtomicBoolean();
    private final dev.phoenium.chunk.BlockEntityChunkQueryDiagnostic blockEntityQueries =
            new dev.phoenium.chunk.BlockEntityChunkQueryDiagnostic();

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
        // The distance manager's level map is a fastutil open-hash map: a callback that adds or removes a ticket (a
        // portal arrival, a forceload, a player joining) while it is being iterated corrupts the iterator and fails
        // with a NullPointerException inside next(). Select the keys first, then run the callbacks over the copy.
        var keys = new it.unimi.dsi.fastutil.longs.LongArrayList();
        map.getDistanceManager().forEachEntityTickingChunk(keys::add);
        for (int i = 0; i < keys.size(); i++) {
            ChunkHolder holder = ((RegionalVisibleChunkAccess) map).phoenium$visible(keys.getLong(i));
            if (holder == null) continue;
            LevelChunk chunk = holder.getTickingChunk();
            if (chunk != null) blockTickCallback(chunk, () -> output.accept(chunk));
        }
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
        // The pinned bright-day branch resets only this named spawner's own state and
        // cannot reach terrain, players, or the newly constructed zombie's origin read.
        if (spawner instanceof net.minecraft.world.entity.ai.village.VillageSiege && level.isBrightOutside()) {
            original.run(); return;
        }
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
        int visibleRecipientRadius = Math.min(recipientRadius,
            ((RegionalChunkMapAccess) map).phoenium$getServerViewDistance() + 2);
        for (PlayerProjection player : cycle.players)
            if (Math.abs((long) player.key.x() - source.x()) <= visibleRecipientRadius
                    && Math.abs((long) player.key.z() - source.z()) <= visibleRecipientRadius) footprint.add(player.key);
        dispatcher.ensureClosure(Set.copyOf(footprint));
        Cycle capturedCycle = cycle;
        Consumer<Runnable> owned = body -> {
            dispatcher.requireCurrentOwner(source); capturedCycle.callbackKey = source;
            try {
                capturedCycle.levelPlayers = refreshPlayerProjections(capturedCycle.levelPlayers);
                body.run();
            } finally { capturedCycle.callbackKey = null; capturedCycle.nearestPlayer = null; }
        };
        try (var spawn = new NaturalSpawnContinuation.Capture(this, dispatcher, level, chunk, source,
                naturalSpawnOrdinal = Math.incrementExact(naturalSpawnOrdinal),
                () -> cycle == capturedCycle && naturalSpawnClosureCurrent(source, footprint))) {
            dispatcher.executeAndDrain(source, RegionalServiceKind.CHUNK_GAMEPLAY, ignored -> owned.accept(() -> spawn.original(original)));
            spawn.finish(body -> { dispatcher.requireCurrentClosure(Set.copyOf(footprint)); owned.accept(body); });
        }
        chunkCallbacks = Math.incrementExact(chunkCallbacks);
    }

    private boolean naturalSpawnClosureCurrent(RoutingKey source, Set<RoutingKey> footprint) {
        var owner = directory.resolve(source).orElse(null);
        if (owner == null) return false;
        for (var key : footprint) {
            var route = directory.resolve(key).orElse(null);
            if (route == null || !route.region().equals(owner.region()) || !route.generation().equals(owner.generation())
                    || directory.pendingChange(key).filter(change -> !change.completed()).isPresent()) return false;
        }
        return true;
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
        forEachLiveEntityTickAlias(entities, entity -> {
            // A dragon lazily reads the native 24-node heightmap ring. Selection can change
            // while the owner-free FULL inputs settle, so repeat its preflight before any
            // native body or RNG draw. A persistently moving cut waits for the next tick.
            int maximumAttempts = entity instanceof EnderDragon ? 3 : 1;
            for (int attempt = 0; attempt < maximumAttempts; attempt++) {
                if (attempt > 0 && entity.isRemoved()) break;
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
                int entityRecipientRadius = Math.min((level.getServer().getScaledTrackingDistance(entityRange) + 15) / 16 + 2,
                    ((RegionalChunkMapAccess) map).phoenium$getServerViewDistance() + 2);
                PlayerCut currentPlayers = capturePlayers();
                for (PlayerProjection player : currentPlayers.players)
                    if (Math.abs((long) player.key.x() - key.x()) <= entityRecipientRadius
                            && Math.abs((long) player.key.z() - key.z()) <= entityRecipientRadius) footprint.add(player.key);
                BlockPos fightOrigin = entity instanceof EnderDragon dragon ? dragon.getFightOrigin().immutable() : null;
                List<ChunkPos> dragonInputs = fightOrigin == null ? List.of() : dragonHeightmapInputChunks(fightOrigin);
                for (ChunkPos input : dragonInputs) footprint.add(new RoutingKey(world, input.x(), input.z()));
                Set<RoutingKey> closure = Set.copyOf(footprint);
                dispatcher.ensureClosure(closure);
                DragonHeightmapCut dragonCut = fightOrigin == null ? null : prepareDragonHeightmapInputs(dragonInputs);
                if (fightOrigin != null && dragonCut == null) continue;
                boolean[] stale = {false};
                dispatcher.executeAndDrain(key, RegionalServiceKind.CHUNK_GAMEPLAY, ignored -> {
                    if (removedBeforeOwnerTurn(entity)) return;
                    if (dragonCut != null && (!((EnderDragon) entity).getFightOrigin().equals(fightOrigin)
                            || !dragonInputRoutesCurrent(key, closure) || !dragonCut.current())) {
                        stale[0] = true;
                        return;
                    }
                    requireEntity(source, key);
                    if (!finishing.entityFacts.validTrackingPassengers(source, passengers))
                        throw new IllegalStateException("entity tick passenger relation changed");
                    if (!finishing.entityFacts.validTrackingVehicle(vehicle))
                        throw new IllegalStateException("entity tick vehicle relation changed");
                    output.accept(entity);
                });
                if (!stale[0]) break;
            }
            // The enclosing native owner callback has returned. Finish this finite
            // portal operation before the next entity tick/RNG draw in native order.
            dev.phoenium.Phoenium.liveRuntime().advanceMobPortalAfterEntityCallback();
        });
    }

    /** Pinned 26.2 EnderDragon.findClosestNode initializes 12 radius-60, 8 radius-40,
     * and 4 radius-20 heightmap nodes around absolute zero. Dragon phases also query
     * EndPodiumFeature.getLocation(fightOrigin). The list is fixed before admission. */
    static List<ChunkPos> dragonHeightmapInputChunks(BlockPos fightOrigin) {
        var inputs = new LinkedHashSet<ChunkPos>();
        for (int i = 0; i < 24; i++) {
            int multiplier = i;
            float radius, denominator;
            if (i < 12) { radius = 60.0F; denominator = 12.0F; }
            else if (i < 20) { multiplier -= 12; radius = 40.0F; denominator = 8.0F; }
            else { multiplier -= 20; radius = 20.0F; denominator = 4.0F; }
            float angle = 2.0F * ((float) -Math.PI + (float) (Math.PI / denominator) * multiplier);
            int nodeX = Mth.floor(radius * Mth.cos(angle));
            int nodeZ = Mth.floor(radius * Mth.sin(angle));
            inputs.add(ChunkPos.containing(new BlockPos(nodeX, 0, nodeZ)));
        }
        inputs.add(ChunkPos.containing(EndPodiumFeature.getLocation(fightOrigin)));
        return List.copyOf(inputs);
    }

    /** Level.getHeight first tests ServerChunkCache.hasChunk. Preserve its absent-chunk
     * fallback instead of force-loading all possible node cells. */
    private DragonHeightmapCut prepareDragonHeightmapInputs(List<ChunkPos> inputs) {
        var selected = new LinkedHashMap<ChunkPos, LevelChunk>();
        var residents = new LinkedHashMap<ChunkPos, ResidentChunkSourceBinding>();
        for (ChunkPos input : inputs) {
            LevelChunk ready = null;
            if (cache.hasChunk(input.x(), input.z())) {
                var loaded = cache.getChunk(input.x(), input.z(), ChunkStatus.FULL, true);
                if (!(loaded instanceof LevelChunk full)) return null;
                ready = full;
                var resident = ResidentChunkSourceBinding.capture(level, input.pack());
                if (resident == null || resident.chunk() != ready) return null;
                residents.put(input, resident);
            }
            selected.put(input, ready);
        }
        return new DragonHeightmapCut(selected, residents);
    }

    private boolean dragonInputRoutesCurrent(RoutingKey source, Set<RoutingKey> closure) {
        var active = OwnershipContext.current().orElseThrow();
        var route = directory.resolve(source).orElse(null);
        if (route == null || !route.region().equals(active.region())) return false;
        for (RoutingKey key : closure) {
            var current = directory.resolve(key).orElse(null);
            if (current == null || !current.region().equals(route.region())
                    || !current.generation().equals(route.generation())
                    || !current.ownerEpoch().equals(route.ownerEpoch())) return false;
        }
        return true;
    }

    static <T> boolean dragonHeightmapSelectionsCurrent(Map<ChunkPos, T> selected,
            java.util.function.Predicate<ChunkPos> hasChunk,
            java.util.function.Function<ChunkPos, T> currentChunk) {
        for (var input : selected.entrySet()) {
            boolean present = input.getValue() != null;
            if (hasChunk.test(input.getKey()) != present) return false;
            if (present && currentChunk.apply(input.getKey()) != input.getValue()) return false;
        }
        return true;
    }

    private final class DragonHeightmapCut {
        private final Map<ChunkPos, LevelChunk> selected;
        private final Map<ChunkPos, ResidentChunkSourceBinding> residents;
        DragonHeightmapCut(Map<ChunkPos, LevelChunk> selected,
                           Map<ChunkPos, ResidentChunkSourceBinding> residents) {
            this.selected = Collections.unmodifiableMap(new LinkedHashMap<>(selected));
            this.residents = Map.copyOf(residents);
        }
        boolean current() {
            if (!dragonHeightmapSelectionsCurrent(selected,
                    input -> cache.hasChunk(input.x(), input.z()),
                    input -> cache.getChunkNow(input.x(), input.z()))) return false;
            for (var resident : residents.values()) if (!resident.current()) return false;
            for (ChunkPos input : selected.keySet())
                dispatcher.requireCurrentOwner(new RoutingKey(world, input.x(), input.z()));
            return true;
        }
    }

    /** EntityTickList keeps iterating its old snapshot when an entity is removed during that
     * iteration. ServerLevel's native consumer checks isRemoved() before any other entity work;
     * preserve that boundary before requiring current owner-published tracking facts. */
    static void forEachLiveEntityTickAlias(EntityTickList entities, Consumer<Entity> output) {
        entities.forEach(entity -> {
            if (!entity.isRemoved()) output.accept(entity);
        });
    }

    /** The same native boundary at the owner turn. This entity's own drain can first deliver older
     * owner work that removes it (a projectile or pearl cap discard queued by an earlier entity's
     * tick). Vanilla would already have removed it before reaching it, so skip instead of failing
     * the retained receipt. */
    static boolean removedBeforeOwnerTurn(Entity entity) {
        return entity.isRemoved();
    }

    public void tickerAdded(TickingBlockEntity ticker) {
        FullFinishingServices.requireWorld(level, FullFinishingServices.Kind.TICK_LIST);
        Objects.requireNonNull(ticker, "ticker"); BlockPos position = ticker.getPos().immutable();
        ChunkPos chunk = ChunkPos.containing(position);
        RoutingKey key = new RoutingKey(world, chunk.x(), chunk.z());
        if (OwnershipContext.current().isPresent()) {
            dispatcher.requireCurrentOwner(key); blockEntities.put(ticker, new BlockEntityReceipt(position, key, new ResidentBlockEntityTickerBinding(level, ticker, directory, key)));
        } else dispatcher.executeAndDrain(key, RegionalServiceKind.BLOCK_ENTITY_TICK, ignored -> {
            dispatcher.requireCurrentOwner(key);
            if (!ticker.getPos().equals(position)) throw new IllegalStateException("new block entity ticker position changed");
            blockEntities.put(ticker, new BlockEntityReceipt(position, key, new ResidentBlockEntityTickerBinding(level, ticker, directory, key)));
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
        ListIterator<TickingBlockEntity> iterator = ticking.listIterator();
        boolean runs = level.tickRateManager().runsNormally();
        int capacity = dispatcher.workerConfig().workers() * dispatcher.workerConfig().callbacksPerRegion();
        while (iterator.hasNext()) {
            List<TickingBlockEntity> window = new ArrayList<>(capacity);
            LinkedHashSet<RoutingKey> sources = new LinkedHashSet<>();
            while (iterator.hasNext() && window.size() < capacity) {
                TickingBlockEntity ticker = iterator.next();
                BlockEntityReceipt receipt = blockEntities.get(ticker);
                if (receipt == null) throw new IllegalStateException("unpublished block entity ticker in original list");
                if (!receipt.binding.ready()) {
                    if (!window.isEmpty()) { iterator.previous(); break; }
                    if (receipt.binding.state() == ResidentBlockEntityTickerBinding.State.RETIRED) {
                        iterator.remove(); blockEntities.remove(ticker);
                    }
                    continue;
                }
                window.add(ticker); sources.add(receipt.key);
            }
            sources.forEach(source -> dispatcher.ensureClosure(source, 1));
            var selections = runs ? selectLocalWindow(window) : new IdentityHashMap<TickingBlockEntity, LocalBlockEntityTick.Selection>();
            for (int index = 0; index < window.size(); index++) iterator.previous();
            int offset = 0;
            while (offset < window.size()) {
                int end = offset + 1;
                if (selections.containsKey(window.get(offset))) {
                    Map<dev.phoenium.region.RegionId, Integer> counts = new HashMap<>();
                    counts.put(directory.resolve(blockEntities.get(window.get(offset)).key).orElseThrow().region(), 1);
                    while (end < window.size() && selections.containsKey(window.get(end))) {
                        var owner = directory.resolve(blockEntities.get(window.get(end)).key).orElseThrow().region();
                        if (counts.getOrDefault(owner, 0) == dispatcher.workerConfig().callbacksPerRegion()
                                || !counts.containsKey(owner) && counts.size() == dispatcher.workerConfig().workers()) break;
                        counts.merge(owner, 1, Integer::sum); end++;
                    }
                }
                List<TickingBlockEntity> segment = window.subList(offset, end);
                if (selections.containsKey(window.get(offset)) && tryLocalSegment(segment, runs)) {
                    for (TickingBlockEntity expected : segment) {
                        if (iterator.next() != expected) throw new IllegalStateException("original ticker iterator changed during preflight");
                    }
                } else for (TickingBlockEntity expected : segment) {
                    TickingBlockEntity ticker = iterator.next();
                    if (ticker != expected) throw new IllegalStateException("original ticker iterator changed during preflight");
                    BlockEntityReceipt receipt = blockEntities.get(ticker); boolean[] removed = {false};
                    if (!receipt.binding.activate()) {
                        if (receipt.binding.state() == ResidentBlockEntityTickerBinding.State.RETIRED) {
                            iterator.remove(); blockEntities.remove(ticker);
                        }
                        continue;
                    }
                    try (var vaultTick = new VaultBlockEntityTickContinuation(level, ticker, directory, receipt.key,
                            vaultTickOrdinal = Math.incrementExact(vaultTickOrdinal),
                            () -> dispatcher.naturalSpawnOpen() && receipt.binding.source() != null
                                && receipt.binding.source().providerRuntimeOpen(),
                            dispatcher::requireNaturalSpawnOwnerFree).sourceBinding(receipt.binding.source())) {
                        vaultTick.bindingFailure(observation -> {
                            if (vaultBindingFailureReported.compareAndSet(false, true))
                                org.slf4j.LoggerFactory.getLogger("Phoenium").error(
                                    "PHOENIUM_VAULT_FIRST_TICK_BINDING_FAILURE dispatcherOpen={} {}",
                                    dispatcher.naturalSpawnOpen(), observation.get());
                        });
                        dispatcher.executeAndDrain(receipt.key, RegionalServiceKind.BLOCK_ENTITY_TICK, execution -> {
                            dispatcher.requireCurrentClosure(receipt.key, 1);
                            try (var diagnostic = blockEntityQueries.enter(level, ticker.getType(), receipt.position,
                                    execution, directory.snapshot().generation())) {
                                if (!receipt.binding.ready()) { removed[0] = true; return; }
                                removed[0] = tickTicker(ticker, runs, receipt.position, level::shouldTickBlocksAt, vaultTick::original);
                            }
                        });
                        vaultTick.finish(dispatcher, (execution, body) -> {
                            dispatcher.requireCurrentClosure(receipt.key, 1);
                            // The retained operation identity remains the original tick ordinal;
                            // each actual resume has a fresh current BLOCK_ENTITY_TICK claim.
                            try (var diagnostic = blockEntityQueries.enter(level, ticker.getType(), receipt.position,
                                    execution, directory.snapshot().generation())) {
                                body.run();
                            }
                        });
                    }
                    if (removed[0]) { receipt.binding.close(); iterator.remove(); blockEntities.remove(ticker); }
                }
                offset = end;
            }
        }
        access.phoenium$tickingBlockEntities(false);
    }

    private IdentityHashMap<TickingBlockEntity, LocalBlockEntityTick.Selection> selectLocalWindow(List<TickingBlockEntity> window) {
        var result = new IdentityHashMap<TickingBlockEntity, LocalBlockEntityTick.Selection>();
        var groups = new LinkedHashMap<dev.phoenium.region.RegionId, List<TickingBlockEntity>>();
        for (TickingBlockEntity ticker : window) groups.computeIfAbsent(
            directory.resolve(blockEntities.get(ticker).key).orElseThrow().region(), ignored -> new ArrayList<>()).add(ticker);
        for (List<TickingBlockEntity> group : groups.values()) dispatcher.executeAndDrain(
            blockEntities.get(group.getFirst()).key, RegionalServiceKind.BLOCK_ENTITY_TICK, ignored -> {
                for (TickingBlockEntity ticker : group) {
                    dispatcher.requireCurrentClosure(blockEntities.get(ticker).key, 1);
                    if (!blockEntities.get(ticker).binding.ready()) continue;
                    LocalBlockEntityTick.Selection selection = LocalBlockEntityTick.select(ticker);
                    if (selection != null) result.put(ticker, selection);
                }
            });
        return result;
    }

    private boolean tryLocalSegment(List<TickingBlockEntity> segment, boolean runs) {
        var groups = new LinkedHashMap<dev.phoenium.region.RegionId, List<TickingBlockEntity>>();
        for (TickingBlockEntity ticker : segment) groups.computeIfAbsent(
            directory.resolve(blockEntities.get(ticker).key).orElseThrow().region(), ignored -> new ArrayList<>()).add(ticker);
        if (groups.size() > dispatcher.workerConfig().workers()
                || groups.values().stream().anyMatch(group -> group.size() > dispatcher.workerConfig().callbacksPerRegion())) return false;
        List<RegionalServiceDispatcher.LocalWork> cohort = new ArrayList<>();
        for (List<TickingBlockEntity> group : groups.values()) {
            List<LocalTicker> prepared = new ArrayList<>(group.size());
            cohort.add(new RegionalServiceDispatcher.LocalWork(blockEntities.get(group.getFirst()).key, ignored -> {
                prepared.clear();
                for (TickingBlockEntity ticker : group) {
                    BlockEntityReceipt receipt = blockEntities.get(ticker);
                    dispatcher.requireCurrentClosure(receipt.key, 1);
                    if (!receipt.binding.activate()) return false;
                    var selection = LocalBlockEntityTick.select(ticker);
                    if (selection == null) return false;
                    prepared.add(new LocalTicker(receipt, selection));
                }
                return true;
            }, ignored -> {
                    for (LocalTicker retained : prepared) {
                        dispatcher.requireHealthyLocalContinuation();
                        dispatcher.requireCurrentClosure(retained.receipt.key, 1);
                        if (!retained.receipt.binding.ready()) throw new IllegalStateException("resident ticker source retired after selection");
                        retained.selection.requireCurrent();
                        TickingBlockEntity original = retained.selection.ticker();
                        if (original.isRemoved()) throw new IllegalStateException("local ticker retired after selection");
                        if (!original.getPos().equals(retained.receipt.position)) throw new IllegalStateException("local ticker moved after selection");
                        if (runs && level.shouldTickBlocksAt(retained.receipt.position)) {
                            String identity = world.value() + ":" + retained.receipt.position.toShortString() + ":" + level.getGameTime();
                            dispatcher.measurements().originalBody(identity, retained.selection::tickOriginal);
                        }
                    }
            }));
        }
        // The scheduler retains every participating claim across this final native
        // preflight and all original bodies. A declined segment has committed no body.
        return dispatcher.executePreparedLocalBatch(cohort);
    }
    private record LocalTicker(BlockEntityReceipt receipt, LocalBlockEntityTick.Selection selection) { }

    static boolean tickTicker(TickingBlockEntity ticker, boolean runs, BlockPos retainedPosition,
            java.util.function.Predicate<BlockPos> shouldTick) {
        return tickTicker(ticker, runs, retainedPosition, shouldTick, Runnable::run);
    }
    static boolean tickTicker(TickingBlockEntity ticker, boolean runs, BlockPos retainedPosition,
            java.util.function.Predicate<BlockPos> shouldTick, Consumer<Runnable> tickBody) {
        if (ticker.isRemoved()) return true;
        if (runs) {
            if (LithiumSleepingTickerBridge.isSleepingSentinel(ticker)) return false;
            BlockPos currentPosition = ticker.getPos();
            if (currentPosition == null)
                throw new IllegalStateException("block entity ticker returned null position");
            if (!currentPosition.equals(retainedPosition))
                throw new IllegalStateException("block entity ticker position changed");
            if (shouldTick.test(retainedPosition)) tickBody.accept(ticker::tick);
        }
        return false;
    }

    /** EnderDragonFight's arena and portal-pattern reads fit its origin +/-9 chunks. The
     * initial legacy scan also visits absolute chunk coordinates -8..8. Current level players
     * are exact alias recipients of updatePlayers, so their owner keys join the cut. */
    public void tickDragonFight(net.minecraft.world.level.dimension.end.EnderDragonFight fight) {
        requireOwnerFree(); Objects.requireNonNull(fight, "fight");
        FullFinishingServices.requireWorld(level, FullFinishingServices.Kind.ENTITY_REGISTRY);
        FullFinishingServices.requireServer(level.getServer(), FullFinishingServices.Kind.CONNECTIONS);
        RegionalDragonFightAccess access = (RegionalDragonFightAccess) fight;
        BlockPos origin = access.phoenium$origin();
        ChunkPos chunk = ChunkPos.containing(origin);
        RoutingKey source = new RoutingKey(world, chunk.x(), chunk.z());
        LinkedHashSet<RoutingKey> footprint = new LinkedHashSet<>(
            new TopologyPlanner().requiredClosure(Set.of(source), 9));
        List<PlayerProjection> players = captureLevelPlayers();
        players.forEach(player -> footprint.add(player.key));
        // In 26.2 isArenaLoaded reads FULL at x = origin +/-8 and z = origin +8
        // (the mapped loop's z lower and upper bounds are both +8). A DRAGON
        // ticket is installed by tick() immediately before those reads. When a
        // player first reaches the End, the ticket's FULL futures can still be
        // incomplete. Resolve their public inputs before admitting the native
        // fight body, then retain their exact resident publications for the cut.
        var arenaInputs = new java.util.LinkedHashMap<RoutingKey, java.util.function.BooleanSupplier>();
        if (!players.isEmpty() || !access.phoenium$dragonEvent().getPlayers().isEmpty()) {
            // The first scan runs only while needsStateScanning is true. Its native
            // hasActiveExitPortal and findExitPortal methods can synchronously request
            // every FULL cell in the center, plus one pattern-border chunk. Finish
            // those inputs without an owner claim before entering the native tick.
            for (ChunkPos input : dragonFightFullInputs(chunk, access.phoenium$needsStateScanning())) {
                int x = input.x(), z = input.z();
                var ready = level.getChunkSource().getChunk(x, z,
                    net.minecraft.world.level.chunk.status.ChunkStatus.FULL, true);
                if (ready == null) throw new IllegalStateException("dragon arena FULL input absent");
                long position = input.pack();
                var resident = dev.phoenium.chunk.ResidentChunkSourceBinding.capture(level, position);
                if (resident == null || resident.chunk() != ready)
                    throw new IllegalStateException("dragon arena FULL input lacks resident publication");
                final int chunkX = x, chunkZ = z;
                var key = new RoutingKey(world, x, z);
                arenaInputs.put(key, () -> resident.current()
                    && level.getChunkSource().getChunkNow(chunkX, chunkZ) == ready);
                footprint.add(key);
            }
        }
        dispatcher.ensureClosure(Set.copyOf(footprint));
        dispatcher.executeAndDrain(source, RegionalServiceKind.CHUNK_GAMEPLAY, ignored -> {
            dispatcher.requireCurrentClosure(Set.copyOf(footprint));
            for (var input : arenaInputs.entrySet()) {
                dispatcher.requireCurrentOwner(input.getKey());
                if (!input.getValue().getAsBoolean())
                    throw new IllegalStateException("dragon arena resident input changed: " + input.getKey());
            }
            for (PlayerProjection player : players) requireEntity(player.candidate, player.key);
            fight.tick();
        });
    }

    /** Exact owner-free FULL inputs for the pinned 26.2 fight paths. The ordinary arena
     * check reads its z=origin+8 row; a pending scan can reach the full origin halo and
     * the legacy portal search, which is fixed at absolute chunk coordinates -8..8. */
    static List<ChunkPos> dragonFightFullInputs(ChunkPos origin, boolean pendingScan) {
        var inputs = new LinkedHashSet<ChunkPos>();
        if (pendingScan) {
            for (int x = origin.x() - 9; x <= origin.x() + 9; x++)
                for (int z = origin.z() - 9; z <= origin.z() + 9; z++)
                    inputs.add(new ChunkPos(x, z));
            for (int x = -8; x <= 8; x++)
                for (int z = -8; z <= 8; z++)
                    inputs.add(new ChunkPos(x, z));
        } else {
            for (int x = origin.x() - 8; x <= origin.x() + 8; x++)
                inputs.add(new ChunkPos(x, origin.z() + 8));
        }
        return List.copyOf(inputs);
    }

    private void bootstrapTicker(TickingBlockEntity ticker) {
        BlockPos position = ticker.getPos().immutable();
        var key = new RoutingKey(world, ChunkPos.getX(ChunkPos.pack(position)), ChunkPos.getZ(ChunkPos.pack(position)));
        blockEntities.put(ticker, new BlockEntityReceipt(position, key, new ResidentBlockEntityTickerBinding(level, ticker, directory, key)));
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
    private record BlockEntityReceipt(BlockPos position, RoutingKey key, ResidentBlockEntityTickerBinding binding) { }
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
