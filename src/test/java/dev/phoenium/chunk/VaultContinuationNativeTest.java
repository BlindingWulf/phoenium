package dev.phoenium.chunk;

import dev.phoenium.core.*;
import dev.phoenium.failure.FailureBarrier;
import dev.phoenium.ownership.*;
import dev.phoenium.scheduler.*;
import dev.phoenium.service.*;
import dev.phoenium.topology.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.core.*;
import net.minecraft.server.level.*;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.block.entity.vault.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import static dev.phoenium.chunk.FullDecisionWitnessSupport.*;
import static dev.phoenium.service.VaultBlockEntityTickContinuation.Phase.*;
import static org.junit.jupiter.api.Assertions.*;

/** Differential execution of the real 26.2 Vault prefix/setChanged/output loop,
 * with controlled holder readiness and callback mutations. No imitation getter. */
class VaultContinuationNativeTest {
    static final BlockPos POS = new BlockPos(110, -24, 174);
    @BeforeAll static void bootstrap() { BlockEntityChunkQueryDiagnosticTest.bootstrap(); }
    @AfterEach void clearTrace() { VaultContinuationTestHooks.TRACE.remove(); }

    @ParameterizedTest @ValueSource(strings = {"service", "slot", "both"})
    void firstTickDiagnosticDistinguishesMissingRuntimeFromMissingPublication(String missing) throws Exception {
        try (var f = new Fixture(); var tick = f.capture()) {
            if (!missing.equals("slot")) f.open = false;
            if (!missing.equals("service"))
                ((FullHolderEvidence) f.holder(POS)).phoenium$futures().set(ChunkStatus.FULL.getIndex(), null);
            List<String> observations = new ArrayList<>();
            tick.bindingFailure(observation -> observations.add(observation.get()));
            var failure = assertThrows(IllegalStateException.class,
                () -> f.owned(() -> tick.original(() -> fail("invalid binding entered native tick"))));
            assertEquals("Vault tick lacks exact current incarnation", failure.getMessage());
            assertEquals(1, observations.size());
            String observed = observations.getFirst();
            assertTrue(observed.contains("serviceOpen=" + missing.equals("slot")), observed);
            assertTrue(observed.contains("fullSlotPresent=" + missing.equals("service")), observed);
            for (String check : List.of("holderCurrent", "tickerVaultSame", "tickerChunkSame", "installedVaultSame", "topologyCurrent"))
                assertTrue(observed.contains(check + "=true"), observed);
            assertNull(tick.query());
            assertEquals(missing.equals("service"), f.fullSlot(POS) != null, "diagnostic must not supply a slot");
        }
    }

    @Test void firstTickDiagnosticFailureCannotReplaceOriginalFailure() throws Exception {
        try (var f = new Fixture(); var tick = f.capture()) {
            f.open = false;
            tick.bindingFailure(observation -> { throw new AssertionError("diagnostic sink failed"); });
            var failure = assertThrows(IllegalStateException.class,
                () -> f.owned(() -> tick.original(() -> fail("invalid binding entered native tick"))));
            assertEquals("Vault tick lacks exact current incarnation", failure.getMessage());
            assertEquals(0, failure.getSuppressed().length);
        }
    }

