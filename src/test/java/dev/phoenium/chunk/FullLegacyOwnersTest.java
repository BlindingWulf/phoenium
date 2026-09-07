package dev.phoenium.chunk;

import com.mojang.authlib.GameProfileRepository;
import com.mojang.authlib.yggdrasil.response.NameAndId;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;
import net.minecraft.SharedConstants;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.*;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.util.ProblemReporter;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static dev.phoenium.chunk.FullDecisionWitnessSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real approved producer, vanilla cache entries/file, protected saved roots, original reached Owner reader. */
final class FullLegacyOwnersTest {
    @TempDir Path directory;
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
    private GameProfileRepository provider(Function<String, Optional<NameAndId>> query) {
        return (GameProfileRepository) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{GameProfileRepository.class},
                (proxy, method, args) -> query.apply((String) args[0]));
    }
    private static MinecraftServer server(FullLegacyOwnerCacheTest.Cache cache, GameProfileRepository provider) throws Exception {
        return stub(MinecraftServer.class, List.of(
                new Answer(MinecraftServer.class.getMethod("services"), args -> new Services(null, null, provider, cache, null)),
                new Answer(MinecraftServer.class.getMethod("usesAuthentication"), args -> false),
                new Answer(MinecraftServer.class.getMethod("isSingleplayer"), args -> false)));
    }
    private static CompoundTag saved(String owner) { CompoundTag root = new CompoundTag(); root.putString("id", "minecraft:wolf"); root.putString("Owner", owner); return root; }
    private static TagValueInput input(CompoundTag tag) { return (TagValueInput) TagValueInput.create(ProblemReporter.DISCARDING, RegistryAccess.EMPTY, tag); }
    private static void await(FullLegacyOwners.Plan plan) throws Exception {
        CountDownLatch ready = new CountDownLatch(1); plan.onReady(ready::countDown); assertTrue(ready.await(5, TimeUnit.SECONDS)); assertTrue(plan.ready());
    }
    @Test void eagerOccurrencesSettleBeforeClaimAndSkippedEarlierRootDoesNotShiftConsumption() throws Exception {
        UUID identity = UUID.randomUUID(); var queries = new AtomicInteger();
        GameProfileRepository provider = provider(name -> { queries.incrementAndGet(); return Optional.of(new NameAndId(identity, name)); });
        try (var cache = new FullLegacyOwnerCacheTest.Cache(provider, directory.resolve("usercache.json"));
             var f = new FullServiceOperationFixture(server(cache, provider))) {
            var world = f.world(); CompoundTag skipped = saved("Owner"), reached = saved("Owner");
            var source = new FullSavedInput(); var snapshot = source.capture(List.of(skipped, reached));
            var plan = f.service.legacyOwners.prepare(f.stage, snapshot); assertNotNull(plan); await(plan);
            assertTrue(plan.valid(f.stage, snapshot)); assertEquals(2, plan.results().size()); assertEquals(1, queries.get());
            assertEquals(FullLegacyOwnerCache.Persistence.WRITTEN, plan.results().getFirst().persistence().outcome());
            assertEquals(FullLegacyOwnerCache.Persistence.NO_ATTEMPT, plan.results().getLast().persistence().outcome());
            assertTrue(Files.readString(directory.resolve("usercache.json")).contains(identity.toString()));
            Files.writeString(directory.resolve("usercache.json"), "settled-before-body");
            f.full(claim -> { try (var active = plan.enter(claim)) {
                assertEquals(identity, EntityReference.readWithOldOwnerConversion(input(reached), "Owner", world).getUUID());
            } });
            assertEquals("settled-before-body", Files.readString(directory.resolve("usercache.json"))); assertEquals(1, queries.get());
            plan.close(); assertEquals(0L, f.service.legacyOwners.measurements().get("plans"));
        }
    }
    @Test void changedMappingInvalidatesPreparedOccurrenceWhileMruTouchDoesNot() throws Exception {
        GameProfileRepository provider = provider(name -> Optional.of(new NameAndId(UUID.randomUUID(), name)));
        try (var cache = new FullLegacyOwnerCacheTest.Cache(provider, directory.resolve("usercache.json"));
             var f = new FullServiceOperationFixture(server(cache, provider))) {
            var source = new FullSavedInput(); var snapshot = source.capture(List.of(saved("Owner")));
            var plan = f.service.legacyOwners.prepare(f.stage, snapshot); await(plan);
            cache.get("OWNER"); assertTrue(plan.valid(f.stage, snapshot));
            cache.add(new net.minecraft.server.players.NameAndId(UUID.randomUUID(), "owner")); assertFalse(plan.valid(f.stage, snapshot));
            assertFalse(plan.valid(f.stage, new FullSavedInput.Snapshot(snapshot.revision(), snapshot.entities(), snapshot.payloadBytes(), snapshot.unmet())));
            plan.close();
        }
    }
    @Test void genuineFailureRemainsAtItsOriginalReachedEdgeAndUnreachedFailureIsNotThrownEarly() throws Exception {
        var failure = new IllegalStateException("provider original failure"); UUID other = UUID.randomUUID();
        GameProfileRepository provider = provider(name -> { if (name.equals("bad")) throw failure; return Optional.of(new NameAndId(other, name)); });
        try (var cache = new FullLegacyOwnerCacheTest.Cache(provider, directory.resolve("usercache.json"));
             var f = new FullServiceOperationFixture(server(cache, provider))) {
            var world = f.world(); CompoundTag bad = saved("Bad"), good = saved("Good"); var source = new FullSavedInput();
            var snapshot = source.capture(List.of(bad, good)); var plan = f.service.legacyOwners.prepare(f.stage, snapshot); await(plan);
            assertSame(failure, plan.results().getFirst().failure()); assertTrue(plan.valid(f.stage, snapshot));
            f.full(claim -> { try (var active = plan.enter(claim)) {
                assertEquals(other, EntityReference.readWithOldOwnerConversion(input(good), "Owner", world).getUUID());
                assertSame(failure, assertThrows(IllegalStateException.class, () -> EntityReference.readWithOldOwnerConversion(input(bad), "Owner", world)));
            } });
            plan.close();
        }
    }
    @Test void repeatedOriginalOccurrenceFailsTheFinishingBarrierEvenWhenCallerCatchesIt() throws Exception {
        GameProfileRepository provider = provider(name -> Optional.of(new NameAndId(UUID.randomUUID(), name)));
        try (var cache = new FullLegacyOwnerCacheTest.Cache(provider, directory.resolve("usercache.json"));
             var f = new FullServiceOperationFixture(server(cache, provider))) {
            var world = f.world(); CompoundTag root = saved("Owner"); var source = new FullSavedInput(); var snapshot = source.capture(List.of(root));
            var plan = f.service.legacyOwners.prepare(f.stage, snapshot); await(plan);
            assertThrows(ChunkProtocolException.class, () -> f.full(claim -> { try (var active = plan.enter(claim)) {
                EntityReference.readWithOldOwnerConversion(input(root), "Owner", world);
                assertThrows(ChunkProtocolException.class, () -> EntityReference.readWithOldOwnerConversion(input(root), "Owner", world));
            } }));
            plan.close(); assertEquals(0L, f.service.measurements().get("reservations"));
        }
    }
    @Test void shutdownRetainsOnlyOutstandingProducerUntilActualAcknowledgementAndDoesNotWakeRetiredPlan() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1); AtomicInteger wakes = new AtomicInteger();
        UUID id = UUID.randomUUID(); GameProfileRepository provider = provider(name -> {
            entered.countDown(); try { assertTrue(release.await(5, TimeUnit.SECONDS)); } catch (InterruptedException failure) { throw new AssertionError(failure); }
            return Optional.of(new NameAndId(id, name));
        });
        try (var cache = new FullLegacyOwnerCacheTest.Cache(provider, directory.resolve("usercache.json"));
             var f = new FullServiceOperationFixture(server(cache, provider))) {
            var snapshot = new FullSavedInput().capture(List.of(saved("Owner"))); var owners = f.service.legacyOwners;
            var plan = owners.prepare(f.stage, snapshot); plan.onReady(wakes::incrementAndGet); assertTrue(entered.await(5, TimeUnit.SECONDS));
            plan.close(); owners.shutdown(); assertFalse(plan.ready()); assertEquals(1L, owners.measurements().get("plans"));
            assertFalse(owners.termination().toCompletableFuture().isDone()); release.countDown();
            owners.termination().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(0, wakes.get()); assertEquals(0L, owners.measurements().get("plans")); assertEquals(1L, owners.measurements().get("acknowledged"));
            assertEquals(0L, owners.measurements().get("cacheRoots")); assertTrue(Files.readString(directory.resolve("usercache.json")).contains(id.toString()));
        } finally { release.countDown(); }
    }
    @Test void uuidOwnerAndIgnoredConcreteReaderDoNotManufactureLegacyOccurrences() throws Exception {
        try (var f = new FullServiceOperationFixture()) {
            var world = f.world(); UUID id = UUID.randomUUID(); CompoundTag root = saved("unused"); root.putIntArray("Owner", UUIDUtil.uuidToIntArray(id));
            CompoundTag ignored = saved("Ignored"); ignored.putString("id", "minecraft:fishing_bobber");
            var snapshot = new FullSavedInput().capture(List.of(root, ignored)); var plan = f.service.legacyOwners.prepare(f.stage, snapshot);
            assertTrue(plan.ready()); assertTrue(plan.results().isEmpty());
            f.full(claim -> { try (var active = plan.enter(claim)) { assertEquals(id, EntityReference.readWithOldOwnerConversion(input(root), "Owner", world).getUUID()); } });
            plan.close();
        }
    }
}
