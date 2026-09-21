package dev.phoenium.service;

import dev.phoenium.Phoenium;
import dev.phoenium.chunk.FullFinishingServices;
import dev.phoenium.chunk.FullGenerationSource;
import dev.phoenium.chunk.FullRegistrationRuntime;
import dev.phoenium.chunk.NaturalSpawnStructureQuery;
import dev.phoenium.mixin.ChunkMapLoadAccess;
import dev.phoenium.mixin.NaturalSpawnContinuationAccess;
import dev.phoenium.mixin.RegionalSpawnStateAccess;
import dev.phoenium.ownership.OwnershipContext;
import dev.phoenium.topology.RoutingKey;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BiomeTags;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;

/** Explicit program counter for the pinned 26.2 natural-spawn loop. Every RNG call
 * remains at its original branch. Yield points are only the two reached mobsAt
 * calls; entity construction, callbacks, weather, and earlier attempts are never replayed.
 */
public final class NaturalSpawnContinuation {
    public interface PlayerQueries {
        net.minecraft.world.entity.player.Player nearestNaturalSpawnPlayer(double x, double y, double z);
        double naturalSpawnPlayerDistance(net.minecraft.world.entity.player.Player player, double x, double y, double z);
    }
    private enum Pc { CATEGORY, GROUP, ATTEMPT, SELECT_RESULT, TYPE, CHECK_RESULT, NEXT_ATTEMPT, NEXT_GROUP, DONE }
    private static final ThreadLocal<Capture> ACTIVE = new ThreadLocal<>();
    private final Capture capture;
    private final ServerLevel level;
    private final LevelChunk chunk;
    private final RegionalSpawnStateAccess state;
    private final List<MobCategory> categories;
    private Pc pc = Pc.CATEGORY;
    private int categoryIndex, groupCount, attempt, max, groupSize, clusterSize, x, z;
    private BlockPos start;
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private double distance, xx, zz;
    private MobCategory category;
    private MobSpawnSettings.SpawnerData spawnData;
    private SpawnGroupData groupData;
    private NaturalSpawnStructureQuery query;
    private long queryGeneration;

    private NaturalSpawnContinuation(Capture capture, NaturalSpawner.SpawnState state, List<MobCategory> categories) {
        this.capture = capture; this.level = capture.level; this.chunk = capture.chunk;
        this.state = (RegionalSpawnStateAccess) state; this.categories = List.copyOf(categories);
    }
    public static boolean capture(ServerLevel level, LevelChunk chunk, NaturalSpawner.SpawnState state, List<MobCategory> categories) {
        Capture active = ACTIVE.get();
        if (active == null || !active.entered) return false;
        if (active.level != level || active.chunk != chunk || active.continuation != null)
            throw new IllegalStateException("overlapping or foreign natural-spawn native callback");
        active.continuation = new NaturalSpawnContinuation(active, state, categories);
        active.continuation.runOwned();
        return true;
    }