    @Test void twoExactSuspensionsMatchNativeOrderWithoutRepeatingPrefix() throws Exception {
        List<String> nativeTrace;
        try (var f = new Fixture()) {
            f.layout(true); f.complete(POS.east(2)); f.complete(POS.south(2));
            f.trace(); f.owned(f.ticker::tick); nativeTrace = List.copyOf(f.events);
            assertFalse(f.sharedDirty()); assertFalse(f.serverDirty()); assertEquals(1, f.updates.size());
        }
        try (var f = new Fixture(); var tick = f.capture()) {
            f.layout(true); f.trace(); f.owned(() -> tick.original(f.ticker::tick));
            assertEquals(READ_SUSPENDED, tick.phase());
            assertEquals(POS.east(2), tick.query().identity().position());
            assertEquals(OutputNeighborSignalContinuation.Pc.SECOND_READ, tick.neighbors().pc());
            assertEquals(1, tick.neighbors().direction());
            assertTrue(f.source.isUnsaved()); assertTrue(f.sharedDirty()); assertTrue(f.updates.isEmpty());
            assertEquals(1, f.events.stream().filter(s -> s.startsWith("unsaved:")).count());
            assertFalse(f.events.contains("read:" + POS.east(2).toShortString()), "suspend BEFORE blocking getter");
            assertFalse(f.base.owner.leases().hasActiveLease()); assertTrue(OwnershipContext.current().isEmpty());
            tick.prepare(); assertEquals(PREPARING, tick.phase());
            var first = tick.query();
            assertNotNull(f.fullSlot(POS.east(2)), "preparation selects the exact FULL subscriber slot");
            assertNull(f.fullSlot(POS.south(2)), "later dependencies are not preselected");
            assertSame(f.world, first.identity().world()); assertEquals(1, first.identity().query());
            assertFalse(first.dependency().isDone());
            f.complete(POS.east(2)); tick.prepare(); assertEquals(PREPARED, tick.phase());
            f.owned(tick::resume);
            assertEquals(READ_SUSPENDED, tick.phase()); assertEquals(POS.south(2), tick.query().identity().position());
            assertEquals(2, tick.query().identity().query()); assertEquals(first.identity().tick(), tick.query().identity().tick());
            assertEquals(2, tick.neighbors().direction()); assertTrue(f.updates.isEmpty());
            first.prepare(); f.owned(tick::resume); // late first completion and duplicate resume are inert
            assertEquals(READ_SUSPENDED, tick.phase());
            f.complete(POS.south(2)); tick.prepare(); f.owned(tick::resume);
            assertEquals(SETTLED, tick.phase());
            f.owned(tick::resume); tick.prepare();
            assertEquals(nativeTrace, f.events);
            assertFalse(f.sharedDirty()); assertFalse(f.serverDirty()); assertEquals(1, f.updates.size());
            assertEquals(0, f.failures.size());
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void earlierCallbackDeterminesLaterDependencyAfterResume(boolean createsConductor) throws Exception {
        try (var f = new Fixture(); var tick = f.capture()) {
            f.layout(false);
            f.put(POS.south(), createsConductor ? Blocks.AIR.defaultBlockState() : Blocks.STONE.defaultBlockState());
            f.callback = pos -> { if (pos.equals(POS.east(2))) f.put(POS.south(), createsConductor ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState()); };
            f.trace(); f.owned(() -> tick.original(f.ticker::tick));
            tick.prepare(); f.complete(POS.east(2)); tick.prepare(); f.owned(tick::resume);
            if (createsConductor) {
                assertEquals(READ_SUSPENDED, tick.phase()); assertEquals(POS.south(2), tick.query().identity().position());
                tick.prepare(); f.complete(POS.south(2)); tick.prepare(); f.owned(tick::resume);
                assertTrue(f.events.contains("read:" + POS.south(2).toShortString()));
            } else {
                assertFalse(f.events.contains("read:" + POS.south(2).toShortString()));
                assertNull(f.fullSlot(POS.south(2)), "unreached second hop selects no FULL slot");
            }
            assertEquals(SETTLED, tick.phase()); assertEquals(1, f.updates.size());
        }
    }

    @ParameterizedTest @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void dirtySuffixMatchesNativeAndObservesCallbackFlagMutation(boolean initiallyShared, boolean afterCallback) throws Exception {
        List<String> nativeTrace;
        try (var f = new Fixture()) {
            f.layout(false); f.complete(POS.east(2)); f.dirty(initiallyShared);
            f.callback = pos -> f.dirty(afterCallback);
            f.trace(); f.owned(f.ticker::tick); nativeTrace = List.copyOf(f.events);
            assertEquals(afterCallback ? 1 : 0, f.updates.size());
        }
        try (var f = new Fixture(); var tick = f.capture()) {
            f.layout(false); f.dirty(initiallyShared); f.callback = pos -> f.dirty(afterCallback);
            f.trace(); f.owned(() -> tick.original(f.ticker::tick));
            assertEquals(READ_SUSPENDED, tick.phase()); assertTrue(f.updates.isEmpty());
            tick.prepare(); f.complete(POS.east(2)); tick.prepare(); f.owned(tick::resume);
            assertEquals(nativeTrace, f.events); assertFalse(f.sharedDirty()); assertFalse(f.serverDirty());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"vault", "map-only-vault", "source-holder", "source-lifetime", "source-slot", "target-holder", "target-lifetime", "target-slot", "topology", "world-close", "shutdown", "cancel"})
    void staleOrCancelledOperationCannotConsumePreparedReceipt(String change) throws Exception {
        try (var f = new Fixture(); var tick = f.capture()) {
            f.layout(false); f.trace(); f.owned(() -> tick.original(f.ticker::tick)); tick.prepare();
            var pending = tick.query(); f.complete(POS.east(2)); tick.prepare(); assertEquals(PREPARED, tick.phase());
            switch (change) {
                case "vault", "map-only-vault" -> {
                    var replacement = new VaultBlockEntity(POS, f.vault.getBlockState()); replacement.setLevel(f.world);
                    f.source.getBlockEntities().put(POS, replacement);
                    if (change.equals("vault")) f.ticker.entity = replacement;
                }
                case "source-holder" -> f.replaceHolder(POS);
                case "target-holder" -> f.replaceHolder(POS.east(2));
                case "source-lifetime" -> f.retireLifetime(POS);
                case "target-lifetime" -> f.retireLifetime(POS.east(2));
                case "target-slot" -> ((FullHolderEvidence) f.holder(POS.east(2))).phoenium$futures().set(ChunkStatus.FULL.getIndex(), CompletableFuture.completedFuture(ChunkResult.of(f.chunks.get(ChunkPos.pack(POS.east(2))))));
                case "source-slot" -> f.publish(POS, f.source);
                case "topology" -> { var gen = new TopologyGeneration(2); f.base.directory.publish(gen, Map.of(), Map.of(f.base.worldId, new RegionRoute(f.base.ownerId, gen, new OwnerEpoch(0)))); }
                case "world-close", "shutdown" -> f.open = false;
                case "cancel" -> tick.retire(CANCELLED);
            }
            if (!change.equals("map-only-vault")) tick.prepare();
            f.owned(tick::resume);
            assertTrue(Set.of(STALE, SHUTDOWN, CANCELLED).contains(tick.phase()), change + ": " + tick.phase());
            var trace = List.copyOf(f.events); pending.prepare(); f.owned(tick::resume);
            assertEquals(trace, f.events); assertTrue(f.sharedDirty()); assertTrue(f.updates.isEmpty());
        }
    }

    @Test void realOwnerFreeFenceAndDispatcherResumeBlockEntityKind() throws Exception {
        try (var f = new Fixture(); var tick = f.capture()) {
            f.layout(true); f.trace();
            f.dispatcher.executeAndDrain(f.key, RegionalServiceKind.BLOCK_ENTITY_TICK, ignored -> tick.original(f.ticker::tick));
            f.loop.schedule(() -> { f.ownerFree(); assertEquals(POS.east(2), tick.query().identity().position()); f.complete(POS.east(2)); });
            f.loop.schedule(() -> { f.ownerFree(); assertEquals(POS.south(2), tick.query().identity().position()); f.complete(POS.south(2)); });
            int[] resumes = {0};
            tick.finish(f.dispatcher, (execution, body) -> {
                assertEquals(RegionalServiceKind.BLOCK_ENTITY_TICK, execution.kind());
                assertTrue(f.base.owner.leases().hasActiveLease()); resumes[0]++; body.run();
            });
            assertEquals(2, resumes[0]); assertEquals(SETTLED, tick.phase()); assertEquals(1, f.updates.size()); f.ownerFree();
        }
    }

    @Test void failedDependencyPreservesCauseRetiresWithoutSuffixOrHangingWork() throws Exception {
        try (var f = new Fixture(); var tick = f.capture()) {
            f.layout(false); f.trace(); f.owned(() -> tick.original(f.ticker::tick));
            tick.prepare(); var pending = tick.query();
            var original = new IllegalArgumentException("controlled FULL failure");
            f.fullSlot(POS.east(2)).completeExceptionally(original);
            assertSame(original, assertThrows(IllegalArgumentException.class, () -> tick.finish(f.dispatcher, (e, b) -> b.run())));
            assertEquals(FAILED, tick.phase()); assertSame(original, tick.failure()); assertFalse(pending.current());
            assertTrue(f.updates.isEmpty()); assertTrue(f.sharedDirty()); f.ownerFree();
            f.dispatcher.executeAndDrain(f.key, RegionalServiceKind.BLOCK_ENTITY_TICK, ignored -> {});
        }
    }

    @Test void nativeStateTransitionPrefixAndDistinctSuffixStatesAreRetainedOnce() throws Exception {
        List<String> nativeTrace;
        try (var f = new Fixture()) {
            f.unlocking(); f.layout(false); f.complete(POS.east(2)); f.trace(); f.owned(f.ticker::tick);
            nativeTrace = List.copyOf(f.events);
        }
        try (var f = new Fixture(); var tick = f.capture()) {
            f.unlocking(); f.layout(false); f.trace(); f.owned(() -> tick.original(f.ticker::tick));
            assertEquals(READ_SUSPENDED, tick.phase());
            assertEquals(21L, field(f.vault.getServerData(), VaultServerData.class, "stateUpdatingResumesAt"));
            tick.prepare(); f.complete(POS.east(2)); tick.prepare(); f.owned(tick::resume);
            assertEquals(nativeTrace, f.events);
            var update = f.updates.getFirst();
            assertEquals(VaultState.UNLOCKING, ((BlockState) update.get(1)).getValue(VaultBlock.STATE));
            assertEquals(VaultState.EJECTING, ((BlockState) update.get(2)).getValue(VaultBlock.STATE));
            assertEquals(2, update.get(3));
            assertEquals(1, f.events.stream().filter(e -> e.startsWith("setBlock:")).count());
            assertEquals(1, f.events.stream().filter(e -> e.startsWith("sound:")).count());
        }
    }

    @Test void cleanNativeTickNeverEntersSetChangedOrCreatesDependency() throws Exception {
        try (var f = new Fixture(); var tick = f.capture()) {
            f.layout(true); f.dirty(false); set(f.vault.getServerData(), VaultServerData.class, "isDirty", false);
            f.trace(); f.owned(() -> tick.original(f.ticker::tick));
            assertEquals(SETTLED, tick.phase()); assertNull(tick.query());
            assertEquals(List.of("clock", "clock"), f.events); assertFalse(f.source.isUnsaved());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"world-close", "shutdown", "replacement", "cancel"})
    void retirementInsideNativePollReturnsCleanlyAndLateCompletionIsInert(String reason) throws Exception {
        try (var f = new Fixture(); var tick = f.capture()) {
            f.layout(false); f.trace(); f.owned(() -> tick.original(f.ticker::tick)); tick.prepare();
            var pending = tick.query(); var slot = f.fullSlot(POS.east(2));
            f.loop.schedule(() -> {
                f.ownerFree();
                if (reason.equals("replacement")) f.replaceHolder(POS.east(2));
                else if (reason.equals("cancel")) tick.retire(CANCELLED);
                else f.open = false;
            });
            tick.finish(f.dispatcher, (execution, body) -> fail("retired operation resumed"));
            assertTrue(Set.of(STALE, SHUTDOWN, CANCELLED).contains(tick.phase()));
            assertNull(tick.failure()); slot.complete(ChunkResult.of(f.chunk(POS.east(2)))); pending.prepare();
            f.owned(tick::resume); assertTrue(f.updates.isEmpty()); assertTrue(f.sharedDirty()); f.ownerFree();
        }
    }

    static final class Loop extends BlockableEventLoop<Runnable> implements ManagedBlockPollTurnFence.Loop {
        final ManagedBlockPollTurnFence fence = new ManagedBlockPollTurnFence(Thread.currentThread());
        int polls;
        Loop() { super("vault-test", true); }
        @Override public Runnable wrapRunnable(Runnable task) { return task; }
        @Override protected boolean shouldRun(Runnable task) { return true; }
        @Override protected Thread getRunningThread() { return Thread.currentThread(); }
        @Override protected boolean pollTask() { assertTrue(++polls < 20, "continuation hung"); return fence.poll(super::pollTask); }
        @Override public ManagedBlockPollTurnFence phoenium$naturalSpawnFence() { return fence; }
    }

    static final class Fixture implements AutoCloseable {
        final FullRegistrationFixture base = new FullRegistrationFixture(true);
        final List<String> events = new ArrayList<>();
        final List<List<Object>> updates = new ArrayList<>();
        final List<BlockEntityChunkQueryDiagnostic.Failure> failures = new ArrayList<>();
        final BlockEntityChunkQueryDiagnostic diagnostic = new BlockEntityChunkQueryDiagnostic(failures::add);
        final Map<Long, LevelChunk> chunks = new HashMap<>();
        final ServerLevel world;
        final LevelChunk source;
        final VaultBlockEntity vault;
        final Ticker ticker = new Ticker();
        final RoutingKey key = new RoutingKey(base.worldId, 6, 10);
        final RegionalServiceDispatcher dispatcher;
        final Loop loop = new Loop();
        Consumer<BlockPos> callback = pos -> {};
        boolean open = true;
        Fixture() throws Exception {
            var dimension = new DimensionType(false, true, false, false, 1, -64, 384, 384, HolderSet.direct(), 0, null, null, null, null, HolderSet.direct(), Optional.empty());
            world = stub(ServerLevel.class, List.of(
                new Answer(ServerLevel.class.getMethod("getWorldBorder"), args -> new net.minecraft.world.level.border.WorldBorder()),
                new Answer(ServerLevel.class.getMethod("areEntitiesLoaded", long.class), args -> true),
                new Answer(ServerLevel.class.getMethod("updatePOIOnBlockStateChange", BlockPos.class, BlockState.class, BlockState.class), args -> null),
                new Answer(ServerLevel.class.getMethod("dimensionType"), args -> dimension),
                new Answer(ServerLevel.class.getMethod("getChunkSource"), args -> base.world.cache),
                new Answer(ServerLevel.class.getMethod("getServer"), args -> base.world.level.getServer()),
                new Answer(ServerLevel.class.getMethod("palettedContainerFactory"), args -> base.world.level.palettedContainerFactory()),
                new Answer(ServerLevel.class.getMethod("getGameTime"), args -> { events.add("clock"); return 1L; }),
                new Answer(ServerLevel.class.getMethod("setBlock", BlockPos.class, BlockState.class, int.class), args -> {
                    events.add("setBlock:" + args[1] + ":" + args[2]); return true;
                }),
                new Answer(ServerLevel.class.getMethod("playSound", net.minecraft.world.entity.Entity.class, BlockPos.class, net.minecraft.sounds.SoundEvent.class, net.minecraft.sounds.SoundSource.class), args -> {
                    events.add("sound:" + args[2]); return null;
                }),
                new Answer(ServerLevel.class.getMethod("sendBlockUpdated", BlockPos.class, BlockState.class, BlockState.class, int.class), args -> {
                    updates.add(List.of(args)); events.add("update:" + args[1] + ":" + args[2] + ":" + args[3]); return null;
                }),
                new Answer(ServerLevel.class.getMethod("neighborChanged", BlockState.class, BlockPos.class, Block.class, net.minecraft.world.level.redstone.Orientation.class, boolean.class), args -> {
                    assertSame(Blocks.VAULT, args[2]); assertNull(args[3]); assertEquals(false, args[4]);
                    BlockPos pos = (BlockPos) args[1]; events.add("callback:" + pos.toShortString()); callback.accept(pos); return null;
                })));
            set(world, ServerLevel.class, "chunkSource", base.world.cache); set(base.world.cache, ServerChunkCache.class, "level", world);
            set(base.world.cache, ServerChunkCache.class, "mainThreadProcessor", allocate(Class.forName("net.minecraft.server.level.ServerChunkCache$MainThreadExecutor")));
            set(base.world.cache.chunkMap, ChunkMap.class, "mainThreadExecutor", loop);
            source = chunk(POS); publish(POS, source); source.tryMarkSaved();
            // Register exact holder/lifecycle metadata without requesting potential dependencies.
            chunk(POS.east(2)); chunk(POS.south(2));
            vault = new VaultBlockEntity(POS, Blocks.VAULT.defaultBlockState()); vault.setLevel(world);
            set(vault.getServerData(), VaultServerData.class, "stateUpdatingResumesAt", Long.MAX_VALUE);
            set(vault.getServerData(), VaultServerData.class, "isDirty", true); dirty(true);
            source.getBlockEntities().put(POS, vault); ticker.entity = vault;
            var regions = Map.of(base.ownerId, base.owner, base.otherId, base.other);
            var scheduler = new RegionScheduler(regions, base.directory, new FailureBarrier());
            dispatcher = new RegionalServiceDispatcher("vault-native-test", 1, base.directory, regions, new OwnerGateway(base.directory, regions, scheduler), scheduler);
        }
        void layout(boolean south) {
            put(POS.north(), Blocks.COMPARATOR.defaultBlockState()); put(POS.east(), Blocks.STONE.defaultBlockState());
            put(POS.east(2), Blocks.COMPARATOR.defaultBlockState());
            put(POS.south(), south ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
            put(POS.south(2), Blocks.COMPARATOR.defaultBlockState()); put(POS.west(), Blocks.COMPARATOR.defaultBlockState());
        }
        void trace() { events.clear(); VaultContinuationTestHooks.TRACE.set(events::add); }
        void unlocking() throws Exception {
            set(vault, BlockEntity.class, "blockState", vault.getBlockState().setValue(VaultBlock.STATE, VaultState.UNLOCKING));
            set(vault.getServerData(), VaultServerData.class, "stateUpdatingResumesAt", 0L);
        }
        void dirty(boolean value) { try { set(vault.getSharedData(), VaultSharedData.class, "isDirty", value); } catch (Exception e) { throw new AssertionError(e); } }
        boolean sharedDirty() throws Exception { return (boolean) field(vault.getSharedData(), VaultSharedData.class, "isDirty"); }
        boolean serverDirty() throws Exception { return (boolean) field(vault.getServerData(), VaultServerData.class, "isDirty"); }
        ChunkHolder holder(BlockPos pos) { return base.holder(ChunkPos.containing(pos)); }
        LevelChunk chunk(BlockPos pos) {
            return chunks.computeIfAbsent(ChunkPos.pack(pos), ignored -> {
                var holder = holder(pos); lifetime(holder);
                return new LevelChunk(world, new ProtoChunk(ChunkPos.containing(pos), UpgradeData.EMPTY, world, world.palettedContainerFactory(), null), null);
            });
        }
        void lifetime(ChunkHolder holder) {
            var id = base.services.lifecycles().activateHolder(1, new RoutingKey(base.worldId, holder.getPos().x(), holder.getPos().z()), holder);
            ((FullHolderEvidence) holder).phoenium$lifetime(new ChunkHolderLifetime(base.services, id, holder));
        }
        void publish(BlockPos pos, LevelChunk value) { ((FullHolderEvidence) holder(pos)).phoenium$futures().set(ChunkStatus.FULL.getIndex(), CompletableFuture.completedFuture(ChunkResult.of(value))); }
        CompletableFuture<ChunkResult<ChunkAccess>> fullSlot(BlockPos pos) { return ((FullHolderEvidence) holder(pos)).phoenium$futures().get(ChunkStatus.FULL.getIndex()); }
        void complete(BlockPos pos) { var slot = fullSlot(pos); if (slot == null) publish(pos, chunk(pos)); else assertTrue(slot.complete(ChunkResult.of(chunk(pos)))); }
        void put(BlockPos pos, BlockState state) { var chunk = chunk(pos); chunk.getSection(chunk.getSectionIndex(pos.getY())).setBlockState(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15, state); }
        void replaceHolder(BlockPos pos) { base.holders.remove(ChunkPos.pack(pos)); lifetime(holder(pos)); }
        void retireLifetime(BlockPos pos) { var life = ((FullHolderEvidence) holder(pos)).phoenium$lifetime(); base.services.lifecycles().retireHolder(life.lifecycle(), life.holder()); }
        void ownerFree() { assertTrue(OwnershipContext.current().isEmpty()); assertFalse(base.owner.leases().hasClaimOrSelection()); assertFalse(base.other.leases().hasClaimOrSelection()); }
        VaultBlockEntityTickContinuation capture() { return new VaultBlockEntityTickContinuation(world, ticker, base.directory, key, 7, () -> open, this::ownerFree); }
        void owned(Runnable body) {
            try (var lease = base.owner.leases().acquire(); var owner = OwnershipContext.enter(base.ownerId, lease.epoch(), OperationCategory.TICK);
                 var diagnosticScope = diagnostic.enter(world, "minecraft:vault", POS, new RegionalServiceDispatcher.Execution("test", 1, 7, RegionalServiceKind.BLOCK_ENTITY_TICK, key, base.owner), base.directory.snapshot().generation())) { body.run(); }
        }
        @Override public void close() { VaultContinuationTestHooks.TRACE.remove(); base.stop(); }
        final class Ticker implements TickingBlockEntity, LocalNativeTickerAccess {
            VaultBlockEntity entity;
            @Override public void tick() { VaultBlockEntity.Server.tick(world, POS, entity.getBlockState(), entity.getConfig(), entity.getServerData(), entity.getSharedData()); }
            @Override public boolean isRemoved() { return entity.isRemoved(); }
            @Override public BlockPos getPos() { return POS; }
            @Override public String getType() { return "minecraft:vault"; }
            @Override public BlockEntity phoenium$localBlockEntity() { return entity; }
            @Override public LevelChunk phoenium$localChunk() { return source; }
        }
    }
}
