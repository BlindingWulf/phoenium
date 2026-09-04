package dev.phoenium.chunk;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Shares vanilla prefetch's existing metadata monitor; never invokes installation or callbacks. */
public record PoiLoadBookkeeping(Object loadLock,
        Long2ObjectMap<CompletableFuture<Optional<?>>> pendingLoads, LongSet loadedChunks) {
    public PoiLoadBookkeeping {
        Objects.requireNonNull(loadLock); Objects.requireNonNull(pendingLoads); Objects.requireNonNull(loadedChunks);
    }
    public boolean alreadyInstalled(long key, CompletableFuture<?> source) {
        synchronized (loadLock) { return loadedChunks.contains(key) && pendingLoads.get(key) != source; }
    }
    public boolean needsInstallation(long key, CompletableFuture<?> source) {
        synchronized (loadLock) {
            if (loadedChunks.contains(key)) return false;
            requireSource(key, source);
            return true;
        }
    }
    /** Called only AFTER the original owner-admitted installation returns successfully. */
    public void acknowledgeInstallation(long key, CompletableFuture<?> source) {
        synchronized (loadLock) {
            requireSource(key, source);
            pendingLoads.remove(key);
            loadedChunks.add(key);
        }
    }
    private void requireSource(long key, CompletableFuture<?> source) {
        if (pendingLoads.get(key) != source) throw new ChunkProtocolException("POI pending source identity changed");
    }
}
