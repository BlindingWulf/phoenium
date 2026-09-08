package dev.phoenium.live;

import dev.phoenium.ownership.OwnershipContext;
import dev.phoenium.Phoenium;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityTickList;
import java.util.function.Consumer;

/** Retained locals at two checked original tick cuts. These values grant no chunk access. */
public final class LiveTickPhases {
    private LiveTickPhases() { }
    public interface LevelAccess {
        LevelState phoenium$beforeScheduledTicks(BooleanSupplier haveTime);
        void phoenium$runScheduledTicks(LevelState state);
        void phoenium$beforeChunkTick(LevelState state);
        void phoenium$afterChunkTick(LevelState state, CacheState cache);
        void phoenium$runBlockEvents(LevelState state);
        boolean phoenium$beforeEntityTicks(LevelState state);
        void phoenium$beforeEntityList(LevelState state);
        void phoenium$runEntityTicks(LevelState state);
        void phoenium$afterEntityList(LevelState state);
        void phoenium$afterEntityTicks(LevelState state);
    }
    public interface CacheAccess {
        CacheState phoenium$prepareChunkTick(BooleanSupplier haveTime, boolean tickChunks);
        void phoenium$finishChunkTick(CacheState state);
        void phoenium$finishChunkMaintenance(CacheState state);
    }

