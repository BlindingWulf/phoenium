package dev.phoenium.chunk;

import com.ishland.c2me.rewrites.chunksystem.common.ChunkLoadingContext;
import com.ishland.c2me.rewrites.chunksystem.common.ChunkState;
import com.ishland.c2me.rewrites.chunksystem.common.Config;
import com.ishland.c2me.rewrites.chunksystem.common.NewChunkStatus;
import com.ishland.c2me.rewrites.chunksystem.common.TheChunkSystem;
import com.ishland.flowsched.scheduler.ItemHolder;
import dev.phoenium.core.OwnerEpoch;
import dev.phoenium.core.TopologyGeneration;
import dev.phoenium.mixin.ChunkMapLoadAccess;
import dev.phoenium.ownership.OwnershipContext;
import dev.phoenium.topology.RegionRoute;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.storage.PrimaryLevelData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static dev.phoenium.chunk.FullDecisionWitnessSupport.*;
import static org.junit.jupiter.api.Assertions.*;

final class C2meFullRuntimeTest {
    private FullRegistrationFixture fixture;
    private ChunkBindingRuntime runtime;
    private ChunkMap map;
    private ItemHolder backing;
    private ChunkLoadingContext context;
    private FullRegistrationFixture.Holder holder;
    private ProtoChunk proto;
    private LevelChunk full;
    private C2mePublicationView.PublishedHolders published;
    private final AtomicReference<Throwable> barrier = new AtomicReference<>();

    @BeforeAll static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @BeforeEach void setUp() throws Exception {
        Config.delayFullChunkEvents = false;
        fixture = new FullRegistrationFixture(true);
        var data = new PrimaryLevelData(new LevelSettings("c2me-full", GameType.SURVIVAL,
                LevelSettings.DifficultySettings.DEFAULT, true, WorldDataConfiguration.DEFAULT),
                PrimaryLevelData.SpecialWorldProperty.NONE, com.mojang.serialization.Lifecycle.stable());
        set(fixture.world.level, Level.class, "levelData", data);
        set(fixture.world.level, ServerLevel.class, "serverLevelData", data);
        set(fixture.world.level, ServerLevel.class, "environmentAttributes", EnvironmentAttributeSystem.builder().build());
        PoiManager poi = allocate(PoiManager.class);
        published = new C2mePublicationView.PublishedHolders();
        Method level = ChunkMapLoadAccess.class.getMethod("phoenium$level");
        Method poiAccess = ChunkMapLoadAccess.class.getMethod("phoenium$poi");
        Method current = C2mePublicationView.class.getMethod("getCurrentChunkHolders");
        map = stub(ChunkMap.class, List.of(
                new Answer(level, ignored -> fixture.world.level),
                new Answer(poiAccess, ignored -> poi),
                new Answer(current, ignored -> published)), ChunkMapLoadAccess.class, C2mePublicationView.class);

        runtime = new ChunkBindingRuntime(new ChunkBindingRuntime.Authority() {
            @Override public ChunkBindingRuntime.WorldAuthority requireChunkWorld(ServerLevel level) {
                assertSame(fixture.world.level, level);
                return fixture.authority;
            }
            @Override public void chunkBarrier(Throwable failure) { barrier.compareAndSet(null, failure); }
        }, LoggerFactory.getLogger("C2meFullRuntimeTest"));

        holder = fixture.holder(ChunkPos.ZERO);
        proto = fixture.input(ChunkPos.ZERO);
        full = allocate(LevelChunk.class);
        backing = new ItemHolder(ChunkPos.ZERO, holder);
        backing.setItem(new ChunkState(proto, proto, ChunkStatus.SPAWN, false));
        context = new ChunkLoadingContext(backing, map, new TheChunkSystem(backing));
    }

    @AfterEach void tearDown() {
        Config.delayFullChunkEvents = false;
        if (runtime != null) runtime.shutdown();
        if (fixture != null) {
            var server = fixture.world.level.getServer();
            try { fixture.stop(); }
            finally { FullFinishingServices.stopped(server); }
        }
    }

    @Test void fullProviderRunnableRunsInsideOwnerAndFullServiceThenAcknowledgesPublication() {
        AtomicInteger calls = new AtomicInteger();
        Runnable wrapped = runtime.c2meFullRunnable(context, () -> {
            assertTrue(OwnershipContext.current().isPresent());
            assertTrue(FullFinishingServices.finishing());
            calls.incrementAndGet();
            backing.setItem(new ChunkState(full, proto, ChunkStatus.FULL, false));
            published.put(ChunkPos.ZERO.pack(), holder);
        });

        assertEquals(0, calls.get());
        wrapped.run();

        assertEquals(1, calls.get());
        assertEquals(0, runtime.c2meRetainedGraphs());
        assertNull(barrier.get());
        assertTrue(OwnershipContext.current().isEmpty());
        assertFalse(FullFinishingServices.finishing());
    }

