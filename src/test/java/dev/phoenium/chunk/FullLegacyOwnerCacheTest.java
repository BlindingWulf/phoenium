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
}
