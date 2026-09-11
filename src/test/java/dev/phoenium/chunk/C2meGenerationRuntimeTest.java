package dev.phoenium.chunk;

import com.ishland.c2me.rewrites.chunksystem.common.ChunkLoadingContext;
import com.ishland.c2me.rewrites.chunksystem.common.TheChunkSystem;
import com.ishland.c2me.rewrites.chunksystem.common.statuses.VanillaWorldGenerationDelegate;
import com.ishland.flowsched.scheduler.ItemHolder;
import dev.phoenium.core.OwnerEpoch;
import dev.phoenium.core.TopologyGeneration;
import dev.phoenium.mixin.ChunkMapLoadAccess;
import dev.phoenium.topology.RegionRoute;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkPyramid;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static dev.phoenium.chunk.FullDecisionWitnessSupport.*;
import static org.junit.jupiter.api.Assertions.*;

final class C2meGenerationRuntimeTest {
    private FullRegistrationFixture fixture;
    private ChunkBindingRuntime runtime;
    private ChunkMap map;
    private ItemHolder backing;
    private ItemHolder centerBacking;
    private ChunkLoadingContext context;
    private VanillaWorldGenerationDelegate delegate;
    private FullRegistrationFixture.Holder holder;
    private ChunkAccess input;
    private ChunkStep step;
    private final AtomicReference<Throwable> barrier = new AtomicReference<>();

    @BeforeAll static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @BeforeEach void setUp() throws Exception {
        fixture = new FullRegistrationFixture(true);
        PoiManager poi = allocate(PoiManager.class);
        Method level = ChunkMapLoadAccess.class.getMethod("phoenium$level");
        Method poiAccess = ChunkMapLoadAccess.class.getMethod("phoenium$poi");
        map = stub(ChunkMap.class, List.of(
                new Answer(level, ignored -> fixture.world.level),
                new Answer(poiAccess, ignored -> poi)), ChunkMapLoadAccess.class);

        runtime = new ChunkBindingRuntime(new ChunkBindingRuntime.Authority() {
            @Override public ChunkBindingRuntime.WorldAuthority requireChunkWorld(ServerLevel level) {
                assertSame(fixture.world.level, level);
                return fixture.authority;
            }
            @Override public void chunkBarrier(Throwable failure) { barrier.compareAndSet(null, failure); }
        }, LoggerFactory.getLogger("C2meGenerationRuntimeTest"));

        ChunkPos position = ChunkPos.ZERO;
        holder = fixture.holder(position);
        input = fixture.input(position);
        ChunkStatus status = ChunkStatus.BIOMES;
        step = ChunkPyramid.GENERATION_PYRAMID.getStepTo(status);
        backing = new ItemHolder(position, null);
        centerBacking = new ItemHolder(position, holder);
        context = new ChunkLoadingContext(backing, map, new TheChunkSystem(centerBacking));
        delegate = new VanillaWorldGenerationDelegate(status);
    }

    @AfterEach void tearDown() {
        if (runtime != null) runtime.shutdown();
        if (fixture != null) fixture.stop();
    }

    @Test void stableSlotSharesOneWorkAndWaitsForOwnerAdmission() {
        AtomicInteger producerCalls = new AtomicInteger();
        CompletableFuture<ChunkAccess> producer = new CompletableFuture<>();
        CompletableFuture<ChunkAccess> first = runtime.c2meGeneration(delegate, context, step, input,
                () -> { producerCalls.incrementAndGet(); return producer; });
        CompletableFuture<ChunkAccess> duplicate = runtime.c2meGeneration(delegate, context, step, input,
                () -> { producerCalls.incrementAndGet(); return new CompletableFuture<>(); });

        assertSame(first, duplicate);
        assertEquals(1, producerCalls.get());
        producer.complete(input);
        assertFalse(first.isDone(), "provider-facing completion must wait for owner admission");
        assertEquals(1, runtime.c2meRetainedGraphs());

        assertTrue(runtime.pumpOne());
        assertSame(input, first.join());
        assertEquals(0, runtime.c2meRetainedGraphs());
        assertNull(barrier.get());
    }

    @Test void alreadyCompletedProducerStillWaitsForOwnerAdmission() {
        CompletableFuture<ChunkAccess> returned = runtime.c2meGeneration(delegate, context, step, input,
                () -> CompletableFuture.completedFuture(input));

        assertFalse(returned.isDone(), "inline producer completion must still wait for owner admission");
        assertEquals(1, runtime.c2meRetainedGraphs());
        assertTrue(runtime.pumpOne());
        assertSame(input, returned.join());
        assertEquals(0, runtime.c2meRetainedGraphs());
        assertNull(barrier.get());
    }

    @Test void alreadyFailedProducerPropagatesInlineWithoutRetainingWork() {
        RuntimeException marker = new RuntimeException("already failed provider marker");
        CompletableFuture<ChunkAccess> producer = new CompletableFuture<>();
        producer.completeExceptionally(marker);

        CompletableFuture<ChunkAccess> returned = runtime.c2meGeneration(delegate, context, step, input, () -> producer);

        var failure = assertThrows(java.util.concurrent.CompletionException.class, returned::join);
        assertSame(marker, failure.getCause());
        assertEquals(0, runtime.c2meRetainedGraphs());
        assertFalse(runtime.pumpOne());
        assertNull(barrier.get());
    }

