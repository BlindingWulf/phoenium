package dev.phoenium.chunk;

import com.mojang.authlib.GameProfileRepository;
import com.mojang.authlib.ProfileLookupCallback;
import com.mojang.authlib.yggdrasil.response.NameAndId;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.Services;
import net.minecraft.server.players.CachedUserNameToIdResolver;
import net.minecraft.server.players.OldUsersConverter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static dev.phoenium.chunk.FullDecisionWitnessSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real cache/profile-conversion/file methods, only controlled repositories and temporary files. */
final class FullLegacyOwnerDecisionTest {
    @TempDir Path directory;

    @Test void hitTouchesMruWithoutProviderOrAnotherFileWrite() throws Exception {
        AtomicInteger queries = new AtomicInteger(); UUID id = UUID.randomUUID();
        var cache = cache(name -> { queries.incrementAndGet(); return Optional.of(new NameAndId(id, name)); });
        assertEquals(id, cache.get("OldOwner").orElseThrow().id());
        Path file = directory.resolve("usercache.json"); Files.writeString(file, "write sentinel");
        assertEquals(id, cache.get("OLDOWNER").orElseThrow().id());
        assertEquals(1, queries.get()); assertEquals("write sentinel", Files.readString(file));
        assertEquals(2L, ((java.util.concurrent.atomic.AtomicLong)field(cache, CachedUserNameToIdResolver.class, "operationCount")).get());
    }

    @Test void missResolvesLowercaseThenCommitsMemoryAndFileBeforeReturningIdentity() throws Exception {
        UUID id = UUID.randomUUID(); var cache = cache(name -> { assertEquals("oldowner", name); return Optional.of(new NameAndId(id, name)); });
        assertEquals(id, OldUsersConverter.convertMobOwnerIfNecessary(server(cache, false), "OldOwner"));
        assertEquals(id, cache.get(id).orElseThrow().id());
        assertTrue(Files.readString(directory.resolve("usercache.json")).contains(id.toString()));
    }

    @Test void invalidPlayerNameUsesLocalOfflineIdentityWithoutProvider() throws Exception {
        var cache = cache(name -> { throw new AssertionError("invalid name has no profile lookup"); });
        var result = cache.get("Bad Name").orElseThrow();
        assertEquals(net.minecraft.core.UUIDUtil.createOfflinePlayerUUID("bad name"), result.id());
        assertTrue(Files.exists(directory.resolve("usercache.json")));
    }

