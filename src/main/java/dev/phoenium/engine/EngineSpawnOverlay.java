package dev.phoenium.engine;

import dev.phoenium.engine.mixin.EngineLocalMobCapAccess;
import dev.phoenium.engine.mixin.EngineMobCountsAccess;
import dev.phoenium.engine.mixin.EngineSpawnStateAccess;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LocalMobCapCalculator;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.PotentialCalculator;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.chunk.ChunkAccess;

/**
 * S9-E3: a running tile's view of the tick's NaturalSpawner.SpawnState. The shared state (spawn potential, category
 * counts, per-player local mob counts, the players near each chunk) is only read while tiles run; the tile's own spawns
 * are added to its local view at once, and to the shared state by a deferred effect after the tile's colour, in tile
 * order. Each method mirrors the vanilla SpawnState / LocalMobCapCalculator method of the same name over base + local.
 * Owned by one tile thread; reset when the thread enters a tile.
 */
public final class EngineSpawnOverlay {
    private static final MobCategory[] CATEGORIES = MobCategory.values();
    /** S9-E8b: spawns merged past the per-player local cap (server thread: the deferred merges run there). */
    private static long overshoot;

    static long overshoot() { return overshoot; }

    private NaturalSpawner.SpawnState state;
    private PotentialCalculator basePotential;
    private Long2ObjectMap<List<ServerPlayer>> baseNear;
    private Map<ServerPlayer, ?> baseCounts;
    private ChunkMap chunkMap;
    // this tile's own spawns
    private final ArrayList<BlockPos> chargePositions = new ArrayList<>();
    private double[] charges = new double[16];
    private final Long2ObjectOpenHashMap<List<ServerPlayer>> near = new Long2ObjectOpenHashMap<>();
    private final IdentityHashMap<ServerPlayer, int[]> counts = new IdentityHashMap<>();
    private BlockPos lastCheckedPos;
    private EntityType<?> lastCheckedType;
    private double lastCharge;

    void reset() {
        state = null;
        basePotential = null;
        baseNear = null;
        baseCounts = null;
        chunkMap = null;
        chargePositions.clear();
        near.clear();
        counts.clear();
        lastCheckedPos = null;
        lastCheckedType = null;
        lastCharge = 0.0;
    }

    EngineSpawnOverlay bind(NaturalSpawner.SpawnState state) {
        if (this.state != state) {
            reset();
            this.state = state;
            EngineSpawnStateAccess access = (EngineSpawnStateAccess) state;
            LocalMobCapCalculator caps = access.phoenium$localMobCaps();
            EngineLocalMobCapAccess capAccess = (EngineLocalMobCapAccess) caps;
            basePotential = access.phoenium$potential();
            baseNear = capAccess.phoenium$playersNear();
            baseCounts = capAccess.phoenium$playerMobCounts();
            chunkMap = capAccess.phoenium$chunkMap();
        }
        return this;
    }

    private static MobSpawnSettings.MobSpawnCost cost(EntityType<?> type, BlockPos pos, ChunkAccess chunk) {
        // NaturalSpawner.getRoughBiome
        return chunk.getNoiseBiome(QuartPos.fromBlock(pos.getX()), QuartPos.fromBlock(pos.getY()), QuartPos.fromBlock(pos.getZ()))
            .value().getMobSettings().getMobSpawnCost(type);
    }

    /** SpawnState.canSpawn: the energy change over the shared charges plus this tile's. */
    public boolean canSpawn(EntityType<?> type, BlockPos pos, ChunkAccess chunk) {
        lastCheckedPos = pos;
        lastCheckedType = type;
        MobSpawnSettings.MobSpawnCost cost = cost(type, pos, chunk);
        if (cost == null) {
            lastCharge = 0.0;
            return true;
        }
        double charge = cost.charge();
        lastCharge = charge;
        double change = basePotential.getPotentialEnergyChange(pos, charge) + localPotentialChange(pos, charge);
        return change <= cost.energyBudget();
    }

    private double localPotentialChange(BlockPos pos, double charge) {
        if (charge == 0.0 || chargePositions.isEmpty()) return 0.0;
        double sum = 0.0;
        for (int i = 0, n = chargePositions.size(); i < n; i++) {
            double distSqr = chargePositions.get(i).distSqr(pos);
            sum += distSqr == 0.0 ? Double.POSITIVE_INFINITY : charges[i] / Math.sqrt(distSqr);
        }
        return sum * charge;
    }

    /** SpawnState.afterSpawn: counted in this tile's view now, in the shared state after the colour. */
    public void afterSpawn(TileContext context, Mob mob, ChunkAccess chunk) {
        EntityType<?> type = mob.getType();
        BlockPos pos = mob.blockPosition();
        double charge;
        if (pos.equals(lastCheckedPos) && type == lastCheckedType) {
            charge = lastCharge;
        } else {
            MobSpawnSettings.MobSpawnCost cost = cost(type, pos, chunk);
            charge = cost != null ? cost.charge() : 0.0;
        }
        if (charge != 0.0) {
            int index = chargePositions.size();
            if (index == charges.length) charges = Arrays.copyOf(charges, index * 2);
            chargePositions.add(pos);
            charges[index] = charge;
        }
        MobCategory category = type.getCategory();
        ChunkPos chunkPos = ChunkPos.containing(pos);
        for (ServerPlayer player : playersNear(chunkPos)) {
            counts.computeIfAbsent(player, key -> new int[CATEGORIES.length])[category.ordinal()]++;
        }
        NaturalSpawner.SpawnState shared = state;
        double spawnedCharge = charge;
        context.defer(() -> {
            EngineSpawnStateAccess access = (EngineSpawnStateAccess) shared;
            // S9-E8b: a spawn the merged per-player local counts would already have refused (another tile spawned near the
            // same players earlier in this tick): the overlay's cap overshoot, measured (vanilla counts every spawn at once)
            if (!access.phoenium$localMobCaps().canSpawn(category, chunkPos)) overshoot++;
            access.phoenium$potential().addCharge(pos, spawnedCharge);
            access.phoenium$mobCategoryCounts().addTo(category, 1);
            access.phoenium$localMobCaps().addMob(chunkPos, category);
        });
    }

    /** SpawnState.canSpawnForCategoryLocal / LocalMobCapCalculator.canSpawn over the shared counts plus this tile's. */
    public boolean canSpawnLocal(MobCategory category, ChunkPos pos) {
        if (SharedConstants.DEBUG_IGNORE_LOCAL_MOB_CAP) return true;
        int max = category.getMaxInstancesPerChunk();
        for (ServerPlayer player : playersNear(pos)) {
            Object shared = baseCounts.get(player);
            int count = shared == null ? 0 : ((EngineMobCountsAccess) shared).phoenium$counts().getOrDefault(category, 0);
            int[] own = counts.get(player);
            if (own != null) count += own[category.ordinal()];
            if (count < max) return true;
        }
        return false;
    }

    /** LocalMobCapCalculator.getPlayersNear without writing the shared cache. */
    private List<ServerPlayer> playersNear(ChunkPos pos) {
        long key = pos.pack();
        List<ServerPlayer> shared = baseNear.get(key);
        if (shared != null) return shared;
        List<ServerPlayer> own = near.get(key);
        if (own == null) {
            own = chunkMap.getPlayersCloseForSpawning(pos);
            near.put(key, own);
        }
        return own;
    }
}
