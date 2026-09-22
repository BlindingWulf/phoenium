package dev.phoenium.chunk;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.*;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.animal.frog.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static dev.phoenium.chunk.FullDecisionWitnessSupport.*;
import static org.junit.jupiter.api.Assertions.*;

final class FullNonselfInputDecisionTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @ParameterizedTest @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void originalAllAirPairActuallyFinishesBothOrdersAndBothConstructionPaths(boolean reload, boolean reverse) throws Exception {
        var p = new FullNonselfPrototype(reload, reverse);
        List<FullNonselfPrototype.Work> order = List.copyOf(p.works.values());
        var first = p.plan(order.getFirst());
        assertEquals(2, first.inputs().size()); assertTrue(p.prepare(first),()->p.works.values().stream()
            .map(w->w.construction==null ? "NOT_CONSTRUCTED" : w.construction.outcome().getNow(null)).toList().toString());
        for (var w : order) {
            assertEquals(1, w.constructions); assertEquals(0, w.callbacks);
            assertEquals(false, field(w.actual, LevelChunk.class, "loaded"));
            assertNotNull(field(w.actual, LevelChunk.class, "postLoad"));
            assertNull(p.f.cache.getChunkNow(w.pos().x(), w.pos().z()));
            assertFalse(w.node.full().isDone()); assertFalse(w.conversion.isDone());
        }
        for (var w : order) {
            assertEquals(ChunkStageAdmission.Result.APPLIED, p.execute(p.plan(w)));
            assertSame(w.actual, w.conversion.getNow(null));
            assertEquals(1, w.constructions); assertEquals(1, w.callbacks);
            assertEquals(reload ? 0 : 1, w.unsaved);
            assertNull(field(w.actual, LevelChunk.class, "postLoad"));
            assertTrue((boolean)field(w.actual, LevelChunk.class, "loaded"));
            assertFalse(w.node.full().isDone()); assertNull(p.f.cache.getChunkNow(w.pos().x(), w.pos().z()));
        }
        assertEquals(2, p.f.sounds); assertEquals(4, p.f.inserted.size());
        assertEquals(2, p.f.inserted.stream().filter(e -> e instanceof Frog).count());
        assertEquals(2, p.f.inserted.stream().filter(e -> e instanceof Tadpole t && t.isRemoved() && t.isAgeLocked()).count());
        assertEquals(2, p.f.scoreboard.getPlayerTeam("amber").getPlayers().size());
        for (var w : order) assertTrue(p.accesses.stream().anyMatch(a -> a.source() == w && a.target() != w
            && a.callers().contains("getChunkForCollisions")));
        RandomSource sound = RandomSource.create(9157L); sound.nextLong(); sound.nextLong();
        assertEquals(sound.nextLong(), p.f.soundRandom.nextLong());
        RandomSource random = RandomSource.create(73031L);
        for (int i = 0; i < 2; i++) { random.nextInt(1); random.nextIntBetweenInclusive(100, 140);
            random.triangle(0, 0.11485000000000001); random.nextFloat(); }
        assertEquals(random.nextLong(), p.f.random.nextLong(), "original world random consumption, two conversions");
        for (var w : order) {
            assertEquals(ChunkStageAdmission.Result.DUPLICATE, p.execute(p.plan(w)));
            assertEquals(ChunkStageAdmission.Result.APPLIED, p.publish(w));
            assertSame(w.actual, w.node.full().join().orElse(null));
        }
        assertEquals(2, p.f.sounds); assertEquals(4, p.f.inserted.size()); assertFalse(p.barrier.tripped());
        p.cleanup();
    }

    @ParameterizedTest @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void originalNeighborShulkerShapeCreatesImmediatelyAndRepeatsWithoutEarlyFull(boolean reload, boolean reverse) throws Exception {
        var p = new FullNonselfPrototype(reload, reverse);
        p.x().node.input().getSection(0).setBlockState(15, 8, 8, Blocks.SHULKER_BOX.defaultBlockState());
        p.y().node.input().getSection(0).setBlockState(0, 8, 8, Blocks.SHULKER_BOX.defaultBlockState());
        for (var w : p.works.values()) { assertTrue(p.prepare(p.plan(w)));
            assertEquals(ChunkStageAdmission.Result.APPLIED, p.execute(p.plan(w))); }
        var dynamic = p.accesses.stream().filter(FullNonselfPrototype.Access::create).toList();
        assertTrue(dynamic.size() >= 4, "original vertical fallback repeats dynamic queries");
        assertTrue(dynamic.stream().anyMatch(a -> a.source() != a.target() && !a.present() && !a.loaded()),
            "foreign immediate creation before target postLoad/loaded, never preinstalled");
        assertTrue(dynamic.stream().anyMatch(FullNonselfPrototype.Access::present));
        for (var a : dynamic) { assertTrue(a.callers().contains("getShape")); assertTrue(a.callers().contains("getBlockEntity")); }
        for (var w : p.works.values()) {
            BlockPos block = new BlockPos(w == p.x() ? 15 : 16, 8, 8);
            assertInstanceOf(ShulkerBoxBlockEntity.class, w.actual.getBlockEntity(block));
            assertEquals(1, w.actual.getBlockEntities().size());
            assertFalse(w.node.full().isDone());
            assertEquals(TicketType.UNKNOWN, p.f.tickets.getTickets(w.pos().pack()).getFirst().getType());
            assertEquals(33, p.f.tickets.getTickets(w.pos().pack()).getFirst().getTicketLevel());
        }
        assertEquals(2, ((List<?>)field(p.f.level, Level.class, "blockEntityTickers")).size());
        assertEquals(2, p.f.sounds); assertEquals(4, p.f.inserted.size()); assertFalse(p.barrier.tripped());
        p.cleanup();
    }

    @Test void preclaimInputChangeAndBusyProducerReleaseAllAuthorityAndWakeTheSameWork() throws Exception {
        var p = new FullNonselfPrototype(false, false);
        var stale = p.plan(p.x()); p.changed(p.y());
        assertFalse(p.prepare(stale)); assertFalse(p.lease.hasActiveLease()); assertEquals(0, p.x().constructions);
        p.y().writers = 1; assertTrue(p.enqueue(p.x())); assertFalse(p.advance());
        assertFalse(p.lease.hasActiveLease()); assertEquals(1, p.queue.size());
        p.y().writers = 0; p.changed(p.y());
        assertTrue(p.advance()); assertTrue(p.queue.isEmpty()); assertTrue(p.wakeups >= 2);
        assertEquals(1, p.x().callbacks); assertEquals(0, p.y().callbacks);
        assertTrue(p.enqueue(p.y())); assertTrue(p.advance()); assertEquals(1, p.y().constructions);
        p.cleanup();
    }

    @Test void privateObjectExpiresAtExitAndConflictingWritersCannotEnterClaimedWindow() throws Exception {
        var p = new FullNonselfPrototype(false, false); AtomicReference<FullNonselfPrototype.Scope> retained = new AtomicReference<>();
        assertTrue(p.prepare(p.plan(p.x())));
        p.duringCallback = w -> {
            retained.set(p.active);
            assertThrows(ChunkProtocolException.class, () -> p.changed(p.y()));
            assertSame(p.y().actual, p.active.use(p.f.level, w.id, p.y(), p.y().node.holder()));
        };
        assertEquals(ChunkStageAdmission.Result.APPLIED, p.execute(p.plan(p.x())));
        assertThrows(ChunkProtocolException.class, () -> retained.get().use(p.f.level, p.x().id, p.y(), p.y().node.holder()));
        p.cleanup();
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings = {"world", "work", "holder", "lifecycle", "query-caller"})
    void caughtWrongPrivateEntitlementCannotBecomeApplied(String wrong) throws Exception {
        var p = new FullNonselfPrototype(false, false); assertTrue(p.prepare(p.plan(p.x())));
        p.duringCallback = w -> {
            if (wrong.equals("query-caller")) {
                assertThrows(ChunkProtocolException.class, () -> p.query(new Object[]{1, 0, ChunkStatus.FULL, false}));
                return;
            }
            var caller = wrong.equals("work") ? p.y().id : w.id;
            if (wrong.equals("lifecycle")) caller = new ChunkCustodyModel.WorkId(new ChunkCustodyModel.LifecycleId(
                "different-session", 2, p.world, w.id.lifecycle().chunkKey(), 100), 0);
            var id = caller;
            assertThrows(ChunkProtocolException.class, () -> p.active.use(wrong.equals("world") ? null : p.f.level,
                id, p.y(), wrong.equals("holder") ? p.x().node.holder() : p.y().node.holder()));
        };
        assertEquals(ChunkStageAdmission.Result.FAILED_BARRIER, p.execute(p.plan(p.x())));
        assertTrue(p.x().conversion.isCompletedExceptionally()); assertEquals(ChunkStageAdmission.Result.NOT_READY, p.publish(p.x()));
        assertTrue(p.barrier.tripped()); p.cleanup();
    }

    @Test void threeActualConversionsHaveCyclicCollisionInputsWithoutCyclicPublicationEdges() throws Exception {
        var p = new FullNonselfPrototype(false, false);
        // Preserve the original pair and add one corner tadpole in each, plus a third chunk.
        for (var work : List.of(p.x(), p.y())) {
            var tag = work.entities.getFirst().copy();
            var pos = tag.getListOrEmpty("Pos"); pos.set(2, net.minecraft.nbt.DoubleTag.valueOf(15.8));
            work.node.input().addEntity(tag);
        }
        var z = p.add(FullNestedQueryBoundaryTest.neighbor(p.f, new ChunkPos(1, 1), Tadpole.ticksToBeFrog, 16.2, 16.2), false, List.of());
        p.publishRoutes();
        assertEquals(3, p.plan(z).inputs().size());
        for (var work : p.works.values()) {
            assertTrue(p.prepare(p.plan(work)));
            assertEquals(ChunkStageAdmission.Result.APPLIED, p.execute(p.plan(work)));
        }
        assertTrue(p.accesses.stream().anyMatch(a -> a.source() == p.x() && a.target() == p.y()));
        assertTrue(p.accesses.stream().anyMatch(a -> a.source() == p.y() && a.target() == z));
        assertTrue(p.accesses.stream().anyMatch(a -> a.source() == z && a.target() == p.x()));
        assertEquals(5, p.f.sounds); assertEquals(10, p.f.inserted.size());
        for (var work : p.works.values()) { assertEquals(1, work.constructions); assertEquals(1, work.callbacks);
            assertFalse(work.node.full().isDone()); assertEquals(ChunkStageAdmission.Result.APPLIED, p.publish(work)); }
        p.cleanup();
    }

    @Test void overlappingWindowsShareConstructionAndBoundQueueBackpressureHasCapacityWakeups() throws Exception {
        var p = new FullNonselfPrototype(false, false, 4, 2);
        for (int x = 2; x < 8; x++) p.add(FullNestedQueryBoundaryTest.neighbor(p.f,
            new ChunkPos(x, 0), Tadpole.ticksToBeFrog, 16 * x + 0.2, 8), false, List.of());
        p.publishRoutes();
        var order = List.copyOf(p.works.values());
        assertTrue(p.enqueue(order.get(0))); assertTrue(p.enqueue(order.get(1)));
        assertFalse(p.enqueue(order.get(2)), "producer keeps valid request until capacity wakeup");
        p.turnsAvailable = false; assertFalse(p.advance()); assertFalse(p.lease.hasActiveLease());
        p.turnsAvailable = true; p.wakeups++;
        for (int i = 0; i < order.size(); i++) {
            assertTrue(p.advance());
            if (i + 2 < order.size()) assertTrue(p.enqueue(order.get(i + 2)));
        }
        assertTrue(p.queue.isEmpty()); assertEquals(8, p.f.sounds); assertTrue(p.wakeups >= 8);
        for (var work : order) { assertEquals(1, work.constructions); assertEquals(1, work.callbacks); }
        assertEquals(2, p.plan(order.getLast()).inputs().size(), "no transitive dependency component or permanent world convergence");
        p.cleanup();
    }

    @Test void reloadImmediateCreationIsReplacedOnlyAtTheOriginalSavedBlockEntitySuffix() throws Exception {
        var tag = new net.minecraft.nbt.CompoundTag(); tag.putString("id", "minecraft:shulker_box");
        tag.putInt("x", 16); tag.putInt("y", 8); tag.putInt("z", 8);
        var touchedPacked = tag.copy(); touchedPacked.putInt("x", 17); touchedPacked.putBoolean("keepPacked", true);
        var untouched = touchedPacked.copy(); untouched.putInt("x", 24);
        var p = new FullNonselfPrototype(true, false, 4, 8, Map.of(new ChunkPos(1, 0), List.of(tag, touchedPacked, untouched)));
        p.y().node.input().getSection(0).setBlockState(0, 8, 8, Blocks.SHULKER_BOX.defaultBlockState());
        p.y().node.input().getSection(0).setBlockState(1, 8, 8, Blocks.SHULKER_BOX.defaultBlockState());
        p.y().node.input().getSection(0).setBlockState(8, 8, 8, Blocks.SHULKER_BOX.defaultBlockState());
        assertTrue(p.prepare(p.plan(p.x())));
        assertTrue(p.y().actual.getBlockEntities().isEmpty()); assertNull(p.y().actual.getBlockEntityNbt(new BlockPos(17, 8, 8)));
        assertEquals(ChunkStageAdmission.Result.APPLIED, p.execute(p.plan(p.x())));
        var immediate = p.y().actual.getBlockEntities().get(new BlockPos(16, 8, 8)); assertNotNull(immediate);
        assertFalse(immediate.isRemoved()); assertFalse((boolean)field(p.y().actual, LevelChunk.class, "loaded"));
        assertEquals(0, ((List<?>)field(p.f.level, Level.class, "blockEntityTickers")).size());
        assertNull(p.y().actual.getBlockEntityNbt(new BlockPos(17, 8, 8)), "saved suffix was not preinstalled");
        assertEquals(ChunkStageAdmission.Result.APPLIED, p.execute(p.plan(p.y())));
        var saved = p.y().actual.getBlockEntities().get(new BlockPos(16, 8, 8));
        assertNotSame(immediate, saved); assertTrue(immediate.isRemoved()); assertFalse(saved.isRemoved());
        assertNotNull(p.y().actual.getBlockEntities().get(new BlockPos(17, 8, 8)));
        assertNull(p.y().actual.getBlockEntityNbt(new BlockPos(17, 8, 8)), "original keepPacked skips a position already created by collision");
        assertNotNull(p.y().actual.getBlockEntityNbt(new BlockPos(24, 8, 8)), "untouched original keepPacked branch ran later");
        assertEquals(2, p.f.sounds); p.cleanup();
    }

    @Test void caughtSoundErrorRetainsForeignImmediateCreationAndTicketButIsNotAnAuthorityBreach() throws Exception {
        var p = new FullNonselfPrototype(false, false);
        p.y().node.input().getSection(0).setBlockState(0, 8, 8, Blocks.SHULKER_BOX.defaultBlockState());
        assertTrue(p.prepare(p.plan(p.x()))); p.f.failSound = true;
        assertEquals(ChunkStageAdmission.Result.APPLIED, p.execute(p.plan(p.x())));
        var block = p.y().actual.getBlockEntities().get(new BlockPos(16, 8, 8)); assertNotNull(block);
        assertTrue(p.f.tickets.hasTickets()); assertEquals(1, p.f.sounds); assertTrue(p.f.inserted.isEmpty());
        assertEquals(1, p.f.scoreboard.getPlayerTeam("amber").getPlayers().size()); assertFalse(p.barrier.tripped());
        assertEquals(ChunkStageAdmission.Result.DUPLICATE, p.execute(p.plan(p.x())));
        assertSame(block, p.y().actual.getBlockEntities().get(new BlockPos(16, 8, 8)));
        assertEquals(ChunkStageAdmission.Result.APPLIED, p.publish(p.x())); p.cleanup();
    }

    @Test void newDependencyBeforeClaimReplansButUnexpectedMidCallbackDiscoveryFencesSuccess() throws Exception {
        var p = new FullNonselfPrototype(false, false);
        var stale = p.plan(p.x());
        var z = p.add(FullNestedQueryBoundaryTest.neighbor(p.f, new ChunkPos(1, 1), Tadpole.ticksToBeFrog, 16.2, 16.2), false, List.of());
        p.version++; p.publishRoutes(); assertFalse(p.prepare(stale));
        assertTrue(p.prepare(p.plan(p.x())));
        var good = p.plan(p.x());
        var missing = new FullNonselfPrototype.Plan(good.source(), good.inputs(), Set.of(p.x().pos()), good.generation());
        assertEquals(ChunkStageAdmission.Result.FAILED_BARRIER, p.execute(missing));
        assertEquals(0, p.f.sounds); assertEquals(1, p.x().callbacks); assertEquals(0, z.callbacks);
        assertTrue(p.x().conversion.isCompletedExceptionally()); p.cleanup();
    }

    @Test void preclaimHolderReplacementRejectsOldInputAndLeavesReplacementWorkIndependent() throws Exception {
        var p = new FullNonselfPrototype(false, false); var stale = p.plan(p.x()); var oldY = p.y();
        var replacement = FullNestedQueryBoundaryTest.neighbor(p.f, Tadpole.ticksToBeFrog, 16.2);
        assertFalse(p.prepare(stale)); assertEquals(0, p.x().constructions);
        p.requestRetire(oldY); assertTrue(oldY.released); assertEquals(1, oldY.cleanups);
        var y = p.add(replacement, false, List.of()); p.version++; p.publishRoutes();
        assertNotEquals(oldY.id.lifecycle(), y.id.lifecycle());
        assertTrue(p.prepare(p.plan(p.x()))); assertEquals(ChunkStageAdmission.Result.APPLIED, p.execute(p.plan(p.x())));
        assertEquals(0, oldY.constructions); assertEquals(1, y.constructions); p.cleanup();
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void successfulConsumerMayPublishAlthoughItsPrivatelyObservedProducerLaterFailsWithoutReplay(boolean reload) throws Exception {
        var p = new FullNonselfPrototype(reload, false);
        p.y().node.input().getSection(0).setBlockState(0, 8, 8, Blocks.SHULKER_BOX.defaultBlockState());
        assertTrue(p.prepare(p.plan(p.x())));
        assertEquals(ChunkStageAdmission.Result.APPLIED, p.execute(p.plan(p.x())));
        assertEquals(ChunkStageAdmission.Result.APPLIED, p.publish(p.x()));
        var reached = p.y().actual.getBlockEntities().get(new BlockPos(16, 8, 8)); assertNotNull(reached);
        p.f.failRootInsertion = true;
        assertEquals(ChunkStageAdmission.Result.FAILED_VANILLA, p.execute(p.plan(p.y())));
        assertTrue(p.x().node.full().isDone()); assertFalse(p.y().node.full().isDone());
        assertEquals(ChunkStageAdmission.Result.NOT_READY, p.publish(p.y()));
        assertEquals(ChunkStageAdmission.Result.DUPLICATE, p.execute(p.plan(p.y())));
        assertEquals(1, p.y().callbacks); assertEquals(2, p.f.sounds); assertEquals(4, p.f.inserted.size());
        assertNotNull(field(p.y().actual, LevelChunk.class, "postLoad"));
        assertSame(reached, p.y().actual.getBlockEntities().get(new BlockPos(16, 8, 8)));
        assertTrue(p.f.tickets.hasTickets()); assertEquals(2, p.f.scoreboard.getPlayerTeam("amber").getPlayers().size());
        assertFalse(p.barrier.tripped()); p.cleanup();
    }

    @Test void shutdownAfterConstructionAndRetirementDuringClaimHaveFiniteAcknowledgement() throws Exception {
        var p = new FullNonselfPrototype(true, false);
        var shutdownPlan = p.plan(p.x());
        assertTrue(p.prepare(shutdownPlan)); p.shutdown();
        assertEquals(ChunkStageAdmission.Result.NOT_READY, p.execute(shutdownPlan));
        assertEquals(0, p.f.sounds); p.cleanup();
        var q = new FullNonselfPrototype(false, false); assertTrue(q.prepare(q.plan(q.x())));
        var retiredY = q.y();
        q.duringCallback = w -> {
            q.requestRetire(q.y()); assertFalse(q.y().retired);
            assertThrows(AssertionError.class, q::cleanup, "legitimate claim pins inputs until exit");
        };
        assertEquals(ChunkStageAdmission.Result.APPLIED, q.execute(q.plan(q.x())));
        assertTrue(retiredY.retired); assertTrue(retiredY.released); assertEquals(1, retiredY.cleanups); assertFalse(q.x().retired);
        assertEquals(ChunkStageAdmission.Result.APPLIED, q.publish(q.x()));
        q.cleanup();
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void twoConsumersPinSharedInputsUntilTheirOwnAcknowledgementsAcrossRepeatedLifecycles(boolean reload) throws Exception {
        int released = 0;
        for (int cycle = 0; cycle < 8; cycle++) {
            var p = new FullNonselfPrototype(reload, cycle % 2 == 1);
            var x = p.x(); var y = p.y();
            var first = p.retainInputs(p.plan(x)); var second = p.retainInputs(p.plan(y));
            assertTrue(p.prepare(p.plan(x))); assertTrue(p.prepare(p.plan(y)));
            assertEquals(1, x.constructions); assertEquals(1, y.constructions);
            assertEquals(2, x.pins); assertEquals(2, y.pins);
            for (var work : List.of(x, y)) {
                assertEquals(ChunkStageAdmission.Result.APPLIED, p.execute(p.plan(work)));
                assertEquals(ChunkStageAdmission.Result.APPLIED, p.publish(work));
            }
            for (var work : List.of(x, y)) {
                p.requestRetire(work);
                assertFalse(work.released); assertNotNull(work.actual);
            }
            // Both conversions already finished; only legitimate consumers now retain data.
            assertTrue(p.custody.retainedEntries() > 0);
            assertThrows(AssertionError.class, p::cleanup);
            first.close(); first.close();
            assertEquals(1, y.pins); assertEquals(0, y.cleanups); assertEquals(2, p.retainedWorks.size());
            second.close(); second.close();
            for (var work : List.of(x, y)) {
                assertEquals(1, work.cleanups); assertNull(work.actual);
                assertNull(work.construction); assertNull(work.callback); assertNull(work.publication);
                assertEquals(1, work.callbacks);
                assertSame(work.conversion.join(), work.node.full().join().orElse(null), "external successful result retains its identity");
            }
            assertEquals(2, p.releases); assertEquals(2, p.peakRetained);
            assertTrue(p.retainedWorks.isEmpty()); assertTrue(p.consumers.isEmpty());
            assertTrue(p.works.isEmpty()); assertTrue(p.queue.isEmpty()); assertTrue(p.retirement.isEmpty());
            assertEquals(0, p.custody.retainedEntries());
            p.cleanup(); released += p.releases;
        }
        assertEquals(16, released);
        System.out.println("Shared cleanup reload=" + reload + ": 8 lifecycles, 16 works released once; terminal registry/consumer/queue/custody=0");
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void preparedHolderReplacementRetainsOldConsumerWithoutReconstructingUnchangedInput(boolean reload) throws Exception {
        var p = new FullNonselfPrototype(reload, false);
        var x = p.x(); var oldY = p.y(); var stale = p.plan(x);
        var consumer = p.retainInputs(stale); assertTrue(p.prepare(stale));
        LevelChunk oldObject = oldY.actual; LevelChunk xObject = x.actual;
        var replacement = FullNestedQueryBoundaryTest.neighbor(p.f, Tadpole.ticksToBeFrog, 16.2);
        p.requestRetire(oldY);
        assertFalse(oldY.released); assertSame(oldObject, oldY.actual); assertEquals(1, oldY.pins);
        var y = p.add(replacement, reload, List.of()); p.publishRoutes();
        assertFalse(p.prepare(stale));
        assertTrue(p.prepare(p.plan(x))); assertSame(xObject, x.actual); assertNotSame(oldObject, y.actual);
        assertEquals(1, x.constructions); assertEquals(1, oldY.constructions); assertEquals(1, y.constructions);
        assertEquals(0, oldY.callbacks);
        for (var work : List.of(x, y)) {
            assertEquals(ChunkStageAdmission.Result.APPLIED, p.execute(p.plan(work)));
            assertEquals(ChunkStageAdmission.Result.APPLIED, p.publish(work));
        }
        consumer.close(); assertTrue(oldY.released); assertEquals(1, oldY.cleanups);
        assertTrue(oldY.conversion.isCompletedExceptionally()); assertTrue(oldY.node.full().isCompletedExceptionally());
        assertSame(y, p.y()); assertSame(y.node.holder(), p.f.visibleHolders.get(y.pos().pack()));
        assertSame(y.actual, y.node.full().join().orElse(null));
        p.requestRetire(oldY); assertEquals(1, oldY.cleanups);
        p.cleanup(); assertEquals(3, p.releases); assertEquals(0, p.custody.retainedEntries());
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings = {"object", "holder-slot", "operation"})
    void exactObjectHolderAndActiveOperationCannotBeSubstitutedEvenIfTheCallerCatchesIt(String wrong) throws Exception {
        var p = new FullNonselfPrototype(false, false); assertTrue(p.prepare(p.plan(p.x())));
        AtomicReference<FullNonselfPrototype.Scope> oldScope = new AtomicReference<>();
        if (wrong.equals("operation")) {
            p.duringCallback = work -> oldScope.set(p.active);
            assertEquals(ChunkStageAdmission.Result.APPLIED, p.execute(p.plan(p.y())));
            assertNull(oldScope.get().plan); assertNull(oldScope.get().claim); assertTrue(oldScope.get().objects.isEmpty());
        }
        p.duringCallback = work -> {
            var scope = wrong.equals("operation") ? oldScope.get() : p.active;
            if (wrong.equals("operation")) {
                var current = p.active; scope.close(); assertSame(current, p.active, "late close cannot revoke another operation");
            }
            if (wrong.equals("holder-slot")) p.f.visibleHolders.put(p.y().pos().pack(), p.x().node.holder());
            assertThrows(ChunkProtocolException.class, () -> scope.use(p.f.level, work.id, p.y(), p.y().node.holder(),
                wrong.equals("object") ? p.x().actual : p.y().actual));
            if (wrong.equals("holder-slot")) p.f.visibleHolders.put(p.y().pos().pack(), p.y().node.holder());
        };
        assertEquals(ChunkStageAdmission.Result.FAILED_BARRIER, p.execute(p.plan(p.x())));
        assertTrue(p.x().conversion.isCompletedExceptionally()); assertFalse(p.x().node.full().isDone());
        assertEquals(ChunkStageAdmission.Result.NOT_READY, p.publish(p.x()));
        p.cleanup();
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(strings = {"mutation", "ticket"})
    void dynamicShapeRequiresExplicitMutationAndTicketOwnership(String missing) throws Exception {
        var p = new FullNonselfPrototype(false, false);
        var y = p.y(); BlockPos block = new BlockPos(16, 8, 8);
        y.node.input().getSection(0).setBlockState(0, 8, 8, Blocks.SHULKER_BOX.defaultBlockState());
        assertTrue(p.prepare(p.plan(p.x())));
        p.duringCallback = work -> {
            if (missing.equals("mutation")) p.active.mutationOwners.remove(y);
            else p.active.ticketTurn = false;
        };
        assertEquals(ChunkStageAdmission.Result.FAILED_BARRIER, p.execute(p.plan(p.x())));
        assertNull(y.actual.getBlockEntities().get(block)); assertEquals(0, p.f.sounds);
        assertEquals(missing.equals("mutation"), p.f.tickets.hasTickets(), "reached original ticket prefix is never rolled back");
        assertEquals(1, p.f.scoreboard.getPlayerTeam("amber").getPlayers().size());
        assertFalse(p.x().node.full().isDone()); assertTrue(p.x().conversion.isCompletedExceptionally());
        p.cleanup();
    }

    @ParameterizedTest @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void foreignDynamicShapeReadsActualMutableProgressWithBothPublicResultsUnfinished(boolean reload, boolean reverse) throws Exception {
        var p = new FullNonselfPrototype(reload, reverse);
        for (var work : p.works.values()) {
            int localX = work == p.x() ? 15 : 0;
            work.node.input().getSection(0).setBlockState(localX, 8, 8,
                Blocks.SHULKER_BOX.defaultBlockState().setValue(net.minecraft.world.level.block.ShulkerBoxBlock.FACING, net.minecraft.core.Direction.EAST));
        }
        assertTrue(p.prepare(p.plan(p.x())));
        Map<FullNonselfPrototype.Work, ShulkerBoxBlockEntity> boxes = new HashMap<>();
        for (var work : p.works.values()) {
            BlockPos pos = new BlockPos(work == p.x() ? 15 : 16, 8, 8);
            var box = new ShulkerBoxBlockEntity(pos, work.actual.getBlockState(pos));
            set(box, ShulkerBoxBlockEntity.class, "progress", 0.5F);
            work.actual.setBlockEntity(box); boxes.put(work, box); p.changed(work);
            assertTrue(box.getBoundingBox(box.getBlockState()).maxX > 1);
        }
        p.onAccess = access -> {
            assertFalse(p.x().node.full().isDone()); assertFalse(p.y().node.full().isDone());
            if (access.create()) {
                p.active.requireMutation(access.target());
                assertSame(boxes.get(access.target()), access.target().actual.getBlockEntities().values().iterator().next());
            }
        };
        for (var work : p.works.values()) {
            assertEquals(ChunkStageAdmission.Result.APPLIED, p.execute(p.plan(work)));
            assertSame(work.actual, work.conversion.join()); assertEquals(1, work.callbacks);
        }
        assertTrue(p.accesses.stream().anyMatch(a -> a.create() && a.source() != a.target()));
        assertEquals(2, p.f.sounds); assertEquals(4, p.f.inserted.size());
        p.cleanup();
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void additionalSavedEntityBeforeExecutionReplansAnActuallyLargerDependency(boolean reload) throws Exception {
        var p = new FullNonselfPrototype(reload, false);
        var stale = p.plan(p.x()); assertTrue(p.prepare(stale));
        LevelChunk originalX = p.x().actual; LevelChunk originalY = p.y().actual;
        var corner = p.x().entities.getFirst().copy();
        corner.getListOrEmpty("Pos").set(2, net.minecraft.nbt.DoubleTag.valueOf(15.8));
        p.x().entities.add(corner); // Deliberately omit changed(): frozen saved tags still detect this writer.
        assertFalse(p.valid(stale));
        assertEquals(ChunkStageAdmission.Result.NOT_READY, p.execute(stale));
        var z = p.add(FullNestedQueryBoundaryTest.neighbor(p.f, new ChunkPos(1, 1), Tadpole.ticksToBeFrog, 16.2, 16.2), reload, List.of());
        p.publishRoutes(); var revised = p.plan(p.x());
        assertEquals(3, revised.inputs().size()); assertTrue(p.prepare(revised));
        assertSame(originalX, p.x().actual); assertSame(originalY, p.y().actual);
        for (var work : p.works.values()) {
            assertEquals(ChunkStageAdmission.Result.APPLIED, p.execute(p.plan(work)));
            assertEquals(1, work.constructions); assertEquals(1, work.callbacks); assertFalse(work.node.full().isDone());
        }
        assertTrue(p.accesses.stream().anyMatch(a -> a.source() == p.x() && a.target() == z));
        assertEquals(4, p.f.sounds); assertEquals(8, p.f.inserted.size()); p.cleanup();
    }

    @Test void failedConstructionReceiptPreservesReachedHolderReplacementButCannotBeConsumedOrReplayed() throws Exception {
        var p = new FullNonselfPrototype(false, false); var y = p.y();
        var failure = new IllegalStateException("controlled failure after construction and holder replacement");
        p.afterConstruction = work -> { if (work == y) throw failure; };
        assertFalse(p.prepare(p.plan(p.x())));
        assertSame(y.node.actual(), y.actual); assertNotNull(y.actual); assertEquals(1, y.constructions);
        assertSame(failure, assertThrows(java.util.concurrent.CompletionException.class, y.conversion::join).getCause());
        p.afterConstruction = work -> { };
        assertFalse(p.prepare(p.plan(p.x())));
        assertEquals(ChunkStageAdmission.Result.NOT_READY, p.execute(p.plan(p.x())));
        assertEquals(0, p.x().callbacks); assertEquals(0, y.callbacks); assertEquals(0, p.f.sounds);
        assertFalse(y.node.full().isDone()); assertFalse(p.barrier.tripped());
        p.cleanup(); assertEquals(1, y.cleanups); assertEquals(2, p.releases);
    }

    @Test void unexpectedDependencyAfterCommittedDynamicEffectsCannotExpandOrReplayTheOperation() throws Exception {
        var p = new FullNonselfPrototype(false, false); var x = p.x(); var y = p.y();
        BlockPos block = new BlockPos(16, 8, 8);
        y.node.input().getSection(0).setBlockState(0, 8, 8, Blocks.SHULKER_BOX.defaultBlockState());
        x.node.input().getSection(0).setBlockState(15, 8, 8, Blocks.SHULKER_BOX.defaultBlockState());
        assertTrue(p.prepare(p.plan(x)));
        var discoveries = new java.util.concurrent.atomic.AtomicInteger();
        p.onAccess = access -> {
            if (access.create() && access.present()) {
                discoveries.incrementAndGet();
                assertThrows(ChunkProtocolException.class, () -> p.query(new Object[]{7, 7, ChunkStatus.FULL, false}));
                assertEquals(2, p.active.plan.inputs().size());
            }
        };
        assertEquals(ChunkStageAdmission.Result.FAILED_BARRIER, p.execute(p.plan(x)));
        assertTrue(discoveries.get() > 0, "failure injection must actually be reached after immediate creation");
        var reached = y.actual.getBlockEntities().get(block); assertNotNull(reached); assertTrue(p.f.tickets.hasTickets());
        assertFalse(p.f.scoreboard.getPlayerTeam("amber").getPlayers().isEmpty());
        int sounds = p.f.sounds, inserts = p.f.inserted.size();
        assertNotEquals(ChunkStageAdmission.Result.APPLIED, p.execute(p.plan(x)));
        assertEquals(1, x.callbacks); assertEquals(1, x.constructions); assertEquals(1, y.constructions);
        assertEquals(sounds, p.f.sounds); assertEquals(inserts, p.f.inserted.size());
        assertSame(reached, y.actual.getBlockEntities().get(block));
        assertTrue(x.conversion.isCompletedExceptionally()); assertFalse(x.node.full().isDone());
        p.cleanup();
    }

    @Test void publishedNeighborUsesOriginalReadyIdentityAndIsNeverConstructedAgain() throws Exception {
        var p = new FullNonselfPrototype(false, true);
        assertTrue(p.prepare(p.plan(p.y())));
        assertEquals(ChunkStageAdmission.Result.APPLIED, p.execute(p.plan(p.y())));
        assertEquals(ChunkStageAdmission.Result.APPLIED, p.publish(p.y()));
        LevelChunk ready = p.y().actual; p.accesses.clear();
        assertTrue(p.prepare(p.plan(p.x())));
        assertEquals(ChunkStageAdmission.Result.APPLIED, p.execute(p.plan(p.x())));
        assertFalse(p.accesses.stream().anyMatch(a -> a.target() == p.y()), "ready path does not borrow privately");
        assertSame(ready, p.y().node.full().join().orElse(null)); assertEquals(1, p.y().constructions);
        assertEquals(2, p.f.sounds); assertEquals(4, p.f.inserted.size()); p.cleanup();
    }

    @Test void capacityRefusalKeepsDemandAndReleasesNoInputUntilAuthenticRetirement() throws Exception {
        var p = new FullNonselfPrototype(false, false, 2, 1);
        var x = p.x(); var y = p.y();
        var consumer = p.retainInputs(p.plan(x));
        assertThrows(IllegalStateException.class, () -> p.retainInputs(p.plan(y)));
        var third = FullNestedQueryBoundaryTest.neighbor(p.f, new ChunkPos(2, 0), Tadpole.ticksToBeFrog, 32.2, 8);
        assertThrows(IllegalStateException.class, () -> p.add(third, false, List.of()));
        assertFalse(x.conversion.isDone()); assertFalse(y.conversion.isDone()); assertEquals(0, p.releases);
        assertTrue(p.enqueue(x)); assertFalse(p.enqueue(y));
        consumer.close(); assertEquals(0, p.releases);
        assertTrue(p.advance()); assertTrue(p.enqueue(y)); assertTrue(p.advance());
        assertEquals(1, x.constructions); assertEquals(1, y.constructions);
        p.cleanup(); assertEquals(2, p.releases);
        assertFalse(p.enqueue(x)); assertFalse(p.enqueue(y));
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void shutdownInsideOriginalCallbackDefersAcknowledgementAndNeverPublishesItsResult(boolean reload) throws Exception {
        var p = new FullNonselfPrototype(reload, false); var x = p.x(); var y = p.y();
        var plan = p.plan(x); var consumer = p.retainInputs(plan); assertTrue(p.prepare(plan));
        p.duringCallback = work -> {
            p.shutdown();
            assertFalse(x.retired); assertFalse(y.retired);
            assertFalse(x.conversion.isDone()); assertFalse(y.conversion.isDone());
            assertEquals(2, x.pins); assertEquals(2, y.pins);
            assertSame(y.actual, p.active.use(p.f.level, work.id, y, y.node.holder()));
        };
        assertEquals(ChunkStageAdmission.Result.APPLIED, p.execute(plan), "already-entered original callback finishes before terminal acknowledgement");
        assertEquals(1, p.f.sounds); assertEquals(2, p.f.inserted.size());
        assertTrue(x.retired); assertTrue(y.retired); assertFalse(x.released); assertFalse(y.released);
        assertEquals(ChunkStageAdmission.Result.NOT_READY, p.publish(x));
        assertFalse(x.node.full().isDone()); assertFalse(y.node.full().isDone());
        consumer.close(); assertEquals(2, p.releases); assertSame(x.conversion.join(), x.node.actual());
        assertTrue(x.node.full().isCompletedExceptionally()); assertTrue(y.node.full().isCompletedExceptionally());
        assertTrue(y.conversion.isCompletedExceptionally());
        p.cleanup(); assertEquals(0, p.custody.retainedEntries());
    }

    @Test void holderAppearingInPreviouslyAbsentPartOfFootprintInvalidatesPlanBeforeSideEffects() throws Exception {
        var p = new FullNonselfPrototype(false, false);
        var extra = p.x().entities.getFirst().copy();
        extra.getListOrEmpty("Pos").set(2, net.minecraft.nbt.DoubleTag.valueOf(15.8)); p.x().entities.add(extra);
        var stale = p.plan(p.x()); assertTrue(stale.positions().contains(new ChunkPos(1, 1)));
        assertNull(stale.holders().get(new ChunkPos(1, 1)));
        var node = FullNestedQueryBoundaryTest.neighbor(p.f, new ChunkPos(1, 1), Tadpole.ticksToBeFrog, 16.2, 16.2);
        assertFalse(p.prepare(stale)); assertEquals(0, p.x().constructions); assertFalse(p.lease.hasActiveLease());
        var z = p.add(node, false, List.of()); p.publishRoutes();
        assertTrue(p.prepare(p.plan(p.x())));
        for (var work : p.works.values()) assertEquals(ChunkStageAdmission.Result.APPLIED, p.execute(p.plan(work)));
        assertEquals(1, z.constructions); assertEquals(1, z.callbacks); assertEquals(4, p.f.sounds);
        p.cleanup();
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void shutdownAtConstructionExitRetainsReachedObjectUntilLeaseReleaseAndStartsNoNextConstruction(boolean reload) throws Exception {
        var p = new FullNonselfPrototype(reload, false); var x = p.x(); var y = p.y();
        var plan = p.plan(x);
        p.afterConstruction = work -> {
            p.shutdown(); assertTrue(p.lease.hasActiveLease());
            assertNotNull(work.actual); assertFalse(work.released); assertFalse(work.conversion.isDone());
        };
        assertFalse(p.prepare(plan));
        assertEquals(1, x.constructions); assertEquals(0, y.constructions);
        assertEquals(0, x.callbacks); assertEquals(0, p.f.sounds);
        assertEquals(1, x.cleanups); assertEquals(1, y.cleanups); assertEquals(2, p.releases);
        assertTrue(x.conversion.isCompletedExceptionally()); assertTrue(x.node.full().isCompletedExceptionally());
        assertFalse(p.lease.hasActiveLease()); assertEquals(0, p.custody.retainedEntries());
        assertEquals(ChunkStageAdmission.Result.NOT_READY, p.execute(p.plan(x)));
        p.cleanup();
    }
}