    /** The outer chunk callback stays synchronous until all exact queries settle. */
    public static final class Capture implements AutoCloseable {
        final PlayerQueries service;
        final RegionalServiceDispatcher dispatcher;
        final ServerLevel level;
        final LevelChunk chunk;
        final RoutingKey source;
        final long ordinal;
        final BooleanSupplier valid;
        final FullRegistrationRuntime structures;
        NaturalSpawnContinuation continuation;
        boolean entered, closed;
        public Capture(PlayerQueries service, RegionalServiceDispatcher dispatcher, ServerLevel level,
                LevelChunk chunk, RoutingKey source, long ordinal, BooleanSupplier valid) {
            if (ACTIVE.get() != null) throw new IllegalStateException("only one natural-spawn callback may be suspended on the native thread");
            this.service = service; this.dispatcher = dispatcher; this.level = level; this.chunk = chunk;
            this.source = source; this.ordinal = ordinal; this.valid = valid;
            this.structures = ((FullGenerationSource) level.getChunkSource().chunkMap).phoenium$fullRegistration();
            ACTIVE.set(this);
        }
        public void original(Runnable original) {
            entered = structures != null;
            try { original.run(); } finally { entered = false; }
        }
        boolean current() { return !closed && valid.getAsBoolean() && dispatcher.naturalSpawnOpen() && structures.naturalSpawnOpen(); }
        public void finish(Consumer<Runnable> ownerBody) {
            if (continuation == null) return;
            var loop = ((ChunkMapLoadAccess) level.getChunkSource().chunkMap).phoenium$mainExecutor();
            var fence = ((ManagedBlockPollTurnFence.Loop) loop).phoenium$naturalSpawnFence();
            while (continuation.pc != Pc.DONE && current()) {
                NaturalSpawnStructureQuery query = Objects.requireNonNull(continuation.query, "spawn yielded without a reached structure query");
                long generation = continuation.queryGeneration;
                query.prepare();
                try (query; var reservation = fence.reserve(ordinal, continuation.queryGeneration, query.dependency(),
                        () -> current() && continuation.query == query && continuation.queryGeneration == generation && query.current(),
                        dispatcher::requireNaturalSpawnOwnerFree)
                        .prepareAtObservation(query::prepare)
                        .shutdownWhen(() -> !dispatcher.naturalSpawnOpen() || !structures.naturalSpawnOpen())) {
                    reservation.awaitAndResume(loop::managedBlock, () -> dispatcher.resumeNaturalSpawn(reservation, source,
                        ignored -> ownerBody.accept(continuation::runOwned)));
                    if (reservation.state() != ManagedBlockPollTurnFence.State.SETTLED) return;
                }
            }
        }
        @Override public void close() {
            closed = true;
            if (continuation != null && continuation.query != null) continuation.query.close();
            if (ACTIVE.get() == this) ACTIVE.remove();
            continuation = null;
        }
    }

