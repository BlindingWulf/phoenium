package dev.phoenium.chunk;

import com.google.gson.Gson;
import com.mojang.authlib.GameProfileRepository;
import com.mojang.authlib.yggdrasil.response.NameAndId;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import net.minecraft.server.players.CachedUserNameToIdResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static dev.phoenium.chunk.FullDecisionWitnessSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual vanilla cache maps/entry objects and disposable files, with controlled provider leaves. */
final class FullLegacyOwnerCacheTest {
    @TempDir Path directory;
    private GameProfileRepository provider(Function<String, Optional<NameAndId>> lookup) {
        return (GameProfileRepository) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{GameProfileRepository.class},
                (proxy, method, args) -> lookup.apply((String) args[0]));
    }
    @SuppressWarnings("unchecked")
    static final class Cache extends CachedUserNameToIdResolver implements FullLegacyOwnerCache.Access, AutoCloseable {
        FullLegacyOwnerCache protocol;
        boolean offline = true;
        Cache(GameProfileRepository provider, Path file) throws Exception {
            super(provider, file.toFile());
            protocol = new FullLegacyOwnerCache(this, (Map<String, Object>) field(this, CachedUserNameToIdResolver.class, "profilesByName"),
                    (Map<UUID, Object>) field(this, CachedUserNameToIdResolver.class, "profilesByUUID"), provider, new Gson(), file.toFile(),
                    (AtomicLong) field(this, CachedUserNameToIdResolver.class, "operationCount"), () -> offline, value -> offline = value);
        }
        public FullLegacyOwnerCache phoenium$ownerCache() { return protocol; }
        @Override public Optional<net.minecraft.server.players.NameAndId> get(String name) { return protocol.get(name); }
        @Override public Optional<net.minecraft.server.players.NameAndId> get(UUID id) { return protocol.get(id); }
        @Override public void add(net.minecraft.server.players.NameAndId value) { protocol.add(value); }
        @Override public void save() { protocol.save(); }
        @Override public void resolveOfflineUsers(boolean value) { protocol.offline(value); }
        public void close() { protocol.shutdown(); }
    }
    @Test void missCommitsOriginalMapsAndSettledFileThenHitOnlyTouchesMru() throws Exception {
        AtomicInteger lookups = new AtomicInteger(); UUID id = UUID.randomUUID(); Path file = directory.resolve("usercache.json");
        try (Cache cache = new Cache(provider(name -> { lookups.incrementAndGet(); assertEquals("oldowner", name); return Optional.of(new NameAndId(id, name)); }), file)) {
            assertEquals(id, cache.get("OldOwner").orElseThrow().id());
            assertEquals(FullLegacyOwnerCache.Persistence.WRITTEN, cache.protocol.lastSave().outcome());
            assertTrue(Files.readString(file).contains(id.toString()));
            var cut = cache.protocol.cut("OldOwner"); Files.writeString(file, "sentinel");
            assertEquals(id, cache.get("OLDOWNER").orElseThrow().id());
            assertTrue(cache.protocol.valid("OldOwner", cut)); assertEquals("sentinel", Files.readString(file)); assertEquals(1, lookups.get());
        }
    }
    @Test void physicalIoCaughtIsDistinctFromResolutionAndRuntimeFailure() throws Exception {
        Path file = directory.resolve("usercache.json"); Files.createDirectory(file); UUID id = UUID.randomUUID();
        try (Cache cache = new Cache(provider(name -> Optional.of(new NameAndId(id, name))), file)) {
            assertEquals(id, cache.get("Owner").orElseThrow().id());
            assertEquals(FullLegacyOwnerCache.Persistence.IO_CAUGHT, cache.protocol.lastSave().outcome());
            assertEquals(id, cache.get(id).orElseThrow().id());
        }
    }
    @Test void lateProviderDoesNotOverwriteAConcurrentAuthoritativeAdd() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        UUID late = UUID.randomUUID(), current = UUID.randomUUID();
        try (Cache cache = new Cache(provider(name -> {
            started.countDown(); try { assertTrue(release.await(5, TimeUnit.SECONDS)); } catch (InterruptedException e) { throw new AssertionError(e); }
            return Optional.of(new NameAndId(late, name));
        }), directory.resolve("usercache.json"))) {
            var pending = CompletableFuture.supplyAsync(() -> cache.get("owner"));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            cache.add(new net.minecraft.server.players.NameAndId(current, "owner"));
            release.countDown();
            assertEquals(current, pending.get(5, TimeUnit.SECONDS).orElseThrow().id());
            assertFalse(Files.readString(directory.resolve("usercache.json")).contains(late.toString()));
        } finally { release.countDown(); }
    }
    @Test void policyAndMappingChangeInvalidateButMruDoesNot() throws Exception {
        try (Cache cache = new Cache(provider(name -> Optional.empty()), directory.resolve("usercache.json"))) {
            cache.get("owner"); var before = cache.protocol.cut("owner");
            cache.get("owner"); assertTrue(cache.protocol.valid("owner", before));
            cache.resolveOfflineUsers(false); assertFalse(cache.protocol.valid("owner", before));
            var mapping = cache.protocol.cut("owner"); cache.add(new net.minecraft.server.players.NameAndId(UUID.randomUUID(), "owner"));
            assertFalse(cache.protocol.valid("owner", mapping));
        }
    }
    @Test void expiredRemovalWithEmptyResolutionSettlesItsRequiredSave() throws Exception {
        UUID id = UUID.randomUUID(); Path file = directory.resolve("usercache.json");
        Files.writeString(file, "[{\"name\":\"owner\",\"uuid\":\"" + id + "\",\"expiresOn\":\"2000-01-01 00:00:00 +0000\"}]");
        try (Cache cache = new Cache(provider(name -> Optional.empty()), file)) {
            cache.resolveOfflineUsers(false); assertTrue(cache.get("owner").isEmpty()); assertTrue(cache.get(id).isEmpty());
            assertEquals("[]", Files.readString(file)); assertEquals(FullLegacyOwnerCache.Persistence.WRITTEN, cache.protocol.lastSave().outcome());
        }
    }
    @Test void separateConcurrentSavesCannotLetAnOlderSnapshotOverwriteTheLaterOne() throws Exception {
        try (Cache cache = new Cache(provider(name -> Optional.empty()), directory.resolve("usercache.json"))) {
            List<CompletableFuture<Void>> writes = new ArrayList<>();
            for (int i = 0; i < 12; i++) { final int ordinal = i; writes.add(CompletableFuture.runAsync(() ->
                    cache.add(new net.minecraft.server.players.NameAndId(UUID.randomUUID(), "owner" + ordinal)))); }
            CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
            String persisted = Files.readString(directory.resolve("usercache.json"));
            for (int i = 0; i < 12; i++) assertTrue(persisted.contains("owner" + i));
        }
    }
    @Test void stoppedStorageRejectsTheAcceptedWriteWithoutRawThreadFallbackOrHangingReceipt() throws Exception {
        try (Cache cache = new Cache(provider(name -> Optional.empty()), directory.resolve("usercache.json"))) {
            cache.protocol.shutdown();
            RejectedExecutionException failure = assertThrows(RejectedExecutionException.class, cache.protocol::save);
            assertSame(failure, cache.protocol.lastSave().failure());
            assertEquals(FullLegacyOwnerCache.Persistence.RUNTIME_FAILURE, cache.protocol.lastSave().outcome());
            assertEquals(0, cache.protocol.pendingWrites());
            assertFalse(Files.exists(directory.resolve("usercache.json")), "rejection must not run the write on a caller or fallback thread");
        }
    }

    @Test void shutdownCannotStrandAcceptedSaveBehindAnUnqueuedEarlierSnapshot() throws Exception {
        CountDownLatch earlierAtAdmission = new CountDownLatch(1), releaseEarlier = new CountDownLatch(1);
        CountDownLatch writeSubmitted = new CountDownLatch(1);
        var earlierResult = new CompletableFuture<Throwable>();
        var laterResult = new CompletableFuture<Throwable>();
        try (Cache cache = new Cache(provider(name -> Optional.empty()), directory.resolve("usercache.json"))) {
            var gate = new java.util.concurrent.locks.ReentrantLock() {
                @Override public void lock() {
                    if (Thread.currentThread().getName().equals("earlier-profile-save")) {
                        earlierAtAdmission.countDown();
                        try { assertTrue(releaseEarlier.await(5, TimeUnit.SECONDS)); }
                        catch (InterruptedException failure) { throw new AssertionError(failure); }
                    }
                    super.lock();
                }
            };
            var field = FullLegacyOwnerCache.class.getDeclaredField("writesGate");
            field.setAccessible(true); field.set(cache.protocol, gate);
            var stateGate = (java.util.concurrent.locks.ReentrantLock)
                    field(cache.protocol, FullLegacyOwnerCache.class, "state");
            var storageField = FullLegacyOwnerCache.class.getDeclaredField("storage");
            storageField.setAccessible(true);
            var actualStorage = (ExecutorService) storageField.get(cache.protocol);
            storageField.set(cache.protocol, new AbstractExecutorService() {
                public void execute(Runnable task) { actualStorage.execute(task); writeSubmitted.countDown(); }
                public void shutdown() { actualStorage.shutdown(); }
                public List<Runnable> shutdownNow() { return actualStorage.shutdownNow(); }
                public boolean isShutdown() { return actualStorage.isShutdown(); }
                public boolean isTerminated() { return actualStorage.isTerminated(); }
                public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
                    return actualStorage.awaitTermination(timeout, unit);
                }
            });
            Thread earlier = new Thread(() -> {
                try { cache.save(); earlierResult.complete(null); }
                catch (Throwable failure) { earlierResult.complete(failure); }
            }, "earlier-profile-save");
            Thread later = new Thread(() -> {
                try { cache.save(); laterResult.complete(null); }
                catch (Throwable failure) { laterResult.complete(failure); }
            }, "later-profile-save");
            earlier.setDaemon(true); later.setDaemon(true);
            try {
                earlier.start(); assertTrue(earlierAtAdmission.await(5, TimeUnit.SECONDS));
                later.start();
                // Old code permits the later snapshot to queue through this gate; the fixed
                // code keeps it behind the earlier snapshot's still-held state turn.
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (cache.protocol.pendingWrites() == 0 && !stateGate.hasQueuedThread(later)
                        && System.nanoTime() < deadline)
                    java.util.concurrent.locks.LockSupport.parkNanos(100_000);
                boolean queued = cache.protocol.pendingWrites() != 0;
                assertTrue(queued || stateGate.hasQueuedThread(later), "later save reached its actual ordering boundary");
                if (queued) assertTrue(writeSubmitted.await(5, TimeUnit.SECONDS), "later write was accepted by original storage executor");
                cache.protocol.shutdown();
                releaseEarlier.countDown();
                Throwable first = earlierResult.get(5, TimeUnit.SECONDS);
                Throwable second = laterResult.get(2, TimeUnit.SECONDS);
                assertInstanceOf(RejectedExecutionException.class, first);
                assertTrue(second == null || second instanceof RejectedExecutionException,
                        "a save either settles its actual write or reports its own rejected admission");
                assertEquals(0, cache.protocol.pendingWrites());
            } finally {
                releaseEarlier.countDown();
                // Preserve a failing old-path test without leaving its deliberately stranded
                // caller behind in the test process. This is cleanup only, not its assertion.
                var reject = FullLegacyOwnerCache.class.getDeclaredMethod("rejectQueuedWrites", RejectedExecutionException.class);
                reject.setAccessible(true); reject.invoke(cache.protocol, new RejectedExecutionException("test cleanup"));
                earlier.join(5000); later.join(5000);
            }
        } finally { releaseEarlier.countDown(); }
    }
}
