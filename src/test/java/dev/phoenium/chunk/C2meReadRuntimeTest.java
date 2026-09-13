package dev.phoenium.chunk;

import com.ishland.c2me.rewrites.chunksystem.common.ChunkLoadingContext;
import com.ishland.c2me.rewrites.chunksystem.common.TheChunkSystem;
import com.ishland.flowsched.scheduler.ItemHolder;
import dev.phoenium.mixin.ChunkMapLoadAccess;
import dev.phoenium.core.WorldId;
import dev.phoenium.topology.RoutingKey;
import java.lang.reflect.Method;
import java.util.List;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static dev.phoenium.chunk.FullDecisionWitnessSupport.*;
import static org.junit.jupiter.api.Assertions.*;

final class C2meReadRuntimeTest {
    private FullRegistrationFixture fixture;
    private MinecraftLightReadFixture readFixture;
    private ChunkBindingRuntime runtime;

    @BeforeAll static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @AfterEach void tearDown() {
        if (runtime != null) runtime.shutdown();
        if (readFixture != null) readFixture.cleanup();
        if (fixture != null) fixture.stop();
    }

    @Test void diskStatusSlotReplacementIsVisibleToTheReadAdapter() throws Exception {
        fixture = new FullRegistrationFixture(true);
        readFixture = new MinecraftLightReadFixture(false, false);
        readFixture.prepare(List.of());
        var position = readFixture.position;
        var holder = fixture.holder(position);
        Method level = ChunkMapLoadAccess.class.getMethod("phoenium$level");
        Method poiAccess = ChunkMapLoadAccess.class.getMethod("phoenium$poi");
        ChunkMap map = stub(ChunkMap.class, List.of(
                new Answer(level, ignored -> readFixture.level),
                new Answer(poiAccess, ignored -> readFixture.manager)), ChunkMapLoadAccess.class);
        ItemHolder backing = new ItemHolder(position, null);
        ChunkLoadingContext context = new ChunkLoadingContext(backing, map,
                new TheChunkSystem(new ItemHolder(position, holder)));

        C2meReadAccess.Observation first = C2meReadAccess.capture(context, position);
        backing.replaceSlot(com.ishland.c2me.rewrites.chunksystem.common.NewChunkStatus.DISK,
                new java.util.concurrent.CompletableFuture<>());
        assertFalse(first.stillCurrent());
        C2meReadAccess.Observation replacement = C2meReadAccess.capture(context, position);
        assertNotSame(first.statusSlot(), replacement.statusSlot());
        assertTrue(replacement.stillCurrent());
    }

    @Test void privateAsyncReadAcceptsOnlyItsExactScalableLuxReceiptAndExpiresOnReturn() throws Throwable {
        PrivateReadFixture f = privateReadFixture();
        try (PrivateC2meReadInvocation invocation = f.open()) {
            assertSame(invocation, PrivateC2meReadInvocation.current(f.position));
            invocation.requireRead(f.data, f.level);
            var occurrence = f.services.light().snapshot(f.batch).orElseThrow().occurrences().getFirst().id();
            f.services.light().invokePrivateC2meRead(invocation, occurrence,
                    insertion -> ScalableLuxLightBridge.submit(f.engine, insertion,
                            () -> f.engine.retainData(f.position, true)));
            invocation.readReturned();
            assertEquals(1, f.engine.retainCalls);
            var receipt = f.services.light().receipt(f.batch).orElseThrow();
            assertTrue(f.services.light().validates(receipt, f.batch));
            assertTrue(dev.phoenium.ownership.OwnershipContext.current().isEmpty(),
                    "private persisted read must never manufacture regional authority");
        }
        assertNull(PrivateC2meReadInvocation.current(f.position));
        assertEquals(0, PrivateC2meReadInvocation.retainedEntries());
        f.cleanup();
    }

    @Test void privateAsyncReadRejectsCrossThreadUseAndUseAfterClose() throws Exception {
        PrivateReadFixture f = privateReadFixture();
        PrivateC2meReadInvocation invocation = f.open();
        var foreignFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread foreign = Thread.ofPlatform().start(() -> {
            try { invocation.requireRead(f.data, f.level); }
            catch (Throwable failure) { foreignFailure.set(failure); }
        });
        foreign.join();
        assertInstanceOf(ChunkProtocolException.class, foreignFailure.get());
        invocation.close();
        assertThrows(ChunkProtocolException.class, () -> invocation.requireRead(f.data, f.level));
        assertEquals(0, PrivateC2meReadInvocation.retainedEntries());
        f.cleanup();
    }

    @Test void privateAsyncReadFailsClosedWhenDiskSlotOrWorkBecomesStale() throws Exception {
        PrivateReadFixture slot = privateReadFixture();
        try (PrivateC2meReadInvocation invocation = slot.open()) {
            slot.backing.replaceSlot(com.ishland.c2me.rewrites.chunksystem.common.NewChunkStatus.DISK,
                    new java.util.concurrent.CompletableFuture<>());
            assertThrows(ChunkProtocolException.class, () -> invocation.requireRead(slot.data, slot.level));
        }
        slot.cleanup();

        PrivateReadFixture work = privateReadFixture();
        try (PrivateC2meReadInvocation invocation = work.open()) {
            work.services.works().retireWork(work.work, true);
            assertThrows(ChunkProtocolException.class, () -> invocation.requireRead(work.data, work.level));
        }
        work.cleanup();
    }