    @Test void capturedProviderSlotReplacementFailsAsSupersededBeforeMutation() {
        AtomicInteger calls = new AtomicInteger();
        Runnable wrapped = runtime.c2meFullRunnable(context, calls::incrementAndGet);
        backing.replaceSlot(NewChunkStatus.SERVER_ACCESSIBLE, new CompletableFuture<>());

        C2meProviderControlException failure = assertThrows(C2meProviderControlException.class, wrapped::run);

        assertEquals(ChunkStageAdmission.Result.SUPERSEDED, failure.reason());
        assertEquals(0, calls.get());
        assertEquals(0, runtime.c2meRetainedGraphs());
        assertNull(barrier.get());
    }

    @Test void providerFailurePropagatesExactThrowableAndCleansGraph() {
        RuntimeException marker = new RuntimeException("C2ME FULL marker");
        Runnable wrapped = runtime.c2meFullRunnable(context, () -> { throw marker; });

        RuntimeException failure = assertThrows(RuntimeException.class, wrapped::run);

        assertSame(marker, failure);
        assertEquals(0, runtime.c2meRetainedGraphs());
        assertNull(barrier.get());
    }

    @Test void missingCurrentHolderPublicationFailsClosedAfterFullBody() {
        Runnable wrapped = runtime.c2meFullRunnable(context,
                () -> backing.setItem(new ChunkState(full, proto, ChunkStatus.FULL, false)));

        assertThrows(ChunkProtocolException.class, wrapped::run);

        assertEquals(0, runtime.c2meRetainedGraphs());
        assertTrue(fixture.barrier.record().isPresent(), "publication identity mismatch must trip the admission barrier");
    }

    @Test void delayedEventsRetainExactFullGraphUntilSecondProviderRunnablePublishes() {
        Config.delayFullChunkEvents = true;
        AtomicInteger fullCalls = new AtomicInteger(), eventCalls = new AtomicInteger();
        Runnable fullWrapped = runtime.c2meFullRunnable(context, () -> {
            fullCalls.incrementAndGet();
            backing.setItem(new ChunkState(full, proto, ChunkStatus.FULL, false));
        });
        fullWrapped.run();

        assertEquals(1, fullCalls.get());
        assertEquals(1, runtime.c2meRetainedGraphs());
        Runnable events = runtime.c2meDelayedFullEventsRunnable(context, () -> {
            assertTrue(OwnershipContext.current().isPresent());
            assertTrue(FullFinishingServices.finishing());
            eventCalls.incrementAndGet();
            published.put(ChunkPos.ZERO.pack(), holder);
        });
        events.run();

        assertEquals(1, eventCalls.get());
        assertEquals(0, runtime.c2meRetainedGraphs());
        assertNull(barrier.get());
    }

    @Test void sendReadyUsesSeparateProviderStatusSlotAndOwnerClaim() {
        makePublishedFull();
        AtomicInteger calls = new AtomicInteger();
        Runnable send = runtime.c2meSendReadyRunnable(context, () -> {
            assertTrue(OwnershipContext.current().isPresent());
            calls.incrementAndGet();
        });

        send.run();

        assertEquals(1, calls.get());
        assertNull(barrier.get());
    }

    @Test void sendReadySlotReplacementSupersedesWithoutCallingProviderBody() {
        makePublishedFull();
        AtomicInteger calls = new AtomicInteger();
        Runnable send = runtime.c2meSendReadyRunnable(context, calls::incrementAndGet);
        backing.replaceSlot(NewChunkStatus.SERVER_ACCESSIBLE_CHUNK_SENDING, new CompletableFuture<>());

        C2meProviderControlException failure = assertThrows(C2meProviderControlException.class, send::run);

        assertEquals(ChunkStageAdmission.Result.SUPERSEDED, failure.reason());
        assertEquals(0, calls.get());
        assertNull(barrier.get());
    }

    @Test void wrapperCapturedBeforeShutdownRetainsNoGraphAndFailsShutdownWhenInvokedLater() {
        AtomicInteger calls = new AtomicInteger();
        Runnable wrapped = runtime.c2meFullRunnable(context, calls::incrementAndGet);
        runtime.shutdown();

        C2meProviderControlException failure = assertThrows(C2meProviderControlException.class, wrapped::run);

        assertEquals(ChunkStageAdmission.Result.SHUTDOWN, failure.reason());
        assertEquals(0, calls.get());
        assertEquals(0, runtime.c2meRetainedGraphs());
        runtime = null;
    }

    @Test void currentTopologyRouteIsUsedWhenProviderRunnableFinallyExecutes() {
        Runnable wrapped = runtime.c2meFullRunnable(context, () -> {
            assertEquals(fixture.otherId, OwnershipContext.current().orElseThrow().region());
            backing.setItem(new ChunkState(full, proto, ChunkStatus.FULL, false));
            published.put(ChunkPos.ZERO.pack(), holder);
        });
        TopologyGeneration generation = new TopologyGeneration(2);
        fixture.directory.publish(generation, Map.of(), Map.of(fixture.worldId,
                new RegionRoute(fixture.otherId, generation, new OwnerEpoch(0))));

        wrapped.run();

        assertEquals(0, runtime.c2meRetainedGraphs());
        assertNull(barrier.get());
    }

    private void makePublishedFull() {
        backing.setItem(new ChunkState(full, proto, ChunkStatus.FULL, false));
        published.put(ChunkPos.ZERO.pack(), holder);
    }
}