    @Test void topologyChangeReroutesSameQueuedStageBeforeCompletion() {
        CompletableFuture<ChunkAccess> producer = new CompletableFuture<>();
        CompletableFuture<ChunkAccess> returned = runtime.c2meGeneration(delegate, context, step, input, () -> producer);
        producer.complete(input);

        TopologyGeneration generation = new TopologyGeneration(2);
        fixture.directory.publish(generation, Map.of(), Map.of(fixture.worldId,
                new RegionRoute(fixture.otherId, generation, new OwnerEpoch(0))));

        assertTrue(runtime.pumpOne());
        assertFalse(returned.isDone(), "stale delivery must reroute rather than release provider completion");
        assertTrue(runtime.pumpOne());
        assertSame(input, returned.join());
        assertNull(barrier.get());
    }

    @Test void backingSlotReplacementSupersedesOldCompletionAndCannotReplay() {
        CompletableFuture<ChunkAccess> producer = new CompletableFuture<>();
        CompletableFuture<ChunkAccess> returned = runtime.c2meGeneration(delegate, context, step, input, () -> producer);
        producer.complete(input);
        backing.replaceSlot(new CompletableFuture<>());

        assertTrue(runtime.pumpOne());
        C2meGenerationControlException control = controlFailure(returned);
        assertEquals(ChunkStageAdmission.Result.SUPERSEDED, control.reason());
        assertEquals(0, runtime.c2meRetainedGraphs());
        assertNull(barrier.get());
    }

    @Test void replacementSlotCreatesNewWorkAndRetiresOldBranch() {
        CompletableFuture<ChunkAccess> oldProducer = new CompletableFuture<>();
        CompletableFuture<ChunkAccess> oldReturned = runtime.c2meGeneration(delegate, context, step, input, () -> oldProducer);
        backing.replaceSlot(new CompletableFuture<>());
        CompletableFuture<ChunkAccess> newProducer = new CompletableFuture<>();
        CompletableFuture<ChunkAccess> replacement = runtime.c2meGeneration(delegate, context, step, input, () -> newProducer);

        C2meGenerationControlException control = controlFailure(oldReturned);
        assertEquals(ChunkStageAdmission.Result.SUPERSEDED, control.reason());
        assertNotSame(oldReturned, replacement);
        oldProducer.complete(input);
        assertFalse(replacement.isDone(), "late old producer completion cannot replay into replacement work");
        newProducer.complete(input);
        assertFalse(replacement.isDone());
        assertTrue(runtime.pumpOne());
        assertSame(input, replacement.join());
        assertNull(barrier.get());
    }

    @Test void exactCenterHolderIsRevalidatedUnderClaim() {
        CompletableFuture<ChunkAccess> producer = new CompletableFuture<>();
        CompletableFuture<ChunkAccess> returned = runtime.c2meGeneration(delegate, context, step, input, () -> producer);
        producer.complete(input);
        centerBacking.getUserData().set(new FullRegistrationFixture.Holder(ChunkPos.ZERO));

        assertTrue(runtime.pumpOne());
        C2meGenerationControlException control = controlFailure(returned);
        assertEquals(ChunkStageAdmission.Result.SUPERSEDED, control.reason());
        assertNull(barrier.get());
    }

    @Test void producerFailurePropagatesSameThrowableWithoutOwnerMutation() {
        RuntimeException marker = new RuntimeException("provider failure marker");
        CompletableFuture<ChunkAccess> producer = new CompletableFuture<>();
        CompletableFuture<ChunkAccess> returned = runtime.c2meGeneration(delegate, context, step, input, () -> producer);
        producer.completeExceptionally(marker);

        var failure = assertThrows(java.util.concurrent.CompletionException.class, returned::join);
        assertSame(marker, failure.getCause());
        assertEquals(0, runtime.c2meRetainedGraphs());
        assertFalse(runtime.pumpOne());
        assertNull(barrier.get());
    }

    @Test void synchronousProducerThrowRemainsSynchronousAndLeavesNoBranch() {
        RuntimeException marker = new RuntimeException("synchronous marker");
        RuntimeException observed = assertThrows(RuntimeException.class,
                () -> runtime.c2meGeneration(delegate, context, step, input, () -> { throw marker; }));
        assertSame(marker, observed);
        assertEquals(0, runtime.c2meRetainedGraphs());
        assertNull(barrier.get());
    }

    @Test void shutdownDetachesPendingProducerAndLateCompletionCannotReplay() {
        CompletableFuture<ChunkAccess> producer = new CompletableFuture<>();
        CompletableFuture<ChunkAccess> returned = runtime.c2meGeneration(delegate, context, step, input, () -> producer);
        runtime.shutdown();

        C2meGenerationControlException control = controlFailure(returned);
        assertEquals(ChunkStageAdmission.Result.SHUTDOWN, control.reason());
        assertEquals(0, runtime.c2meRetainedGraphs());
        producer.complete(input);
        assertTrue(returned.isCompletedExceptionally());
        assertNull(barrier.get());
        runtime = null;
    }

    @Test void wrongProviderTypeFailsClosedBeforeProducerExecution() {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> C2meGenerationAccess.capture(new Object(), context, ChunkPos.ZERO));
        assertEquals(0, calls.get());
    }

    private static C2meGenerationControlException controlFailure(CompletableFuture<?> future) {
        var failure = assertThrows(java.util.concurrent.CompletionException.class, future::join);
        assertInstanceOf(C2meGenerationControlException.class, failure.getCause());
        return (C2meGenerationControlException) failure.getCause();
    }
}