    public static final class LevelState {
        private final ServerLevel level;
        private final BooleanSupplier haveTime;
        private final ProfilerFiller profiler;
        private final TickRateManager tickRate;
        private final boolean runs;
        private final int sleepingPercentage;
        private ServerChunkCache cache;
        private boolean finalActive;
        private Phase phase = Phase.PREFIX_COMPLETE;
        public LevelState(ServerLevel level, BooleanSupplier haveTime, ProfilerFiller profiler, TickRateManager tickRate, boolean runs, int sleepingPercentage) {
            if (OwnershipContext.current().isEmpty()) throw new IllegalStateException("level tick prefix has no owner");
            this.level = Objects.requireNonNull(level); this.haveTime = Objects.requireNonNull(haveTime);
            this.profiler = Objects.requireNonNull(profiler); this.tickRate = Objects.requireNonNull(tickRate); this.runs = runs;
            this.sleepingPercentage = sleepingPercentage;
        }
        /** Historical focused-test constructor retained to prove final-phase single consumption. */
        public LevelState(ServerLevel level, ServerChunkCache cache, BooleanSupplier haveTime, ProfilerFiller profiler,
                          TickRateManager tickRate, boolean runs, int sleepingPercentage) {
            this(level, haveTime, profiler, tickRate, runs, sleepingPercentage);
            this.cache = Objects.requireNonNull(cache);
            this.phase = Phase.ENTITY_BODY_COMPLETE;
        }
        public BooleanSupplier haveTime() { return haveTime; }
        public ServerChunkCache cache() { return cache; }
        public ProfilerFiller profiler() { return profiler; }
        public TickRateManager tickRate() { return tickRate; }
        public boolean runs() { return runs; }
        public int sleepingPercentage() { return sleepingPercentage; }
        public boolean finalActive() { return finalActive; }
        public void beginScheduled(ServerLevel expected) { require(expected, Phase.PREFIX_COMPLETE, false); phase = Phase.SCHEDULED_RUNNING; }
        public void finishScheduled(ServerLevel expected) { require(expected, Phase.SCHEDULED_RUNNING, false); phase = Phase.SCHEDULED_COMPLETE; }
        public void beginMiddle(ServerLevel expected) { require(expected, Phase.SCHEDULED_COMPLETE, true); phase = Phase.MIDDLE_RUNNING; }
        public void finishMiddle(ServerLevel expected, ServerChunkCache cache) {
            require(expected, Phase.MIDDLE_RUNNING, true); this.cache = Objects.requireNonNull(cache); phase = Phase.MIDDLE_COMPLETE;
        }
        public void consumeChunk(ServerLevel expected) { require(expected, Phase.MIDDLE_COMPLETE, false); phase = Phase.CHUNK_RUNNING; }
        public void finishChunk(ServerLevel expected) { require(expected, Phase.CHUNK_RUNNING, false); phase = Phase.CHUNK_GAMEPLAY_COMPLETE; }
        public void finishMaintenance(ServerLevel expected) { require(expected, Phase.CHUNK_GAMEPLAY_COMPLETE, false); phase = Phase.CHUNK_COMPLETE; }
        public void beginBlockEvents(ServerLevel expected) { require(expected, Phase.CHUNK_COMPLETE, false); phase = Phase.BLOCK_EVENTS_RUNNING; }
        public void finishBlockEvents(ServerLevel expected) { require(expected, Phase.BLOCK_EVENTS_RUNNING, false); phase = Phase.BLOCK_EVENTS_COMPLETE; }
        public void beginFinalPrefix(ServerLevel expected) { require(expected, Phase.BLOCK_EVENTS_COMPLETE, true); phase = Phase.FINAL_PREFIX_RUNNING; }
        public void finishFinalPrefix(ServerLevel expected, boolean active) {
            require(expected, Phase.FINAL_PREFIX_RUNNING, true); finalActive = active; phase = Phase.FINAL_PREFIX_COMPLETE;
        }
        public void skipEntityBody(ServerLevel expected) { require(expected, Phase.FINAL_PREFIX_COMPLETE, false); phase = Phase.ENTITY_BODY_COMPLETE; }
        public void beginEntityPrelude(ServerLevel expected) { require(expected, Phase.FINAL_PREFIX_COMPLETE, true); phase = Phase.ENTITY_PRELUDE_RUNNING; }
        public void finishEntityPrelude(ServerLevel expected) { require(expected, Phase.ENTITY_PRELUDE_RUNNING, true); phase = Phase.ENTITY_PRELUDE_COMPLETE; }
        public void beginEntities(ServerLevel expected) { require(expected, Phase.ENTITY_PRELUDE_COMPLETE, false); phase = Phase.ENTITIES_RUNNING; }
        public void finishEntities(ServerLevel expected) { require(expected, Phase.ENTITIES_RUNNING, false); phase = Phase.ENTITIES_COMPLETE; }
        public void beginBlockEntities(ServerLevel expected) { require(expected, Phase.ENTITIES_COMPLETE, true); phase = Phase.BLOCK_ENTITIES_RUNNING; }
        public void finishBlockEntities(ServerLevel expected) { require(expected, Phase.BLOCK_ENTITIES_RUNNING, true); phase = Phase.ENTITY_BODY_COMPLETE; }
        public void consumeFinal(ServerLevel expected) { require(expected, Phase.ENTITY_BODY_COMPLETE, true); phase = Phase.COMPLETE; }
        public void consume(ServerLevel expected) { consumeFinal(expected); }
        private void require(ServerLevel expected, Phase expectedPhase, boolean owned) {
            if (expected != level || phase != expectedPhase || OwnershipContext.current().isPresent() != owned)
                throw new IllegalStateException("wrong, repeated, or authority-invalid level tick phase " + phase + " expected " + expectedPhase);
        }
        private enum Phase { PREFIX_COMPLETE, SCHEDULED_RUNNING, SCHEDULED_COMPLETE, MIDDLE_RUNNING,
            MIDDLE_COMPLETE, CHUNK_RUNNING, CHUNK_GAMEPLAY_COMPLETE, CHUNK_COMPLETE,
            BLOCK_EVENTS_RUNNING, BLOCK_EVENTS_COMPLETE, FINAL_PREFIX_RUNNING, FINAL_PREFIX_COMPLETE,
            ENTITY_PRELUDE_RUNNING, ENTITY_PRELUDE_COMPLETE, ENTITIES_RUNNING, ENTITIES_COMPLETE,
            BLOCK_ENTITIES_RUNNING, ENTITY_BODY_COMPLETE, COMPLETE }
    }
    public static final class CacheState {
        private final ServerChunkCache cache;
        private final BooleanSupplier haveTime;
        private final boolean tickChunks;
        private final ProfilerFiller profiler;
        private Phase phase = Phase.PREPARED;
        public CacheState(ServerChunkCache cache, BooleanSupplier haveTime, boolean tickChunks, ProfilerFiller profiler) {
            if (OwnershipContext.current().isPresent()) throw new IllegalStateException("chunk readiness prefix still owns a region");
            this.cache = Objects.requireNonNull(cache); this.haveTime = Objects.requireNonNull(haveTime);
            this.tickChunks = tickChunks; this.profiler = Objects.requireNonNull(profiler);
        }
        public BooleanSupplier haveTime() { return haveTime; }
        public boolean tickChunks() { return tickChunks; }
        public ProfilerFiller profiler() { return profiler; }
        public void beginGameplay(ServerChunkCache expected) { require(expected, Phase.PREPARED, false); phase = Phase.GAMEPLAY_RUNNING; }
        public void finishGameplay(ServerChunkCache expected) { require(expected, Phase.GAMEPLAY_RUNNING, false); phase = Phase.GAMEPLAY_COMPLETE; }
        public void beginMaintenance(ServerChunkCache expected) { require(expected, Phase.GAMEPLAY_COMPLETE, false); phase = Phase.MAINTENANCE_RUNNING; }
        public void finishMaintenance(ServerChunkCache expected) { require(expected, Phase.MAINTENANCE_RUNNING, false); phase = Phase.COMPLETE; }
        public void consume(ServerChunkCache expected) { beginGameplay(expected); finishGameplay(expected); }
        private void require(ServerChunkCache expected, Phase expectedPhase, boolean owned) {
            if (expected != cache || phase != expectedPhase || OwnershipContext.current().isPresent() != owned)
                throw new IllegalStateException("wrong, repeated, or authority-invalid chunk tick phase " + phase + " expected " + expectedPhase);
        }
        private enum Phase { PREPARED, GAMEPLAY_RUNNING, GAMEPLAY_COMPLETE, MAINTENANCE_RUNNING, COMPLETE }
    }
    /** Replaces only the copied suffix's original ServerChunkCache.tick invocation. */
    public static void finishCache(ServerChunkCache cache, BooleanSupplier haveTime, boolean tickChunks, CacheState state) {
        if (state.cache != cache || state.haveTime != haveTime || state.tickChunks != tickChunks)
            throw new IllegalStateException("chunk tick continuation changed original arguments");
        ((CacheAccess) cache).phoenium$finishChunkTick(state);
    }
    public static void finishCacheMaintenance(ServerChunkCache cache, CacheState state) {
        if (state.cache != cache) throw new IllegalStateException("chunk maintenance changed original cache");
        ((CacheAccess) cache).phoenium$finishChunkMaintenance(state);
    }
    /** Exact replacement for the copied private tickChunks invocation at its owner-free cut. */
    public static void runChunkGameplay(ServerChunkCache cache) {
        Phoenium.liveRuntime().runRegionalChunkGameplay(cache);
    }
    public static void runEntityTicks(EntityTickList entities, Consumer<Entity> output, ServerLevel level) {
        Phoenium.liveRuntime().runRegionalEntityTicks(level, entities, output);
    }
}
