package dev.phoenium.chunk;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.animal.frog.Tadpole;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static dev.phoenium.chunk.FullDecisionWitnessSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Concrete D3 closure witnesses. Original FULL, saved readers, conversion, collision iteration,
 * dynamic shape, Level.getBlockEntity, ticket insertion and holder future selection execute.
 * The final getter boundary is controlled: exact direct-self access or a bounded stop BEFORE
 * managedBlock. These are neither a production FULL binding nor a live hung-server test.
 */
final class FullNestedQueryBoundaryTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    record Query(ChunkPos target, boolean create, CompletableFuture<ChunkResult<ChunkAccess>> selected,
                 List<String> callers) { }
    record Node(ChunkHolder holder, ProtoChunk input, CompletableFuture<ChunkResult<ChunkAccess>> full) {
        ChunkPos pos() { return holder.getPos(); }
        LevelChunk actual() {
            return ((ImposterProtoChunk) holder.getChunkIfPresentUnchecked(ChunkStatus.FULL.getParent())).getWrapped();
        }
    }
    static final class DependencyStop extends RuntimeException {
        DependencyStop(String message) { super(message); }
    }

    @Test void localNonAirDynamicShapeReachesFullTrueAndCommitsOriginalUnknownTicket() throws Exception {
        var f = new FullTadpoleDecisionTest.Fixture(Tadpole.ticksToBeFrog);
        Node node = primary(f);
        BlockPos block = new BlockPos(8, 8, 8);
        var state = Blocks.SHULKER_BOX.defaultBlockState().setValue(ShulkerBoxBlock.FACING, Direction.EAST);
        // Fixture preparation before conversion; original block/entity operations run during the query.
        f.proto.getSection(0).setBlockState(8, 8, 8, state);
        var box = new ShulkerBoxBlockEntity(block, state);
        set(box, ShulkerBoxBlockEntity.class, "progress", 0.5F);
        f.proto.setBlockEntity(box);
        List<Query> queries = new ArrayList<>();
        f.queryEndpoint = args -> {
            Query q = select(f, args); queries.add(q);
            assertEquals(node.pos(), q.target()); assertSame(node.full(), q.selected());
            assertFalse(node.full().isDone()); assertNull(node.holder().getChunkIfPresent(ChunkStatus.FULL));
            if (q.create()) {
                assertTrue(q.callers().contains("getBlockEntity"));
                assertTrue(q.callers().contains("getShape"));
                assertFalse(f.tickets.getTickets(q.target().pack()).isEmpty(), "ticket prefix precedes selected future");
            }
            return node.actual();
        };
        LevelChunk result = f.run();
        assertSame(box, result.getBlockEntity(block));
        assertTrue(box.getBoundingBox(state).maxX > 1, "shape actually depends on mutable non-air block entity state");
        assertTrue(queries.stream().anyMatch(Query::create), "empty-air-only fixtures cannot reach this query");
        assertTrue(queries.stream().anyMatch(q -> !q.create() && q.callers().contains("getChunkForCollisions")));
        assertEquals(1, f.sounds); assertEquals(2, f.inserted.size());
        assertEquals(TicketType.UNKNOWN, f.tickets.getTickets(ChunkPos.ZERO.pack()).getFirst().getType());
        assertEquals(ChunkLevel.byStatus(ChunkStatus.FULL), f.tickets.getTickets(ChunkPos.ZERO.pack()).getFirst().getTicketLevel());
        assertFalse(f.fullSlot.isDone());
    }

    @Test void originalImmediateBlockEntityCreationOccursBeforeLaterRegistration() throws Exception {
        var f = new FullTadpoleDecisionTest.Fixture(Tadpole.ticksToBeFrog);
        Node node = primary(f);
        BlockPos block = new BlockPos(8, 8, 8);
        f.proto.getSection(0).setBlockState(8, 8, 8, Blocks.SHULKER_BOX.defaultBlockState());
        List<Query> queries = new ArrayList<>();
        List<Boolean> presentBeforeQuery = new ArrayList<>();
        f.queryEndpoint = args -> {
            Query q = select(f, args); queries.add(q);
            assertSame(node.full(), q.selected());
            if (q.create()) {
                assertEquals(false, uncheckedField(node.actual(), LevelChunk.class, "loaded"));
                presentBeforeQuery.add(node.actual().getBlockEntities().containsKey(block));
            }
            return node.actual();
        };
        LevelChunk result = f.run();
        assertInstanceOf(ShulkerBoxBlockEntity.class, result.getBlockEntity(block));
        assertEquals(List.of(false, true), presentBeforeQuery, "first search creates; the original fallback search reuses");
        assertEquals(2, queries.stream().filter(Query::create).count());
        assertEquals(1, ((List<?>) field(f.level, net.minecraft.world.level.Level.class, "blockEntityTickers")).size());
        assertEquals(1, f.sounds); assertFalse(f.fullSlot.isDone());
    }

    @Test void readyNeighborUsesItsOriginalPublishedResultAndDoesNotNeedPrivateNeighborAccess() throws Exception {
        var f = new FullTadpoleDecisionTest.Fixture(Tadpole.ticksToBeFrog);
        Node x = primary(f); position(x.input(), 15.8);
        Node y = neighbor(f, Tadpole.ticksToBeFrog - 1, 16.2);
        LevelChunk ready = new LevelChunk(f.level, y.input(), null);
        publish(y, ready);
        List<Query> queries = new ArrayList<>();
        f.queryEndpoint = args -> {
            Query q = select(f, args); queries.add(q);
            if (q.target().equals(x.pos())) { assertSame(x.full(), q.selected()); return x.actual(); }
            assertSame(y.full(), q.selected()); assertTrue(q.selected().isDone());
            return q.selected().join().orElse(null);
        };
        f.run();
        assertTrue(queries.stream().anyMatch(q -> q.target().equals(y.pos())));
        assertEquals(1, f.sounds); assertEquals(2, f.inserted.size());
        assertFalse(x.full().isDone()); assertSame(ready, y.holder().getChunkIfPresent(ChunkStatus.FULL));
    }

    @Test void genuinelyAbsentNeighborPreservesOriginalUnloadedResultWithoutTicketCreation() throws Exception {
        var f = new FullTadpoleDecisionTest.Fixture(Tadpole.ticksToBeFrog);
        Node x = primary(f); position(x.input(), 15.8);
        List<Query> absent = new ArrayList<>();
        f.queryEndpoint = args -> {
            Query q = select(f, args);
            if (q.target().equals(x.pos())) return x.actual();
            absent.add(q); assertFalse(q.create());
            assertTrue(q.selected().isDone()); assertFalse(q.selected().join().isSuccess());
            return q.selected().join().orElse(null);
        };
        f.run();
        assertFalse(absent.isEmpty()); assertFalse(f.tickets.hasTickets());
        assertEquals(1, f.sounds); assertEquals(2, f.inserted.size()); assertFalse(x.full().isDone());
    }

    @Test void twoSavedBoundaryConversionsSelectEachOthersUnfinishedFullSlotsDespiteDirectSelfAccess() throws Exception {
        witnessCycle(false);
    }

    @Test void persistedReloadPairHasTheSameNonSelfCycleWithBothActualChunksAlreadyConstructed() throws Exception {
        witnessCycle(true);
    }

    private static void witnessCycle(boolean reload) throws Exception {
        var f = new FullTadpoleDecisionTest.Fixture(Tadpole.ticksToBeFrog);
        Node protoX = primary(f); position(protoX.input(), 15.8);
        Node protoY = neighbor(f, Tadpole.ticksToBeFrog, 16.2);
        Node x = reload ? reload(f, protoX) : protoX;
        Node y = reload ? reload(f, protoY) : protoY;
        List<String> edges = new ArrayList<>();
        AtomicReference<Node> active = new AtomicReference<>(x);
        AtomicReference<LevelChunk> ySupplierResult = new AtomicReference<>();
        f.queryEndpoint = args -> {
            Node source = active.get();
            Query q = select(f, args);
            assertFalse(q.create());
            assertFalse(x.full().isDone()); assertFalse(y.full().isDone());
            if (q.target().equals(source.pos())) {
                assertSame(source.full(), q.selected());
                return source.actual(); // Only the approved direct-self boundary is supplied.
            }
            assertTrue(q.callers().contains("getChunkForCollisions"));
            assertTrue(q.callers().contains("fudgePositionAfterSizeChange"));
            assertTrue(q.callers().contains("ageUp"));
            if (source == x) {
                assertSame(y.full(), q.selected()); edges.add("X collision -> Y FULL slot");
                // Execute Y's original queued supplier to witness its dependency while X is still
                // on this stack. No managedBlock, thread timeout or fabricated neighbor is used.
                active.set(y);
                try { ySupplierResult.set(f.run(y.holder(), y.input())); }
                finally { active.set(x); }
                throw new DependencyStop("bounded X stop: Y publication requires returning through X-dependent Y");
            }
            assertSame(x.full(), q.selected()); edges.add("Y collision -> X FULL slot");
            throw new DependencyStop("bounded Y stop: X supplier is still active");
        };
        LevelChunk xSupplierResult = f.run(x.holder(), x.input());
        assertEquals(List.of("X collision -> Y FULL slot", "Y collision -> X FULL slot"), edges);
        assertSame(x.actual(), xSupplierResult); assertSame(y.actual(), ySupplierResult.get());
        assertFalse(x.full().isDone()); assertFalse(y.full().isDone());
        assertNull(x.holder().getChunkIfPresent(ChunkStatus.FULL));
        assertNull(y.holder().getChunkIfPresent(ChunkStatus.FULL));
        assertEquals(0, f.sounds); assertTrue(f.inserted.isEmpty()); assertEquals(2, f.executions);
        assertEquals(2, f.scoreboard.getPlayerTeam("amber").getPlayers().size(), "nested stops keep conversion team prefixes");
        // EntityType catches the injected RuntimeException in each supplier. Those diagnostic
        // returns are NOT unmodified successful progress and MUST NOT authorize publication.
    }

    static Node primary(FullTadpoleDecisionTest.Fixture f) { return new Node(f.holder, f.proto, f.fullSlot); }
    static Node reload(FullTadpoleDecisionTest.Fixture f, Node node) throws Exception {
        Method postLoad = net.minecraft.world.level.chunk.storage.SerializableChunkData.class.getDeclaredMethod(
                "postLoadChunk", ServerLevel.class, List.class, List.class);
        postLoad.setAccessible(true);
        var processor = (LevelChunk.PostLoadProcessor) postLoad.invoke(null, f.level, node.input().getEntities(), List.of());
        LevelChunk actual = new LevelChunk(f.level, node.input(), processor);
        var input = new ImposterProtoChunk(actual, false);
        @SuppressWarnings("unchecked") var slots = (AtomicReferenceArray<CompletableFuture<ChunkResult<ChunkAccess>>>)
                field(node.holder(), GenerationChunkHolder.class, "futures");
        for (ChunkStatus status : ChunkStatus.getStatusList()) if (status != ChunkStatus.FULL)
            slots.set(status.getIndex(), CompletableFuture.completedFuture(ChunkResult.of(input)));
        return new Node(node.holder(), input, node.full());
    }
    static void position(ProtoChunk input, double x) {
        position(input, x, 8);
    }
    static void position(ProtoChunk input, double x, double z) {
        ListTag pos = new ListTag(); pos.add(DoubleTag.valueOf(x)); pos.add(DoubleTag.valueOf(8)); pos.add(DoubleTag.valueOf(z));
        input.getEntities().getFirst().put("Pos", pos);
    }
    static Node neighbor(FullTadpoleDecisionTest.Fixture f, int age, double x) throws Exception {
        return neighbor(f, new ChunkPos(1, 0), age, x, 8);
    }
    static Node neighbor(FullTadpoleDecisionTest.Fixture f, ChunkPos pos, int age, double x, double z) throws Exception {
        ProtoChunk input = new ProtoChunk(pos, UpgradeData.EMPTY, f.level, f.level.palettedContainerFactory(), null);
        input.setPersistedStatus(ChunkStatus.FULL.getParent()); input.setInhabitedTime(900000L);
        var tag = f.proto.getEntities().getFirst().copy(); tag.putInt("Age", age); input.addEntity(tag); position(input, x, z);
        ChunkHolder holder = stub(ChunkHolder.class, List.of(
                new Answer(ChunkHolder.class.getMethod("getTicketLevel"), args -> 33),
                new Answer(ChunkHolder.class.getMethod("getFullStatus"), args -> FullChunkStatus.FULL)));
        set(holder, GenerationChunkHolder.class, "pos", pos);
        set(holder, GenerationChunkHolder.class, "highestAllowedStatus", ChunkStatus.FULL);
        set(holder, GenerationChunkHolder.class, "startedWork", new AtomicReference<>(ChunkStatus.FULL));
        var task = allocate(ChunkGenerationTask.class); set(task, ChunkGenerationTask.class, "targetStatus", ChunkStatus.FULL);
        set(holder, GenerationChunkHolder.class, "task", new AtomicReference<>(task));
        var full = new CompletableFuture<ChunkResult<ChunkAccess>>();
        var slots = new AtomicReferenceArray<CompletableFuture<ChunkResult<ChunkAccess>>>(ChunkStatus.getStatusList().size());
        for (ChunkStatus status : ChunkStatus.getStatusList())
            slots.set(status.getIndex(), status == ChunkStatus.FULL ? full : CompletableFuture.completedFuture(ChunkResult.of(input)));
        set(holder, GenerationChunkHolder.class, "futures", slots); f.visibleHolders.put(pos.pack(), holder);
        return new Node(holder, input, full);
    }
    static Query select(FullTadpoleDecisionTest.Fixture f, Object[] args) {
        assertSame(ChunkStatus.FULL, args[2]);
        try {
            Method method = ServerChunkCache.class.getDeclaredMethod("getChunkFutureMainThread", int.class, int.class, ChunkStatus.class, boolean.class);
            method.setAccessible(true);
            @SuppressWarnings("unchecked") var future = (CompletableFuture<ChunkResult<ChunkAccess>>) method.invoke(f.cache, args);
            return new Query(new ChunkPos((int) args[0], (int) args[1]), (boolean) args[3], future,
                    StackWalker.getInstance().walk(frames -> frames.map(StackWalker.StackFrame::getMethodName).toList()));
        } catch (InvocationTargetException failure) { throw new AssertionError("original selector failed", failure.getCause()); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    static void publish(Node node, LevelChunk result) throws Exception {
        Method method = GenerationChunkHolder.class.getDeclaredMethod("completeFuture", ChunkStatus.class, ChunkAccess.class);
        method.setAccessible(true); method.invoke(node.holder(), ChunkStatus.FULL, result);
    }
    static Object uncheckedField(Object value, Class<?> type, String name) {
        try { return field(value, type, name); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
}