    @Test void privateAsyncLightCallThatIsNeitherInsertedNorDelegatedFailsTheBatch() throws Throwable {
        PrivateReadFixture f = privateReadFixture();
        try (PrivateC2meReadInvocation invocation = f.open()) {
            var occurrence = f.services.light().snapshot(f.batch).orElseThrow().occurrences().getFirst().id();
            var failure = assertThrows(ChunkProtocolException.class,
                    () -> f.services.light().invokePrivateC2meRead(invocation, occurrence, insertion -> { }));
            assertTrue(failure.getMessage().contains("insertion") || failure.getMessage().contains("ownership"));
            assertEquals(LightInputService.State.FAILED_BARRIER,
                    f.services.light().snapshot(f.batch).orElseThrow().occurrences().getFirst().state());
        }
        f.cleanup();
    }

    @Test void unboundAsyncFinalPairAllowsOnlyAnEmptyProviderQueue() {
        var queue = new it.unimi.dsi.fastutil.objects.ReferenceArrayList<Runnable>();
        var carrier = new C2meReadAccess.ProviderPair(new Object(), null, queue);
        assertDoesNotThrow(() -> C2meReadAccess.requireEmptyProviderQueue(carrier));
        queue.add(() -> { });
        var failure = assertThrows(ChunkProtocolException.class,
                () -> C2meReadAccess.requireEmptyProviderQueue(carrier));
        assertTrue(failure.getMessage().contains("deferred work"));
    }

    private PrivateReadFixture privateReadFixture() throws Exception {
        readFixture = new MinecraftLightReadFixture(false, false);
        readFixture.prepare(List.of());
        var engine = MinecraftLightReadFixture.allocate(
                ca.spottedleaf.starlight.common.light.vanillainterface.ThreadedLevelLightEngineVanillaInterface.class);
        assertTrue(ScalableLuxLightBridge.ownsPersistedReload(engine));

        ServerChunkCache cache = stub(ServerChunkCache.class, List.of(
                new Answer(ServerChunkCache.class.getMethod("getLightEngine"), ignored -> engine)));
        ServerLevel level = stub(ServerLevel.class, List.of(
                new Answer(ServerLevel.class.getMethod("dimensionType"), ignored -> readFixture.level.dimensionType()),
                new Answer(ServerLevel.class.getMethod("getChunkSource"), ignored -> cache),
                new Answer(ServerLevel.class.getMethod("palettedContainerFactory"), ignored -> readFixture.factory),
                new Answer(ServerLevel.class.getMethod("registryAccess"), ignored -> readFixture.registries),
                new Answer(ServerLevel.class.getMethod("getServer"), ignored -> readFixture.level.getServer()),
                new Answer(ServerLevel.class.getMethod("getSeed"), ignored -> 0L)));
        ChunkMap map = stub(ChunkMap.class, List.of(
                new Answer(ChunkMapLoadAccess.class.getMethod("phoenium$level"), ignored -> level),
                new Answer(ChunkMapLoadAccess.class.getMethod("phoenium$poi"), ignored -> readFixture.manager)),
                ChunkMapLoadAccess.class);
        var position = readFixture.position;
        var holder = new FullRegistrationFixture.Holder(position);
        ItemHolder backing = new ItemHolder(position, null);
        ChunkLoadingContext context = new ChunkLoadingContext(backing, map,
                new TheChunkSystem(new ItemHolder(position, holder)));
        C2meReadAccess.Observation observation = C2meReadAccess.capture(context, position);

        WorldId world = new WorldId("c2me-private-read");
        ChunkPrerequisiteServices services = new ChunkPrerequisiteServices(world, "c2me-private-read", 1);
        var lifecycle = services.lifecycles().activateHolder(1, new RoutingKey(world, position.x(), position.z()), holder);
        var work = services.works().beginOrResolveSharedWork(lifecycle,
                new ChunkWorkRegistry.WorkReservation("c2me-test", "DISK", 0));
        var stage = new ChunkCustodyModel.StageId(work, ChunkStageKind.PARSED_APPLY, "C2ME_TEST", 0);
        var batch = new LightInputService.BatchId(lifecycle, work, stage, 0);
        assertTrue(services.light().register(batch, true, List.of()));
        return new PrivateReadFixture(position, holder, backing, observation, services, lifecycle, work,
                batch, readFixture.data, level, engine);
    }

    private record PrivateReadFixture(net.minecraft.world.level.ChunkPos position,
                                      FullRegistrationFixture.Holder holder,
                                      ItemHolder backing,
                                      C2meReadAccess.Observation observation,
                                      ChunkPrerequisiteServices services,
                                      ChunkCustodyModel.LifecycleId lifecycle,
                                      ChunkCustodyModel.WorkId work,
                                      LightInputService.BatchId batch,
                                      net.minecraft.world.level.chunk.storage.SerializableChunkData data,
                                      ServerLevel level,
                                      ca.spottedleaf.starlight.common.light.vanillainterface.ThreadedLevelLightEngineVanillaInterface engine) {
        PrivateC2meReadInvocation open() {
            return new PrivateC2meReadInvocation(observation, services, lifecycle, work, position, data, batch, level);
        }
        void cleanup() {
            try { services.light().forget(batch); } catch (Throwable ignored) { }
            try {
                if (services.works().isCurrentWork(work)) services.works().retireWork(work, true);
                services.works().releaseRetiredWork(work);
            } catch (Throwable ignored) { }
        }
    }

    private record Answer(Method method, java.util.function.Function<Object[], Object> body) { }
    private static <T> T stub(Class<T> parent, List<Answer> answers, Class<?>... interfaces) throws Exception {
        return FullDecisionWitnessSupport.stub(parent, answers.stream().map(answer ->
                new FullDecisionWitnessSupport.Answer(answer.method(), answer.body())).toList(), interfaces);
    }
}
