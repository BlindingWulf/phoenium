package dev.phoenium.chunk;

import dev.phoenium.core.OwnerEpoch;
import dev.phoenium.core.TopologyGeneration;
import dev.phoenium.core.WorldId;
import dev.phoenium.failure.FailureBarrier;
import dev.phoenium.ownership.OwnershipContext;
import dev.phoenium.ownership.RegionLeaseManager;
import dev.phoenium.region.RegionId;
import dev.phoenium.task.TaskCorrelationId;
import dev.phoenium.topology.RegionDirectory;
import dev.phoenium.topology.RegionRoute;
import dev.phoenium.topology.RoutingKey;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.world.entity.animal.frog.Tadpole;
import net.minecraft.world.level.chunk.LevelChunk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import static org.junit.jupiter.api.Assertions.*;

/** Real query guard + existing admission, including the original Minecraft supplier/catch chain.
 * This proves failure-channel enforcement, not the still-disabled integrated FULL binding. */
final class ChunkQueryFailureBindingTest {
    @BeforeAll static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap();
    }
    @Test void caughtNestedQueryBreachCannotBecomeSuccessfulConversionEvidence() throws Exception {
        var f = new FullTadpoleDecisionTest.Fixture(Tadpole.ticksToBeFrog);
        var x = FullNestedQueryBoundaryTest.primary(f); FullNestedQueryBoundaryTest.position(x.input(), 15.8);
        var y = FullNestedQueryBoundaryTest.neighbor(f, Tadpole.ticksToBeFrog, 16.2);
        AtomicReference<ChunkProtocolException> originalBreach = new AtomicReference<>();
        f.queryEndpoint = args -> {
            var q = FullNestedQueryBoundaryTest.select(f, args);
            if (q.target().equals(x.pos())) return x.actual();
            assertSame(y.full(), q.selected());
            try { ChunkSynchronousAccessGuard.beforeWait(q.selected()::isDone); }
            catch (ChunkProtocolException failure) { originalBreach.set(failure); throw failure; }
            throw new AssertionError("unfinished neighbor must not reach join or return");
        };
        Admission a = new Admission(f.holder);
        AtomicReference<LevelChunk> supplierReturn = new AtomicReference<>();
        var pending = a.pending(() -> { LevelChunk result = f.run(); supplierReturn.set(result); return result; });
        AtomicInteger observedCompletion = new AtomicInteger();
        pending.outcome().thenRun(() -> {
            assertFalse(a.lease.hasActiveLease()); assertTrue(OwnershipContext.current().isEmpty());
            observedCompletion.incrementAndGet();
        });
        assertEquals(ChunkStageAdmission.Result.FAILED_BARRIER, a.execute(pending));
        assertSame(x.actual(), supplierReturn.get(), "EntityType caught/wrapped the nested failure; supplier actually returned");
        assertSame(originalBreach.get(), assertInstanceOf(ChunkStageOutcome.BarrierFailure.class, pending.outcome().join()).failure());
        assertTrue(a.barrier.tripped()); assertEquals(0, a.admission.applications());
        assertEquals(1, f.executions); assertEquals(0, f.sounds); assertTrue(f.inserted.isEmpty());
        assertEquals(1, f.scoreboard.getPlayerTeam("amber").getPlayers().size(), "committed conversion team prefix remains");
        assertFalse(x.full().isDone()); assertFalse(y.full().isDone()); assertEquals(1, observedCompletion.get());
        assertFalse(pending.retainsInputOrContinuation());
        assertEquals(ChunkStageAdmission.Result.FAILED_BARRIER, a.execute(pending)); assertEquals(1, f.executions);
    }

    @Test void genuineCaughtVanillaFailureStillAllowsOriginalSupplierSuccess() throws Exception {
        var f = new FullTadpoleDecisionTest.Fixture(Tadpole.ticksToBeFrog); f.failSound = true;
        Admission a = new Admission(f.holder);
        var pending = a.pending(f::run);
        assertEquals(ChunkStageAdmission.Result.APPLIED, a.execute(pending));
        assertSame(f.actual(), assertInstanceOf(ChunkStageOutcome.Applied.class, pending.outcome().join()).value());
        assertEquals(1, f.sounds); assertTrue(f.inserted.isEmpty()); assertFalse(a.barrier.tripped());
        assertEquals(1, f.scoreboard.getPlayerTeam("amber").getPlayers().size());
        assertFalse(f.fullSlot.isDone(), "conversion outcome alone still does not publish the holder");
    }

    @Test void caughtDynamicShapeBreachKeepsTheReachedFullTrueTicketPrefix() throws Exception {
        var f = new FullTadpoleDecisionTest.Fixture(Tadpole.ticksToBeFrog);
        var x = FullNestedQueryBoundaryTest.primary(f);
        f.proto.getSection(0).setBlockState(8, 8, 8, net.minecraft.world.level.block.Blocks.SHULKER_BOX.defaultBlockState());
        AtomicInteger fullTrue = new AtomicInteger();
        f.queryEndpoint = args -> {
            var q = FullNestedQueryBoundaryTest.select(f, args);
            assertSame(x.full(), q.selected());
            if (q.create()) {
                fullTrue.incrementAndGet();
                assertTrue(f.tickets.hasTickets(), "original ticket insertion precedes rejected unfinished query");
                ChunkSynchronousAccessGuard.beforeWait(q.selected()::isDone);
                throw new AssertionError("must not fabricate dynamic shape data");
            }
            return x.actual();
        };
        Admission a = new Admission(f.holder); var pending = a.pending(f::run);
        assertEquals(ChunkStageAdmission.Result.FAILED_BARRIER, a.execute(pending));
        assertEquals(1, fullTrue.get()); assertTrue(f.tickets.hasTickets());
        assertEquals(1, f.scoreboard.getPlayerTeam("amber").getPlayers().size());
        assertEquals(0, f.sounds); assertTrue(f.inserted.isEmpty()); assertFalse(f.fullSlot.isDone());
        assertTrue(f.actual().getBlockEntities().isEmpty(), "failure precedes original IMMEDIATE creation");
    }

    @Test void caughtQueryBreachWinsOverLaterUncaughtVanillaFailure() {
        Admission a = new Admission(new Object()); AtomicReference<ChunkProtocolException> first = new AtomicReference<>();
        var later = new IllegalArgumentException("later vanilla suffix");
        var pending = a.pending(() -> {
            try { ChunkSynchronousAccessGuard.beforeWait(() -> false); }
            catch (ChunkProtocolException failure) { first.set(failure); }
            throw later;
        });
        assertEquals(ChunkStageAdmission.Result.FAILED_BARRIER, a.execute(pending));
        assertSame(first.get(), assertInstanceOf(ChunkStageOutcome.BarrierFailure.class, pending.outcome().join()).failure());
        assertArrayEquals(new Throwable[]{later}, first.get().getSuppressed(), "later genuine failure is retained for diagnosis");
        assertFalse(a.lease.hasActiveLease()); assertFalse(pending.retainsInputOrContinuation());
    }

    @Test void reentrantSettlementAndLaterClaimDoNotInheritRetiredQueryFailure() {
        Admission a = new Admission(new Object()), next = new Admission(new Object());
        var failed = a.pending(() -> {
            try { ChunkSynchronousAccessGuard.beforeWait(() -> false); } catch (ChunkProtocolException expected) { }
            return 1;
        });
        AtomicInteger nextInvocations = new AtomicInteger();
        var valid = next.pending(() -> { ChunkSynchronousAccessGuard.beforeWait(() -> true); return nextInvocations.incrementAndGet(); });
        var reentrant = failed.outcome().thenRun(() -> assertEquals(ChunkStageAdmission.Result.APPLIED, next.execute(valid)));
        assertEquals(ChunkStageAdmission.Result.FAILED_BARRIER, a.execute(failed)); reentrant.join();
        assertEquals(1, nextInvocations.get()); assertFalse(next.barrier.tripped());
        assertFalse(a.lease.hasActiveLease()); assertFalse(next.lease.hasActiveLease());
        assertTrue(OwnershipContext.current().isEmpty());
        ChunkSynchronousAccessGuard.beforeWait(() -> false); // Original owner-free behavior is unchanged.
    }

    @FunctionalInterface interface Body<T> { T run() throws Throwable; }
    static final class Admission {
        final WorldId world = new WorldId("full-query-test");
        final RegionId owner = new RegionId(world, "one-converged-test-region");
        final TopologyGeneration generation = new TopologyGeneration(1);
        final ChunkCustodyModel custody = new ChunkCustodyModel();
        final RegionLeaseManager lease = new RegionLeaseManager(owner);
        final FailureBarrier barrier = new FailureBarrier();
        final ChunkStageAdmission admission;
        final ChunkCustodyModel.WorkId work;
        Admission(Object actualHolder) {
            var key = new RoutingKey(world, 0, 0);
            var directory = new RegionDirectory();
            directory.publish(generation, Map.of(key, new RegionRoute(owner, generation, new OwnerEpoch(0))));
            var lifecycles = new ChunkLifecycleRegistry(custody, "query-session");
            var lifecycle = lifecycles.activateHolder(1, key, actualHolder);
            work = new ChunkWorkRegistry(custody).beginOrResolveSharedWork(lifecycle,
                    new ChunkWorkRegistry.WorkReservation("generation-status", "FULL", 0));
            admission = new ChunkStageAdmission(custody, directory, Map.of(owner, lease), barrier);
        }
        <T> PendingVanillaStage<Integer, T> pending(Body<T> body) {
            var stage = new ChunkCustodyModel.StageId(work, ChunkStageKind.FULL_CONVERT, "FULL", 0);
            var pending = new PendingVanillaStage<Integer, T>(custody, work.lifecycle(), work, stage,
                    new TaskCorrelationId(1), ignored -> body.run());
            assertTrue(pending.bindInput(1)); pending.publishReady(); return pending;
        }
        ChunkStageAdmission.Result execute(PendingVanillaStage<?, ?> pending) {
            return new ChunkStageExecutor(admission).execute(pending, owner, generation);
        }
    }
}
