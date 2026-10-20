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
    private long cycles, entityContributions, chunkCallbacks, spawnerTerrainNotReady;
    private long naturalSpawnOrdinal;
    private long vaultTickOrdinal;
    private final java.util.concurrent.atomic.AtomicBoolean vaultBindingFailureReported = new java.util.concurrent.atomic.AtomicBoolean();
    private final dev.phoenium.chunk.BlockEntityChunkQueryDiagnostic blockEntityQueries =
            new dev.phoenium.chunk.BlockEntityChunkQueryDiagnostic();
    /** S9-D18: a block-entity tick's diagnostic scope, or none (a null resource) while the diagnostic is off
     * (DiagnosticSwitches); with no scope open the chunk queries take their idle path, as outside any tick. */
    private dev.phoenium.chunk.BlockEntityChunkQueryDiagnostic.Scope blockEntityScope(
            net.minecraft.world.level.block.entity.TickingBlockEntity ticker, net.minecraft.core.BlockPos position,
            RegionalServiceDispatcher.Execution execution) {
        if (!dev.phoenium.core.DiagnosticSwitches.BLOCK_ENTITY_CHUNK_QUERIES) return null;
        return blockEntityQueries.enter(level, ticker::getType, position, execution, directory.snapshot().generation());
    }

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
        // S9-D11: vanilla reads every entity in one pass, so the entities are selected first; each one's capture turn
        // goes through executeEach (consecutive turns share one where nothing could run between them), and its
        // contribution is applied right after its own capture, in iteration order, as before.
        List<Entity> entities = new ArrayList<>();
        for (Entity originalEntity : originalEntities) entities.add(originalEntity);
        FullEntityFacts.Candidate[] candidates = new FullEntityFacts.Candidate[entities.size()];
        RoutingKey[] keys = new RoutingKey[entities.size()];
        dispatcher.executeEach(world, entities.size(), i -> {
            candidates[i] = finishing.entityFacts.trackingCandidate(level, entities.get(i));
            return keys[i] = key(candidates[i]);
        }, RegionalServiceKind.SPAWN_STATE_CAPTURE, i -> {
            FullEntityFacts.Candidate candidate = candidates[i]; RoutingKey key = keys[i];
            candidates[i] = null;
            Contribution contribution = spawnStateContribution(candidate, key);
            if (contribution != null) {
                potential.addCharge(contribution.position, contribution.charge);
                if (contribution.mob) caps.addMob(ChunkPos.containing(contribution.position), contribution.category);
                counts.addTo(contribution.category, 1); entityContributions = Math.incrementExact(entityContributions);
            }
        });
        active.spawnState = state;
        return state;
    }

    /** One entity's owned spawn-state read: null when it contributes nothing. */
    private Contribution spawnStateContribution(FullEntityFacts.Candidate candidate, RoutingKey key) {
        requireEntity(candidate, key);
        Entity entity = candidate.alias();
        if (entity instanceof Mob mob && (mob.isPersistenceRequired() || mob.requiresCustomPersistence())) return null;
        MobCategory category = entity.getType().getCategory();
        if (category == MobCategory.MISC) return null;
        BlockPos position = entity.blockPosition();
        Contribution[] captured = new Contribution[1];
        ((RegionalServerChunkCacheAccess) cache).phoenium$invokeGetFullChunk(ChunkPos.pack(position), chunk -> {
            dispatcher.requireCurrentOwner(new RoutingKey(world, chunk.getPos().x(), chunk.getPos().z()));
            MobSpawnSettings.MobSpawnCost cost = chunk.getNoiseBiome(QuartPos.fromBlock(position.getX()),
                QuartPos.fromBlock(position.getY()), QuartPos.fromBlock(position.getZ()))
                .value().getMobSettings().getMobSpawnCost(entity.getType());
            captured[0] = new Contribution(position.immutable(), category,
                cost == null ? 0.0 : cost.charge(), entity instanceof Mob);
        });
        return captured[0];
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
            if (chunk != null && anyClose(active.players, holder.getPos())) output.add(chunk);
        }
        // S9-D13: vanilla shuffles this list in place, then visits it in order with one spawning callback per entry
        active.spawning = output;
        active.spawningCalls = 0;
        active.spawningNext = 0;
    }
    private static boolean anyClose(List<PlayerProjection> players, ChunkPos chunk) {
        for (PlayerProjection player : players) if (close(player, chunk)) return true;
        return false;
    }

    public void forEachBlockTickingChunk(ChunkMap expected, Consumer<LevelChunk> output) {
        requireCycle(); requireMap(expected);
        // The distance manager's level map is a fastutil open-hash map: a callback that adds or removes a ticket (a
        // portal arrival, a forceload, a player joining) while it is being iterated corrupts the iterator and fails
        // with a NullPointerException inside next(). Select the keys first, then run the callbacks over the copy.
        var keys = new it.unimi.dsi.fastutil.longs.LongArrayList();
        map.getDistanceManager().forEachEntityTickingChunk(keys::add);
        // S9-D13: one cursor over the selected keys; a callback's shared turn may take the following chunks (tickAhead)
        int[] cursor = {0};
        ChunkAhead ahead = new ChunkAhead() {
            @Override public LevelChunk next() {
                while (cursor[0] < keys.size()) {
                    ChunkHolder holder = ((RegionalVisibleChunkAccess) map).phoenium$visible(keys.getLong(cursor[0]++));
                    if (holder == null) continue;
                    LevelChunk chunk = holder.getTickingChunk();
                    if (chunk != null) return chunk;
                }
                return null;
            }
            @Override public int terrainRadius(WeatherCut weather) { return 1; }
            @Override public Runnable original(LevelChunk chunk) { return () -> output.accept(chunk); }
        };
        LevelChunk chunk;
        while ((chunk = ahead.next()) != null) chunkCallback(chunk, 1, ahead.original(chunk), ahead);
    }

    /** The pinned built-in NaturalSpawner groups reach three chunk keys from their selected source.
     * Radius eight is admitted only when the exact retained rain/thunder prefix can reach the
     * pinned 128-block lightning-rod POI query. A callback with no spawning categories and no
     * thunder has only its selected chunk's inhabited-time mutation. */
    public void spawningChunkCallback(LevelChunk chunk, boolean canSpawn, Consumer<LevelChunk> ticker) {
        Cycle active = requireCycle();
        // S9-D13: vanilla visits the collected list in order, one callback per entry. An entry that an earlier
        // callback's shared turn already ticked is skipped; a loop that is not that list takes no run-ahead.
        int position = active.spawningCalls++;
        List<LevelChunk> list = active.spawning;
        if (list != null && (position >= list.size() || list.get(position) != chunk)) {
            if (position < active.spawningNext)
                throw new IllegalStateException("spawning loop diverged from its collected list after a shared turn");
            active.spawning = list = null;
        }
        if (list != null) {
            if (position < active.spawningNext) return;
            active.spawningNext = position + 1;
        }
        boolean raining = level.isRaining();
        boolean thundering = raining && level.isThundering();
        active.weather = new WeatherCut(raining, thundering);
        try {
            chunkCallback(chunk, thundering ? 8 : canSpawn ? 3 : 0, () -> ticker.accept(chunk),
                list == null ? null : new SpawningAhead(active, list, canSpawn, ticker));
        } finally { active.weather = null; }
    }
    public void blockTickCallback(LevelChunk chunk, Runnable original) { chunkCallback(chunk, 1, original, null); }
    public void broadcastChunkCallback(LevelChunk chunk, Runnable original) { chunkCallback(chunk, 0, original, null); }

    /** S9-D13: the rest of a chunk callback loop, which a callback's shared turn may tick ahead. */
    private interface ChunkAhead {
        /** Takes the loop's next chunk (its own callback will then do nothing), or null at the loop's end. */
        LevelChunk next();
        /** The weather cut that chunk's own callback would take now (spawning callbacks only). */
        default WeatherCut weather() { return null; }
        int terrainRadius(WeatherCut weather);
        Runnable original(LevelChunk chunk);
    }
    private final class SpawningAhead implements ChunkAhead {
        private final Cycle active; private final List<LevelChunk> list; private final boolean canSpawn;
        private final Consumer<LevelChunk> ticker;
        SpawningAhead(Cycle active, List<LevelChunk> list, boolean canSpawn, Consumer<LevelChunk> ticker) {
            this.active = active; this.list = list; this.canSpawn = canSpawn; this.ticker = ticker;
        }
        @Override public LevelChunk next() {
            if (active.spawning != list || active.spawningNext >= list.size()) return null;
            return list.get(active.spawningNext++);
        }
        @Override public WeatherCut weather() {
            boolean raining = level.isRaining();
            return new WeatherCut(raining, raining && level.isThundering());
        }
        @Override public int terrainRadius(WeatherCut weather) { return weather.thundering ? 8 : canSpawn ? 3 : 0; }
        @Override public Runnable original(LevelChunk chunk) { return () -> ticker.accept(chunk); }
    }
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
            if (!runSpawnerAttempt(original)) {
                spawnerTerrainNotReady = Math.incrementExact(spawnerTerrainNotReady);
                org.slf4j.LoggerFactory.getLogger("Phoenium").info("PHOENIUM_SPAWNER_TERRAIN_NOT_READY spawner={} world={} attempts={}",
                    spawner.getClass().getSimpleName(), world, spawnerTerrainNotReady);
            }
        });
        chunkCallbacks = Math.incrementExact(chunkCallbacks);
    }
    /** Vanilla's spawner gate (hasChunksAt) counts a chunk ticketed FULL that is still loading, e.g. right after a
     * respawn or teleport, and vanilla would then wait for it; an owner transaction never may. That attempt ends as
     * vanilla's own "terrain not loaded" branch would: nothing more spawns this time. Every other failure propagates. */
    static boolean runSpawnerAttempt(Runnable original) {
        try { original.run(); return true; }
        catch (dev.phoenium.chunk.ChunkProtocolException failure) {
            if (!dev.phoenium.chunk.ChunkSynchronousAccessGuard.unfinishedWait(failure)) throw failure;
            return false;
        }
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
    private void chunkCallback(LevelChunk chunk, int terrainRadius, Runnable original, ChunkAhead ahead) {
        requireCycle(); Objects.requireNonNull(chunk); Objects.requireNonNull(original);
        cycle.players = capturePlayers().players;
        cycle.levelPlayers = captureLevelPlayers();
        ChunkCall call = new ChunkCall(chunk, terrainRadius, cycle, original);
        boolean uniform = dispatcher.ensureUniformClosure(call.source);
        if (!uniform) dispatcher.ensureClosure(call.footprint.closure());
        ChunkCall[] pending = {null};
        try {
            try (call) {
                call.open();
                dispatcher.executeAndDrain(call.source, RegionalServiceKind.CHUNK_GAMEPLAY, execution -> {
                    call.tick();
                    // S9-D13: this callback's finish would do nothing, so the loop's following chunks may tick here
                    if (uniform && ahead != null && call.spawn.settled()) {
                        call.close();
                        pending[0] = tickAhead(execution, ahead, call.cycle);
                    }
                });
                call.finish();
            }
            chunkCallbacks = Math.incrementExact(chunkCallbacks);
            if (pending[0] != null) {
                pending[0].finish();
                chunkCallbacks = Math.incrementExact(chunkCallbacks);
            }
        } finally { if (pending[0] != null) pending[0].close(); }
    }

    /**
     * S9-D13: inside a uniformly admitted chunk callback's turn whose natural spawn settled, the loop's following
     * chunks tick in the same turn while their own callbacks would follow it with nothing in between: the turn is
     * coalescable, and each chunk's natural spawn settles inside it (its owner-free finish would do nothing). Each
     * chunk keeps its own callback's weather cut, player capture, owner check, natural-spawn capture and owned
     * body, in loop order; the loop's own callback for it then does nothing. A chunk whose natural spawn yields
     * ends the shared turn and is returned, still open and with its weather cut, for its owner-free finish.
     */
    private ChunkCall tickAhead(RegionalServiceDispatcher.Execution execution, ChunkAhead ahead, Cycle active) {
        active.weather = null;
        while (dispatcher.coalescable(execution, world)) {
            LevelChunk chunk = ahead.next();
            if (chunk == null) return null;
            WeatherCut weather = ahead.weather();
            active.weather = weather;
            active.players = capturePlayers().players;
            active.levelPlayers = captureLevelPlayers();
            ChunkCall call = new ChunkCall(chunk, ahead.terrainRadius(weather), active, ahead.original(chunk));
            if (!dispatcher.requireUniformClosure(call.source))
                throw new IllegalStateException("shared chunk turn left its uniformly routed world");
            try { call.open().tick(); }
            catch (RuntimeException | Error failure) { call.close(); active.weather = null; throw failure; }
            if (!call.spawn.settled()) return call;
            call.close();
            chunkCallbacks = Math.incrementExact(chunkCallbacks);
            active.weather = null;
        }
        return null;
    }

    /** One chunk callback: its key and footprint, its native body under the owner, and its natural-spawn capture. */
    private final class ChunkCall implements AutoCloseable {
        final LevelChunk chunk; final Cycle cycle; final Runnable original;
        final RoutingKey source; final ChunkFootprint footprint;
        NaturalSpawnContinuation.Capture spawn;
        ChunkCall(LevelChunk chunk, int terrainRadius, Cycle cycle, Runnable original) {
            this.chunk = chunk; this.cycle = cycle; this.original = original;
            source = new RoutingKey(world, chunk.getPos().x(), chunk.getPos().z());
            int visibleRecipientRadius = Math.min(recipientRadius,
                ((RegionalChunkMapAccess) map).phoenium$getServerViewDistance() + 2);
            // S9-D7: built from these captured inputs only when a check needs its keys (a world that is not
            // uniformly routed), so every use sees the same footprint as the former eager construction.
            footprint = new ChunkFootprint(source, terrainRadius, cycle.players, visibleRecipientRadius);
        }
        ChunkCall open() {
            spawn = new NaturalSpawnContinuation.Capture(RegionalChunkGameplayService.this, dispatcher, level, chunk,
                source, naturalSpawnOrdinal = Math.incrementExact(naturalSpawnOrdinal),
                () -> RegionalChunkGameplayService.this.cycle == cycle && naturalSpawnClosureCurrent(source, footprint));
            return this;
        }
        void owned(Runnable body) {
            dispatcher.requireCurrentOwner(source); cycle.callbackKey = source;
            try {
                cycle.levelPlayers = refreshPlayerProjections(cycle.levelPlayers);
                body.run();
            } finally { cycle.callbackKey = null; cycle.nearestPlayer = null; }
        }
        void tick() { owned(() -> spawn.original(original)); }
        void finish() {
            spawn.finish(body -> {
                if (!dispatcher.requireUniformClosure(source)) dispatcher.requireCurrentClosure(footprint.closure());
                owned(body);
            });
        }
        @Override public void close() { if (spawn != null) spawn.close(); }
    }

    private boolean naturalSpawnClosureCurrent(RoutingKey source, ChunkFootprint footprint) {
        // S9-D7: a uniformly routed world has no pending change and one route for the source and every key
        // (each footprint key is built in source's world).
        if (directory.uniformRoute(source.world()) != null) return true;
        var owner = directory.resolve(source).orElse(null);
        if (owner == null) return false;
        for (var key : footprint.ordered()) {
            var route = directory.resolve(key).orElse(null);
            if (route == null || !route.region().equals(owner.region()) || !route.generation().equals(owner.generation())
                    || directory.pendingChange(key).filter(change -> !change.completed()).isPresent()) return false;
        }
        return true;
    }

    /** S9-D7: a chunk callback's terrain closure plus its visible current players, in the former insertion order. */
    private static final class ChunkFootprint {
        private final RoutingKey source; private final int terrainRadius; private final List<PlayerProjection> players;
        private final int visibleRecipientRadius;
        private Set<RoutingKey> ordered, closure;
        ChunkFootprint(RoutingKey source, int terrainRadius, List<PlayerProjection> players, int visibleRecipientRadius) {
            this.source = source; this.terrainRadius = terrainRadius; this.players = players;
            this.visibleRecipientRadius = visibleRecipientRadius;
        }
        Set<RoutingKey> ordered() {
            if (ordered == null) {
                Set<RoutingKey> keys = new LinkedHashSet<>(new TopologyPlanner().requiredClosure(Set.of(source), terrainRadius));
                for (PlayerProjection player : players)
                    if (Math.abs((long) player.key.x() - source.x()) <= visibleRecipientRadius
                            && Math.abs((long) player.key.z() - source.z()) <= visibleRecipientRadius) keys.add(player.key);
                ordered = keys;
            }
            return ordered;
        }
        Set<RoutingKey> closure() {
            if (closure == null) closure = Set.copyOf(ordered());
            return closure;
        }
    }

    /** Pinned NaturalSpawner calls the boolean getNearestPlayer overload and immediately asks the
     * selected alias for its squared distance. Preserve the level player-list order and strict tie
     * behavior while reading only exact position/spectator publications. */
    public Player nearestNaturalSpawnPlayer(double x, double y, double z) {
        Cycle active = requireOwnedCycle();
        boolean fresh = fresh(active.levelPlayers, finishing.entityFacts.playerPublications());
        PlayerProjection best = null; double bestDistance = -1.0;
        for (PlayerProjection retained : active.levelPlayers) {
            PlayerProjection player = fresh ? retained : refreshPlayerProjection(retained);
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
        // S9-D10: the player cut feeds only an entity's closure. A uniformly routed world admits the closure without
        // its keys, so the cut is captured (and its sessions validated) once for the pass, and per entity only where
        // a closure is built (another routing, or a dragon).
        capturePlayers();
        // S9-D11: the pass is the list as forEach would visit it; an entity removed before it is reached is skipped.
        // After an entity's uniformly admitted owner turn, the following entities may tick inside it
        // (tickFollowingEntities); each entity still ends with the owner-free portal advance, which a shared turn
        // skips only while it would do nothing.
        List<Entity> pass = entityTickPass(entities);
        int[] next = {0};
        while (next[0] < pass.size()) {
            Entity entity = pass.get(next[0]++);
            if (entity.isRemoved()) continue;
            // A dragon lazily reads the native 24-node heightmap ring. Selection can change
            // while the owner-free FULL inputs settle, so repeat its preflight before any
            // native body or RNG draw. A persistently moving cut waits for the next tick.
            int maximumAttempts = entity instanceof EnderDragon ? 3 : 1;
            for (int attempt = 0; attempt < maximumAttempts; attempt++) {
                if (attempt > 0 && entity.isRemoved()) break;
                FullEntityFacts.Candidate source = finishing.entityFacts.trackingCandidate(level, entity);
                List<FullEntityFacts.Candidate> passengers = finishing.entityFacts.trackingPassengers(source);
                FullEntityFacts.VehicleRelation vehicle = finishing.entityFacts.trackingVehicle(source);
                RoutingKey key = key(source);
                BlockPos fightOrigin = entity instanceof EnderDragon dragon ? dragon.getFightOrigin().immutable() : null;
                List<ChunkPos> dragonInputs = fightOrigin == null ? List.of() : dragonHeightmapInputChunks(fightOrigin);
                // S9-D7: every footprint key names this world; a uniformly routed world admits it without the keys.
                // The dragon's owner-turn route check reads its closure, so a dragon always builds it.
                Set<RoutingKey> closure = null;
                if (fightOrigin != null || !dispatcher.ensureUniformClosure(key)) {
                    closure = entityTickClosure(source, key, passengers, vehicle, capturePlayers(), dragonInputs);
                    dispatcher.ensureClosure(closure);
                }
                Set<RoutingKey> admittedClosure = closure;
                DragonHeightmapCut dragonCut = fightOrigin == null ? null : prepareDragonHeightmapInputs(dragonInputs);
                if (fightOrigin != null && dragonCut == null) continue;
                boolean[] stale = {false};
                dispatcher.executeAndDrain(key, RegionalServiceKind.CHUNK_GAMEPLAY, execution -> {
                    if (removedBeforeOwnerTurn(entity)) return;
                    if (dragonCut != null && (!((EnderDragon) entity).getFightOrigin().equals(fightOrigin)
                            || !dragonInputRoutesCurrent(key, admittedClosure) || !dragonCut.current())) {
                        stale[0] = true;
                        return;
                    }
                    requireEntity(source, key);
                    if (!finishing.entityFacts.validTrackingPassengers(source, passengers))
                        throw new IllegalStateException("entity tick passenger relation changed");
                    if (!finishing.entityFacts.validTrackingVehicle(vehicle))
                        throw new IllegalStateException("entity tick vehicle relation changed");
                    output.accept(entity);
                    if (admittedClosure == null) tickFollowingEntities(execution, pass, next, output);
                });
                if (!stale[0]) break;
            }
            // The enclosing native owner callback has returned. Finish this finite
            // portal operation before the next entity tick/RNG draw in native order.
            dev.phoenium.Phoenium.liveRuntime().advanceMobPortalAfterEntityCallback();
        }
    }

    /**
     * S9-D11: inside an entity's uniformly admitted owner turn, the following entities of the pass tick in the same
     * turn while their own turns would follow it with nothing in between: the turn is coalescable (no owner work
     * queued, no release notice, idle boundary, clear barrier, still uniform) and every portal coordinator is quiet,
     * so the owner-free advance after each entity would do nothing. Each entity keeps its preparation, owner checks
     * and native callback, in pass order; a removed one is skipped as before; a dragon, which builds a closure and
     * may retry, takes its own turn.
     */
    private void tickFollowingEntities(RegionalServiceDispatcher.Execution execution, List<Entity> pass, int[] next,
                                       Consumer<Entity> output) {
        var live = dev.phoenium.Phoenium.liveRuntime();
        while (next[0] < pass.size() && live.mobPortalsQuiet() && dispatcher.coalescable(execution, world)) {
            Entity entity = pass.get(next[0]);
            if (entity instanceof EnderDragon) return;
            next[0]++;
            if (entity.isRemoved()) continue;
            FullEntityFacts.Candidate source = finishing.entityFacts.trackingCandidate(level, entity);
            List<FullEntityFacts.Candidate> passengers = finishing.entityFacts.trackingPassengers(source);
            FullEntityFacts.VehicleRelation vehicle = finishing.entityFacts.trackingVehicle(source);
            RoutingKey key = key(source);
            if (!dispatcher.requireUniformClosure(key))
                throw new IllegalStateException("coalesced entity tick left its uniformly routed world");
            requireEntity(source, key);
            if (!finishing.entityFacts.validTrackingPassengers(source, passengers))
                throw new IllegalStateException("entity tick passenger relation changed");
            if (!finishing.entityFacts.validTrackingVehicle(vehicle))
                throw new IllegalStateException("entity tick vehicle relation changed");
            output.accept(entity);
        }
    }

    /** An entity tick's footprint: its own, passenger and vehicle halos, recipient players and dragon inputs. */
    private Set<RoutingKey> entityTickClosure(FullEntityFacts.Candidate source, RoutingKey key,
            List<FullEntityFacts.Candidate> passengers, FullEntityFacts.VehicleRelation vehicle,
            PlayerCut currentPlayers, List<ChunkPos> dragonInputs) {
        LinkedHashSet<RoutingKey> footprint = new LinkedHashSet<>();
        TopologyPlanner planner = new TopologyPlanner();
        footprint.addAll(planner.requiredClosure(Set.of(key), 1));
        passengers.forEach(passenger -> footprint.addAll(planner.requiredClosure(Set.of(key(passenger)), 1)));
        if (vehicle.vehicle() != null) footprint.addAll(planner.requiredClosure(Set.of(key(vehicle.vehicle())), 1));
        int entityRange = source.fact().type().clientTrackingRange() * 16;
        for (var passenger : passengers)
            entityRange = Math.max(entityRange, passenger.fact().type().clientTrackingRange() * 16);
        int entityRecipientRadius = Math.min((level.getServer().getScaledTrackingDistance(entityRange) + 15) / 16 + 2,
            ((RegionalChunkMapAccess) map).phoenium$getServerViewDistance() + 2);
        for (PlayerProjection player : currentPlayers.players)
            if (Math.abs((long) player.key.x() - key.x()) <= entityRecipientRadius
                    && Math.abs((long) player.key.z() - key.z()) <= entityRecipientRadius) footprint.add(player.key);
        for (ChunkPos input : dragonInputs) footprint.add(new RoutingKey(world, input.x(), input.z()));
        return Set.copyOf(footprint);
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
    /** S9-D11: the entities EntityTickList.forEach visits, in its order. A change while it iterates goes to a copy,
     * so it visits the list as it stood when the pass began; this is that list, taken once. */
    static List<Entity> entityTickPass(EntityTickList entities) {
        List<Entity> pass = new ArrayList<>();
        entities.forEach(pass::add);
        return pass;
    }
    static void forEachLiveEntityTickAlias(EntityTickList entities, Consumer<Entity> output) {
        for (Entity entity : entityTickPass(entities)) if (!entity.isRemoved()) output.accept(entity);
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
            if (tickUniformRun(iterator, runs)) continue;
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
                            try (var diagnostic = blockEntityScope(ticker, receipt.position, execution)) {
                                if (!receipt.binding.ready()) { removed[0] = true; return; }
                                removed[0] = tickTicker(ticker, runs, receipt.position, level::shouldTickBlocksAt, vaultTick::original);
                            }
                        });
                        vaultTick.finish(dispatcher, (execution, body) -> {
                            dispatcher.requireCurrentClosure(receipt.key, 1);
                            // The retained operation identity remains the original tick ordinal;
                            // each actual resume has a fresh current BLOCK_ENTITY_TICK claim.
                            try (var diagnostic = blockEntityScope(ticker, receipt.position, execution)) {
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

    /**
     * S9-D15: while the world is uniformly routed its one owner runs every ticker, and a local cohort
     * ({@link #tryLocalSegment}) would run serially on that owner as well. From the iterator's next ticker this runs
     * the window loop's serial protocol: an unready ticker is skipped owner-free (and dropped once retired); a ready
     * one admits its closure and runs in its own turn with its Vault continuation, as there. Further tickers then
     * join that turn in list order while it is coalescable ({@link RegionalServiceDispatcher#coalescable}: no owner
     * inbox work, no release notice, an idle boundary, the world still uniformly routed; between their own turns
     * nothing could run) and the turn's first ticker left no Vault suffix to await. A joined ticker is activated,
     * its closure re-checked under the shared owner and its original tick run as in the serial body; activation
     * and that check are consecutive in one turn, so the serial body's second readiness check is the activation.
     * A Vault ticker never joins; it opens the next turn. Removals and binding closes are applied owner-free after
     * the turn, in list order, as the serial loop applies them. Returns false, having consumed nothing, when the
     * world is not uniformly routed.
     */
    private boolean tickUniformRun(ListIterator<TickingBlockEntity> iterator, boolean runs) {
        if (directory.uniformRoute(world) == null) return false;
        TickingBlockEntity ticker = iterator.next();
        BlockEntityReceipt receipt = blockEntities.get(ticker);
        if (receipt == null) throw new IllegalStateException("unpublished block entity ticker in original list");
        if (!receipt.binding.activate()) {
            if (receipt.binding.state() == ResidentBlockEntityTickerBinding.State.RETIRED) {
                iterator.remove(); blockEntities.remove(ticker);
            }
            return true;
        }
        dispatcher.ensureClosure(receipt.key, 1);
        boolean[] removed = {false};
        List<JoinedTicker> joined = new ArrayList<>();
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
                try (var diagnostic = blockEntityScope(ticker, receipt.position, execution)) {
                    if (!receipt.binding.ready()) { removed[0] = true; return; }
                    removed[0] = tickTicker(ticker, runs, receipt.position, level::shouldTickBlocksAt, vaultTick::original);
                }
                // A retained Vault suffix is awaited owner-free before any later ticker, as between serial turns.
                if (vaultTick.query() == null) joinUniformTurn(iterator, execution, runs, joined);
            });
            vaultTick.finish(dispatcher, (execution, body) -> {
                dispatcher.requireCurrentClosure(receipt.key, 1);
                try (var diagnostic = blockEntityScope(ticker, receipt.position, execution)) {
                    body.run();
                }
            });
        }
        for (int index = 0; index <= joined.size(); index++) iterator.previous();
        if (iterator.next() != ticker) throw new IllegalStateException("original ticker iterator changed during a shared turn");
        if (removed[0]) { receipt.binding.close(); iterator.remove(); blockEntities.remove(ticker); }
        for (JoinedTicker entry : joined) {
            if (iterator.next() != entry.ticker) throw new IllegalStateException("original ticker iterator changed during a shared turn");
            switch (entry.outcome) {
                case CLOSE -> { entry.receipt.binding.close(); iterator.remove(); blockEntities.remove(entry.ticker); }
                case DROP -> { iterator.remove(); blockEntities.remove(entry.ticker); }
                case KEEP -> { }
            }
        }
        return true;
    }
    /** S9-D15: the tickers after a shared turn's first, run inside that turn (see {@link #tickUniformRun}). */
    private void joinUniformTurn(ListIterator<TickingBlockEntity> iterator, RegionalServiceDispatcher.Execution execution,
                                 boolean runs, List<JoinedTicker> joined) {
        java.util.function.Predicate<BlockPos> shouldTick = level::shouldTickBlocksAt;
        while (iterator.hasNext() && dispatcher.coalescable(execution, world)) {
            TickingBlockEntity ticker = iterator.next();
            BlockEntityReceipt receipt = blockEntities.get(ticker);
            if (receipt == null) throw new IllegalStateException("unpublished block entity ticker in original list");
            if (ticker instanceof LocalNativeTickerAccess access
                    && access.phoenium$localBlockEntity() instanceof net.minecraft.world.level.block.entity.vault.VaultBlockEntity) {
                iterator.previous(); return;
            }
            if (!receipt.binding.activate()) {
                joined.add(new JoinedTicker(ticker, receipt,
                    receipt.binding.state() == ResidentBlockEntityTickerBinding.State.RETIRED ? Joined.DROP : Joined.KEEP));
                continue;
            }
            dispatcher.requireCurrentClosure(receipt.key, 1);
            boolean removed;
            try (var diagnostic = blockEntityScope(ticker, receipt.position, execution)) {
                removed = tickTicker(ticker, runs, receipt.position, shouldTick);
            }
            joined.add(new JoinedTicker(ticker, receipt, removed ? Joined.CLOSE : Joined.KEEP));
        }
    }
    private enum Joined { KEEP, CLOSE, DROP }
    private record JoinedTicker(TickingBlockEntity ticker, BlockEntityReceipt receipt, Joined outcome) { }

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

    /* S9-D4 (owner step a): a player cut or level-player projection is re-derived only when one of its inputs
     * changed: the player list (order and connection), the connection directory revision, or any player's
     * published fact, route or precise position (FullEntityFacts.playerPublications). A reused cut still
     * revalidates every live session, and publishedPosition's access check still runs whenever it would have. */
    private record RetainedPlayers(List<ServerPlayer> order, long directory, long facts, PlayerCut captured, int playerMap) { }
    private record RetainedLevelPlayers(List<ServerPlayer> order, long facts, List<PlayerProjection> players) { }
    private record FreshProjections(List<PlayerProjection> players, long facts) { }
    private volatile RetainedPlayers retainedPlayers;
    private volatile RetainedLevelPlayers retainedLevelPlayers;
    private volatile FreshProjections freshProjections;

    private static boolean sameOrder(List<ServerPlayer> retained, java.util.Collection<ServerPlayer> live) {
        if (retained.size() != live.size()) return false;
        int i = 0;
        for (ServerPlayer player : live) if (retained.get(i++) != player) return false;
        return true;
    }
    private void requirePublishedPositionAccess(List<?> players) {
        if (!players.isEmpty())
            FullFinishingServices.requireServer(finishing.server(), FullFinishingServices.Kind.ENTITY_REGISTRY);
    }
    /** True when {@code players} was derived at publication {@code facts}, so a refresh would rebuild equal projections. */
    private boolean fresh(List<PlayerProjection> players, long facts) {
        FreshProjections stamp = freshProjections;
        if (stamp == null || stamp.players != players || stamp.facts != facts) return false;
        requirePublishedPositionAccess(players);
        return true;
    }

    private PlayerCut capturePlayers() {
        long facts = finishing.entityFacts.playerPublications();
        long directory = finishing.scoreboard.directoryRevision();
        var playerMap = ((RegionalChunkMapAccess) map).phoenium$getPlayerMap();
        var live = playerMap.getAllPlayers();
        int playerMapRevision = ((PlayerMapRevision) (Object) playerMap).phoenium$revision(); // final class: via Object
        RetainedPlayers retained = retainedPlayers;
        // S9-D16: an unchanged key-set count means the same members in the same order (see PlayerMapRevision), so
        // the open-hash key set is walked only after a change.
        if (retained != null && retained.facts == facts && retained.directory == directory
                && (retained.playerMap == playerMapRevision || sameOrder(retained.order, live))) {
            if (retained.playerMap != playerMapRevision)
                retainedPlayers = retained = new RetainedPlayers(retained.order, retained.directory, retained.facts,
                    retained.captured, playerMapRevision);
            FullFinishingScoreboard.TrackingCut cut = retained.captured.cut;
            requirePublishedPositionAccess(retained.order);
            finishing.scoreboard.requireTrackingCut(cut);
            return retained.captured;
        }
        List<ServerPlayer> order = List.copyOf(live);
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
        PlayerCut captured = new PlayerCut(cut, List.copyOf(result));
        retainedPlayers = new RetainedPlayers(order, cut.directoryRevision(), facts, captured, playerMapRevision);
        return captured;
    }
    private List<PlayerProjection> captureLevelPlayers() {
        long facts = finishing.entityFacts.playerPublications();
        List<ServerPlayer> live = level.players();
        RetainedLevelPlayers retained = retainedLevelPlayers;
        if (retained != null && retained.facts == facts && sameOrder(retained.order, live)) {
            boolean sameListeners = true;
            for (PlayerProjection player : retained.players)
                if (player.player.connection != player.listener) { sameListeners = false; break; }
            if (sameListeners) {
                requirePublishedPositionAccess(retained.players);
                freshProjections = new FreshProjections(retained.players, facts);
                return retained.players;
            }
        }
        List<ServerPlayer> order = List.copyOf(live);
        List<PlayerProjection> result = new ArrayList<>();
        for (ServerPlayer player : order) {
            FullEntityFacts.PublishedPosition published = finishing.entityFacts.publishedPosition(player);
            FullEntityFacts.Candidate candidate = published.candidate();
            if (candidate.fact().world() != level) throw new IllegalStateException("natural spawn player changed world");
            result.add(new PlayerProjection(player, player.connection, candidate, key(candidate),
                published.position(), candidate.fact().spectator()));
        }
        List<PlayerProjection> captured = List.copyOf(result);
        retainedLevelPlayers = new RetainedLevelPlayers(order, facts, captured);
        freshProjections = new FreshProjections(captured, facts);
        return captured;
    }
    private List<PlayerProjection> refreshPlayerProjections(List<PlayerProjection> retained) {
        long facts = finishing.entityFacts.playerPublications();
        if (fresh(retained, facts)) return retained;
        List<PlayerProjection> refreshed = new ArrayList<>(retained.size());
        retained.forEach(player -> refreshed.add(refreshPlayerProjection(player)));
        List<PlayerProjection> result = List.copyOf(refreshed);
        freshProjections = new FreshProjections(result, facts);
        return result;
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
        String objectId = candidate.fact().objectId();
        objects.requireCurrentOwnerIfPresent(objectId, directory.resolve(key).orElseThrow().region()).orElseThrow(
            () -> new IllegalStateException("spawn-state candidate lacks regional lifecycle ownership id=" + objectId
                + " runtimeId=" + candidate.fact().runtimeId() + " type=" + candidate.fact().type()
                + " route=" + key.x() + "," + key.z()));
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
        /** S9-D13: the collected spawning list, the callbacks vanilla has made over it, and its first unticked entry. */
        List<LevelChunk> spawning;
        int spawningCalls, spawningNext;
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
        // S9-D11: packed chunk keys; a boxed-Long HashMap collides on x ^ z and tree-bins with reflective compares
        private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<List<PlayerProjection>> near =
            new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
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
            long packed = pos.pack();
            List<PlayerProjection> found = near.get(packed);
            if (found == null) {
                if (!map.getDistanceManager().hasPlayersNearby(packed).toBoolean(true)) found = List.of();
                else {
                    List<PlayerProjection> close = new ArrayList<>();
                    for (PlayerProjection player : players.get()) if (close(player, pos)) close.add(player);
                    found = List.copyOf(close);
                }
                near.put(packed, found);
            }
            return found;
        }
    }
}