    private void runOwned() {
        if (OwnershipContext.current().isEmpty()) throw new IllegalStateException("natural spawn resumed without owner");
        var profiler = Profiler.get(); profiler.push("spawner");
        try {
            while (pc != Pc.DONE) switch (pc) {
                case CATEGORY -> {
                    if (categoryIndex == categories.size()) { pc = Pc.DONE; continue; }
                    category = categories.get(categoryIndex++);
                    if (!state.phoenium$canSpawnLocal(category, chunk.getPos())) continue;
                    start = NaturalSpawnContinuationAccess.phoenium$randomPos(level, chunk);
                    if (start.getY() < level.getMinY() + 1) continue;
                    var block = chunk.getBlockState(start);
                    if (block.isRedstoneConductor(chunk, start)) continue;
                    groupCount = 0; clusterSize = 0; pc = Pc.GROUP;
                }
                case GROUP -> {
                    if (groupCount == 3) { pc = Pc.CATEGORY; continue; }
                    x = start.getX(); z = start.getZ(); spawnData = null; groupData = null;
                    max = Mth.ceil(level.getRandom().nextFloat() * 4.0F); groupSize = 0; attempt = 0; pc = Pc.ATTEMPT;
                }
                case ATTEMPT -> {
                    if (attempt >= max) { pc = Pc.NEXT_GROUP; continue; }
                    x += level.getRandom().nextInt(6) - level.getRandom().nextInt(6);
                    z += level.getRandom().nextInt(6) - level.getRandom().nextInt(6);
                    pos.set(x, start.getY(), z); xx = x + 0.5; zz = z + 0.5;
                    var player = capture.service.nearestNaturalSpawnPlayer(xx, start.getY(), zz);
                    if (player == null) { pc = Pc.NEXT_ATTEMPT; continue; }
                    distance = capture.service.naturalSpawnPlayerDistance(player, xx, start.getY(), zz);
                    if (!NaturalSpawnContinuationAccess.phoenium$rightDistance(level, chunk, pos, distance)) { pc = Pc.NEXT_ATTEMPT; continue; }
                    if (spawnData != null) { pc = Pc.TYPE; continue; }
                    var biome = level.getBiome(pos);
                    if (category == MobCategory.WATER_AMBIENT && biome.is(BiomeTags.REDUCED_WATER_AMBIENT_SPAWNS)
                            && level.getRandom().nextFloat() < 0.98F) { pc = Pc.NEXT_GROUP; continue; }
                    query(biome.value().getMobSettings().getMobs(category)); pc = Pc.SELECT_RESULT; return;
                }
                case SELECT_RESULT -> {
                    var selected = query.snapshot().spawns().getRandom(level.getRandom());
                    query = null;
                    if (selected.isEmpty()) { pc = Pc.NEXT_GROUP; continue; }
                    spawnData = selected.get();
                    max = spawnData.minCount() + level.getRandom().nextInt(1 + spawnData.maxCount() - spawnData.minCount());
                    pc = Pc.TYPE;
                }
                case TYPE -> {
                    var type = spawnData.type();
                    if (type.getCategory() == MobCategory.MISC || !type.canSpawnFarFromPlayer()
                            && distance > type.getCategory().getDespawnDistance() * type.getCategory().getDespawnDistance()
                            || !type.canSummon()) { pc = Pc.NEXT_ATTEMPT; continue; }
                    query(level.getBiome(pos).value().getMobSettings().getMobs(category)); pc = Pc.CHECK_RESULT; return;
                }
                case CHECK_RESULT -> {
                    boolean contains = query.snapshot().spawns().contains(spawnData); query = null;
                    var type = spawnData.type();
                    pc = Pc.NEXT_ATTEMPT;
                    if (!contains || !SpawnPlacements.isSpawnPositionOk(type, level, pos)
                            || !SpawnPlacements.checkSpawnRules(type, level, EntitySpawnReason.NATURAL, pos, level.getRandom())
                            || !level.noCollision(type.getSpawnAABB(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5))
                            || !state.phoenium$canSpawn(type, pos, chunk)) continue;
                    Mob mob = NaturalSpawnContinuationAccess.phoenium$createMob(level, type);
                    if (mob == null) { pc = Pc.CATEGORY; continue; }
                    mob.snapTo(xx, start.getY(), zz, level.getRandom().nextFloat() * 360.0F, 0.0F);
                    if (!NaturalSpawnContinuationAccess.phoenium$validMob(level, mob, distance)) continue;
                    try (var passengerScope = Phoenium.liveRuntime().naturalSpawnPassengerScope(level)) {
                        try (var finalization = passengerScope.finalizing(mob)) {
                            groupData = mob.finalizeSpawn(level, level.getCurrentDifficultyAt(mob.blockPosition()),
                                EntitySpawnReason.NATURAL, groupData);
                        }
                        clusterSize++; groupSize++;
                        try (var turn = FullFinishingServices.forServer(level.getServer()).referenceAccess()) {
                            passengerScope.bulkInsertion(mob, () -> level.addFreshEntityWithPassengers(mob));
                        }
                    }
                    state.phoenium$afterSpawn(mob, chunk);
                    if (clusterSize >= mob.getMaxSpawnClusterSize()) { pc = Pc.CATEGORY; continue; }
                    if (mob.isMaxGroupSizeReached(groupSize)) pc = Pc.NEXT_GROUP;
                }
                case NEXT_ATTEMPT -> { attempt++; pc = Pc.ATTEMPT; }
                case NEXT_GROUP -> { groupCount++; pc = Pc.GROUP; }
                default -> throw new IllegalStateException("invalid natural-spawn program counter " + pc);
            }
        } finally { profiler.pop(); }
    }
    private void query(net.minecraft.util.random.WeightedList<MobSpawnSettings.SpawnerData> fallback) {
        boolean fortress = category == MobCategory.MONSTER && level.getBlockState(pos.below()).is(Blocks.NETHER_BRICKS);
        queryGeneration = Math.incrementExact(queryGeneration);
        query = new NaturalSpawnStructureQuery(capture.structures, chunk, pos, category, fortress, fallback, capture::current);
    }
}
