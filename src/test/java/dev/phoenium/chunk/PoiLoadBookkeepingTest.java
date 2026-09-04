package dev.phoenium.chunk;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PoiLoadBookkeepingTest {
    @Test void prefetchAndOwnerAcknowledgmentUseTheSameMetadataMonitor() {
        Object monitor = new Object();
        var pending = new Long2ObjectOpenHashMap<CompletableFuture<Optional<?>>>() {
            @Override public CompletableFuture<Optional<?>> get(long key) {
                assertTrue(Thread.holdsLock(monitor)); return super.get(key);
            }
            @Override public CompletableFuture<Optional<?>> remove(long key) {
                assertTrue(Thread.holdsLock(monitor)); return super.remove(key);
            }
        };
        var loaded = new LongOpenHashSet() {
            @Override public boolean contains(long key) { assertTrue(Thread.holdsLock(monitor)); return super.contains(key); }
            @Override public boolean add(long key) { assertTrue(Thread.holdsLock(monitor)); return super.add(key); }
        };
        CompletableFuture<Optional<?>> source = CompletableFuture.completedFuture(Optional.empty());
        synchronized (monitor) { pending.put(7, source); }
        var bookkeeping = new PoiLoadBookkeeping(monitor, pending, loaded);
        assertTrue(bookkeeping.needsInstallation(7, source));
        assertFalse(Thread.holdsLock(monitor), "the original install runs after metadata preparation exits");
        assertFalse(bookkeeping.alreadyInstalled(7, source));
        bookkeeping.acknowledgeInstallation(7, source);
        assertTrue(bookkeeping.alreadyInstalled(7, source));
        assertFalse(bookkeeping.needsInstallation(7, source));
    }

    @Test void staleAcknowledgmentCannotRemoveAReplacementSource() {
        Object monitor = new Object();
        var pending = new Long2ObjectOpenHashMap<CompletableFuture<Optional<?>>>();
        var loaded = new LongOpenHashSet();
        CompletableFuture<Optional<?>> old = new CompletableFuture<>(), replacement = new CompletableFuture<>();
        pending.put(7, old);
        var bookkeeping = new PoiLoadBookkeeping(monitor, pending, loaded);
        assertTrue(bookkeeping.needsInstallation(7, old));
        synchronized (monitor) { pending.put(7, replacement); }
        assertThrows(ChunkProtocolException.class, () -> bookkeeping.acknowledgeInstallation(7, old));
        assertSame(replacement, pending.get(7));
        assertFalse(loaded.contains(7));
    }

    @Test void preparationDoesNotMarkFailedOrUnfinishedInstallationLoaded() {
        var pending = new Long2ObjectOpenHashMap<CompletableFuture<Optional<?>>>();
        var loaded = new LongOpenHashSet();
        CompletableFuture<Optional<?>> source = new CompletableFuture<>();
        pending.put(7, source);
        var bookkeeping = new PoiLoadBookkeeping(new Object(), pending, loaded);
        assertTrue(bookkeeping.needsInstallation(7, source));
        assertFalse(bookkeeping.alreadyInstalled(7, source));
        assertSame(source, pending.get(7));
        assertFalse(loaded.contains(7));
    }
}