    @Test void genuineEmptyResolutionDoesNotInventAnAuthenticatedOwnerOrPersistence() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        GameProfileRepository repository = repository(name -> { calls.incrementAndGet(); return Optional.empty(); }, false);
        var cache = new CachedUserNameToIdResolver(repository, directory.resolve("usercache.json").toFile());
        cache.resolveOfflineUsers(false);
        assertNull(OldUsersConverter.convertMobOwnerIfNecessary(server(cache, repository, true), "OldOwner"));
        assertEquals(1, calls.get()); // The separate bulk callback below is a controlled genuine miss too.
        assertFalse(Files.exists(directory.resolve("usercache.json")));
    }

    @Test void authenticatedFallbackUsesOriginalCaseBulkLookupAndCachesItsSuccess() throws Exception {
        UUID id = UUID.randomUUID(); AtomicInteger single = new AtomicInteger(), bulk = new AtomicInteger();
        GameProfileRepository repository = (GameProfileRepository) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{GameProfileRepository.class}, (proxy, method, args) -> {
                    if (method.getName().equals("findProfileByName")) { single.incrementAndGet(); return Optional.empty(); }
                    assertEquals("findProfilesByNames", method.getName()); bulk.incrementAndGet();
                    assertArrayEquals(new String[]{"OldOwner"}, (String[])args[0]);
                    ((ProfileLookupCallback)args[1]).onProfileLookupSucceeded("OldOwner", id); return null;
                });
        var cache = new CachedUserNameToIdResolver(repository, directory.resolve("usercache.json").toFile());
        cache.resolveOfflineUsers(false);
        assertEquals(id, OldUsersConverter.convertMobOwnerIfNecessary(server(cache, repository, true), "OldOwner"));
        assertEquals(1, single.get()); assertEquals(1, bulk.get());
        assertTrue(Files.readString(directory.resolve("usercache.json")).contains(id.toString()));
    }

    @Test void providerRuntimeFailurePropagatesAndIsNotOfflineFallback() throws Exception {
        var failure = new IllegalStateException("controlled provider failure");
        var cache = cache(name -> { throw failure; });
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> OldUsersConverter.convertMobOwnerIfNecessary(server(cache, false), "OldOwner")));
        assertFalse(Files.exists(directory.resolve("usercache.json")));
    }

    @Test void offlineConverterFallbackPreservesOriginalCaseAfterEmptyCacheResult() throws Exception {
        var cache = cache(name -> Optional.empty()); cache.resolveOfflineUsers(false);
        assertEquals(net.minecraft.core.UUIDUtil.createOfflinePlayerUUID("OldOwner"),
                OldUsersConverter.convertMobOwnerIfNecessary(server(cache, false), "OldOwner"));
        assertFalse(Files.exists(directory.resolve("usercache.json")), "converter fallback itself does not add to cache");
    }

    @Test void physicalIoFailureIsCaughtByVanillaWhileResolvedIdentityAndMemorySurvive() throws Exception {
        UUID id = UUID.randomUUID(); Files.createDirectory(directory.resolve("usercache.json"));
        var cache = cache(name -> Optional.of(new NameAndId(id, name)));
        assertEquals(id, OldUsersConverter.convertMobOwnerIfNecessary(server(cache, false), "OldOwner"));
        assertTrue(Files.isDirectory(directory.resolve("usercache.json")), "a failed write is not a successful file");
        assertEquals(id, cache.get(id).orElseThrow().id());
    }

    @Test void saveRuntimeFailureIsNotCaughtAndKeepsTheMemoryPrefix() throws Exception {
        var failure = new IllegalStateException("controlled serialization boundary"); UUID id = UUID.randomUUID();
        class FailingSave extends CachedUserNameToIdResolver {
            FailingSave() { super(repository(name -> Optional.of(new NameAndId(id, name)), false), directory.resolve("usercache.json").toFile()); }
            @Override public void save() { throw failure; }
        }
        var cache = new FailingSave();
        assertSame(failure, assertThrows(IllegalStateException.class, () -> cache.get("OldOwner")));
        assertEquals(id, cache.get(id).orElseThrow().id()); assertFalse(Files.exists(directory.resolve("usercache.json")));
    }

    @Test void expiredEntryIsRemovedAndPersistedEvenWhenResolutionReturnsEmpty() throws Exception {
        UUID id = UUID.randomUUID(); Path file = directory.resolve("usercache.json");
        Files.writeString(file, "[{\"name\":\"oldowner\",\"uuid\":\"" + id + "\",\"expiresOn\":\"2000-01-01 00:00:00 +0000\"}]");
        var cache = cache(name -> Optional.empty()); cache.resolveOfflineUsers(false);
        assertTrue(cache.get("OldOwner").isEmpty()); assertTrue(cache.get(id).isEmpty());
        assertEquals("[]", Files.readString(file));
    }

    @Test void duplicateLogicalRequestSharesPreparationAndStaleWorkCannotUndoEarlyEffects() throws Exception {
        UUID id = UUID.randomUUID(); AtomicInteger queries = new AtomicInteger(), applies = new AtomicInteger();
        var cache = cache(name -> { queries.incrementAndGet(); return Optional.of(new NameAndId(id, name)); });
        try (var prep = new PreparationProbe(server(cache, false))) {
            var first = prep.request("work-1/Owner", "OldOwner");
            assertSame(first, prep.request("work-1/Owner", "OldOwner"));
            assertEquals(id, first.get(5, TimeUnit.SECONDS));
            prep.retire();
            if (prep.current) applies.incrementAndGet();
            assertEquals(0, applies.get()); assertEquals(1, queries.get());
            assertTrue(Files.readString(directory.resolve("usercache.json")).contains(id.toString()),
                    "counterexample: speculative preparation is externally visible even though FULL never invokes");
        }
    }

    @Test void shutdownDuringProfilePreparationDoesNotHoldCacheAccessOrApplyLateResult() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        UUID id = UUID.randomUUID(); var cache = cache(name -> { entered.countDown(); await(release); return Optional.of(new NameAndId(id, name)); });
        cache.add(new net.minecraft.server.players.NameAndId(UUID.randomUUID(), "cached"));
        try (var prep = new PreparationProbe(server(cache, false))) {
            var result = prep.request("work/Owner", "OldOwner"); assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertTrue(cache.get("cached").isPresent(), "no cache turn is held over the controlled network wait");
            prep.retire(); release.countDown(); assertEquals(id, result.get(5, TimeUnit.SECONDS));
            assertFalse(prep.current); assertTrue(Files.exists(directory.resolve("usercache.json")));
            assertThrows(IllegalStateException.class, () -> prep.request("new", "NewOwner"));
        } finally { release.countDown(); }
    }

    @Test void shutdownWithPersistenceOutstandingWaitsForAttemptReceiptWithoutInventingSuccess() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1); UUID id = UUID.randomUUID();
        class DelayedSave extends CachedUserNameToIdResolver {
            DelayedSave() { super(repository(name -> Optional.of(new NameAndId(id, name)), false), directory.resolve("usercache.json").toFile()); }
            @Override public void save() { entered.countDown(); await(release); super.save(); }
        }
        var cache = new DelayedSave();
        try (var prep = new PreparationProbe(server(cache, false))) {
            var result = prep.request("work/Owner", "OldOwner"); assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(id, cache.get(id).orElseThrow().id()); assertFalse(result.isDone());
            prep.retire(); assertFalse(Files.exists(directory.resolve("usercache.json")));
            release.countDown(); assertEquals(id, result.get(5, TimeUnit.SECONDS));
            assertTrue(Files.readString(directory.resolve("usercache.json")).contains(id.toString())); assertFalse(prep.current);
        } finally { release.countDown(); }
    }

    private CachedUserNameToIdResolver cache(Function<String, Optional<NameAndId>> provider) {
        return new CachedUserNameToIdResolver(repository(provider, false), directory.resolve("usercache.json").toFile());
    }
    private static GameProfileRepository repository(Function<String, Optional<NameAndId>> single, boolean unused) {
        return (GameProfileRepository) Proxy.newProxyInstance(FullLegacyOwnerDecisionTest.class.getClassLoader(),
                new Class<?>[]{GameProfileRepository.class}, (proxy, method, args) -> {
                    if (method.getName().equals("findProfileByName")) return single.apply((String)args[0]);
                    assertEquals("findProfilesByNames", method.getName());
                    for (String name : (String[]) args[0]) ((ProfileLookupCallback)args[1]).onProfileLookupFailed(name, new IllegalStateException("controlled no profile"));
                    return null;
                });
    }
    private static MinecraftServer server(CachedUserNameToIdResolver cache, boolean auth) throws Exception {
        return server(cache, (GameProfileRepository)field(cache, CachedUserNameToIdResolver.class, "profileRepository"), auth);
    }
    private static MinecraftServer server(CachedUserNameToIdResolver cache, GameProfileRepository repository, boolean auth) throws Exception {
        return stub(MinecraftServer.class, List.of(
                new Answer(MinecraftServer.class.getMethod("services"), args -> new Services(null, null, repository, cache, null)),
                new Answer(MinecraftServer.class.getMethod("usesAuthentication"), args -> auth),
                new Answer(MinecraftServer.class.getMethod("isSingleplayer"), args -> false)));
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("bounded witness did not release endpoint"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
    /** Isolated eager-preparation counterexample, not the proposed production cache coordinator. */
    private static final class PreparationProbe implements AutoCloseable {
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        final java.util.Map<String, CompletableFuture<UUID>> requests = new java.util.HashMap<>();
        final MinecraftServer server; boolean current = true;
        PreparationProbe(MinecraftServer server) { this.server = server; }
        CompletableFuture<UUID> request(String occurrence, String name) {
            if (!current) throw new IllegalStateException("retired");
            return requests.computeIfAbsent(occurrence, ignored -> CompletableFuture.supplyAsync(
                    () -> OldUsersConverter.convertMobOwnerIfNecessary(server, name), executor));
        }
        void retire() { current = false; }
        public void close() throws InterruptedException {
            executor.shutdown(); assertTrue(executor.awaitTermination(6, TimeUnit.SECONDS)); requests.clear();
        }
    }
}
