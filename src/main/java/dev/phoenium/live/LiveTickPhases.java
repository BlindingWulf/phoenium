package dev.phoenium.live;

import dev.phoenium.ownership.OwnershipContext;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.TickRateManager;

/** Retained locals at two checked original tick cuts. These values grant no chunk access. */
public final class LiveTickPhases {
    private LiveTickPhases() { }
    public interface LevelAccess {
        LevelState phoenium$beforeChunkTick(BooleanSupplier haveTime);
        void phoenium$afterChunkTick(LevelState level, CacheState cache);
    }
    public interface CacheAccess {
        CacheState phoenium$prepareChunkTick(BooleanSupplier haveTime, boolean tickChunks);
        void phoenium$finishChunkTick(CacheState state);
    }

    public static final class LevelState {
        private final ServerLevel level;
        private final ServerChunkCache cache;
        private final BooleanSupplier haveTime;
        private final ProfilerFiller profiler;
        private final TickRateManager tickRate;
        private final boolean runs;
        private final int sleepingPercentage;
        private boolean consumed;
        public LevelState(ServerLevel level, ServerChunkCache cache, BooleanSupplier haveTime, ProfilerFiller profiler, TickRateManager tickRate, boolean runs, int sleepingPercentage) {
            if (OwnershipContext.current().isEmpty()) throw new IllegalStateException("level tick prefix has no owner");
            this.level = Objects.requireNonNull(level); this.haveTime = Objects.requireNonNull(haveTime);
            this.cache = Objects.requireNonNull(cache);
            this.profiler = Objects.requireNonNull(profiler); this.tickRate = Objects.requireNonNull(tickRate); this.runs = runs;
            this.sleepingPercentage = sleepingPercentage;
        }
        public BooleanSupplier haveTime() { return haveTime; }
        public ServerChunkCache cache() { return cache; }
        public ProfilerFiller profiler() { return profiler; }
        public TickRateManager tickRate() { return tickRate; }
        public boolean runs() { return runs; }
        public int sleepingPercentage() { return sleepingPercentage; }
        public void consume(ServerLevel expected) {
            if (expected != level || consumed || OwnershipContext.current().isEmpty())
                throw new IllegalStateException("wrong, repeated or unowned level tick suffix");
            consumed = true;
        }
    }
    public static final class CacheState {
        private final ServerChunkCache cache;
        private final BooleanSupplier haveTime;
        private final boolean tickChunks;
        private final ProfilerFiller profiler;
        private boolean consumed;
        public CacheState(ServerChunkCache cache, BooleanSupplier haveTime, boolean tickChunks, ProfilerFiller profiler) {
            if (OwnershipContext.current().isPresent()) throw new IllegalStateException("chunk readiness prefix still owns a region");
            this.cache = Objects.requireNonNull(cache); this.haveTime = Objects.requireNonNull(haveTime);
            this.tickChunks = tickChunks; this.profiler = Objects.requireNonNull(profiler);
        }
        public BooleanSupplier haveTime() { return haveTime; }
        public boolean tickChunks() { return tickChunks; }
        public ProfilerFiller profiler() { return profiler; }
        public void consume(ServerChunkCache expected) {
            if (expected != cache || consumed || OwnershipContext.current().isEmpty())
                throw new IllegalStateException("wrong, repeated or unowned chunk tick suffix");
            consumed = true;
        }
    }
    /** Replaces only the copied suffix's original ServerChunkCache.tick invocation. */
    public static void finishCache(ServerChunkCache cache, BooleanSupplier haveTime, boolean tickChunks, CacheState state) {
        if (state.cache != cache || state.haveTime != haveTime || state.tickChunks != tickChunks)
            throw new IllegalStateException("chunk tick continuation changed original arguments");
        ((CacheAccess) cache).phoenium$finishChunkTick(state);
    }
}
