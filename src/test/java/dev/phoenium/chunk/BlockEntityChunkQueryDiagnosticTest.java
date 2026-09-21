package dev.phoenium.chunk;

import dev.phoenium.ownership.*;
import dev.phoenium.service.*;
import dev.phoenium.topology.RoutingKey;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.*;
import net.minecraft.server.level.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.vault.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import org.junit.jupiter.api.*;
import static dev.phoenium.chunk.FullDecisionWitnessSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Original Vault.tick -> setChanged -> output loop -> Level.getBlockState -> cache getter.
 * World clock/config and holder generation are controlled, not a replay of the read loop. */
final class BlockEntityChunkQueryDiagnosticTest {
    @BeforeAll static void bootstrap() {
        FullProductionRegistrationTest.bootstrap();
        net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_INITIALIZERS
                .build(net.minecraft.data.registries.VanillaRegistries.createLookup())
                .forEach(net.minecraft.core.component.DataComponentInitializers.PendingComponents::apply);
    }
    static final BlockPos SOURCE = new BlockPos(109, -24, 174);
    static final ChunkPos SOURCE_CHUNK = new ChunkPos(6, 10), TARGET_CHUNK = new ChunkPos(6, 11);

    @Test void actualVaultDirtyPrefixSucceedsThenExactSouthFullReadHitsGuard() throws Exception {
        try (var f = new Fixture()) {
            f.put(SOURCE.south(), Blocks.STONE.defaultBlockState());
            var vault = new VaultBlockEntity(SOURCE, Blocks.VAULT.defaultBlockState().setValue(VaultBlock.OMINOUS, true));
            vault.setLevel(f.world);
            set(vault.getServerData(), VaultServerData.class, "stateUpdatingResumesAt", Long.MAX_VALUE);
            set(vault.getSharedData(), VaultSharedData.class, "isDirty", true);
            f.source.tryMarkSaved();
            ChunkProtocolException failure;
            try (var lease = f.base.owner.leases().acquire();
                 var owner = OwnershipContext.enter(f.base.ownerId, lease.epoch(), OperationCategory.TICK);
                 var scope = f.diagnostic.enter(f.world, "minecraft:vault", SOURCE, f.execution, f.base.directory.snapshot().generation())) {
                failure = assertThrows(ChunkProtocolException.class, () -> VaultBlockEntity.Server.tick(
                        f.world, SOURCE, vault.getBlockState(), vault.getConfig(), vault.getServerData(), vault.getSharedData()));
                assertEquals("unfinished synchronous chunk dependency inside an owner transaction", failure.getMessage());
                assertTrue(f.source.isUnsaved(), "native own-chunk lookup and dirty mutation committed before suspension point");
                assertEquals(0, f.updates, "native Vault suffix has not sent its block update");
                assertEquals(true, field(vault.getSharedData(), VaultSharedData.class, "isDirty"));
                // Repeating a failing READ proves bounded telemetry; never replay the tick.
                assertThrows(ChunkProtocolException.class, () -> f.world.getBlockState(SOURCE.south(2)));
            }
            assertEquals(1, f.failures.size());
            var receipt = f.failures.getFirst();
            assertEquals(SOURCE, receipt.sourcePosition());
            assertEquals(SOURCE.south(2), receipt.queriedPosition());
            assertEquals(TARGET_CHUNK, receipt.queriedChunk());
            assertEquals(SOURCE_CHUNK, receipt.sourceChunk());
            assertEquals(ChunkStatus.FULL.toString(), receipt.requestedStatus());
            assertTrue(receipt.create()); assertFalse(receipt.sameChunk());
            assertFalse(receipt.typedCurrentInputReceipt());
            assertEquals("BLOCK_ENTITY_TICK", receipt.service());
            assertEquals("minecraft:vault", receipt.type());
            assertEquals(f.execution.worldInstance(), receipt.worldInstance());
            assertEquals(91, receipt.serviceTurn());
            assertEquals(f.base.directory.snapshot().generation(), receipt.topology());
            assertEquals(f.sourceLifetime.lifecycle(), receipt.sourceHolder().incarnation());
            assertEquals(f.targetLifetime.lifecycle(), receipt.queriedHolder().incarnation());
            assertNotEquals(receipt.sourceHolder().identity(), receipt.queriedHolder().identity());
            assertEquals("READY", receipt.sourceHolder().readiness());
            assertTrue(receipt.sourceHolder().incarnationCurrent());
            assertEquals("PENDING", receipt.selectedReadiness());
            assertTrue(receipt.selectedIsHolderFullSlot());
            assertEquals(receipt.queriedHolder().fullSlot(), receipt.selectedFuture());
            assertEquals(receipt.queriedHolder(), receipt.holderAtFailure());
            assertTrue(receipt.queriedHolder().incarnationCurrent());
            assertNotNull(receipt.owner());
            assertEquals(f.base.ownerId, receipt.owner().region());
            assertEquals(OperationCategory.TICK, receipt.owner().category());
            assertEquals(failure.getClass().getName(), receipt.failureClass());
            assertEquals(failure.getMessage(), receipt.failureMessage());
            assertFalse(f.base.owner.leases().hasActiveLease());
            System.out.println("PHOENIUM_BLOCK_ENTITY_CHUNK_QUERY_FIRST_FAILURE deterministic=" + receipt);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void diagnosticRethrowsIdenticalUnmodifiedFailureEvenWhenSinkFails(boolean sinkFails) throws Exception {
        try (var f = new Fixture()) {
            var original = new ChunkProtocolException("original query failure");
            var existingSuppressed = new IllegalStateException("already present");
            original.addSuppressed(existingSuppressed);
            var stack = original.getStackTrace();
            int[] reports = {0};
            var diagnostic = new BlockEntityChunkQueryDiagnostic(failure -> {
                reports[0]++;
                if (sinkFails) throw new IllegalStateException("diagnostic sink failure");
            });
            try (var scope = diagnostic.enter(f.world, "minecraft:vault", SOURCE, f.execution,
                    f.base.directory.snapshot().generation())) {
                for (int attempt = 0; attempt < 2; attempt++) {
                    assertSame(original, assertThrows(ChunkProtocolException.class, () ->
                            BlockEntityChunkQueryDiagnostic.blockState(f.world, SOURCE.south(2), () -> {
                                BlockEntityChunkQueryDiagnostic.chunk(f.world, 6, 11, ChunkStatus.FULL, true,
                                        () -> { throw original; });
                                return fail("a failed query must not supply substitute block data");
                            })));
                }
                BlockState nativeResult = Blocks.STONE.defaultBlockState();
                assertSame(nativeResult, BlockEntityChunkQueryDiagnostic.blockState(f.world, SOURCE, () -> nativeResult));
                assertSame(f.source, BlockEntityChunkQueryDiagnostic.chunk(f.world, 6, 10, ChunkStatus.FULL, true,
                        () -> f.source));
            }
            assertEquals(1, reports[0]);
            assertArrayEquals(new Throwable[]{existingSuppressed}, original.getSuppressed());
            assertArrayEquals(stack, original.getStackTrace());
            assertNull(original.getCause());
        }
    }

    @Test void earlierNativeComparatorCallbackCanChangeLaterSecondHopSelection() throws Exception {
        try (var f = new Fixture()) {
            var first = Direction.Plane.HORIZONTAL.iterator().next();
            assertEquals(Direction.NORTH, first);
            f.put(SOURCE.north(), Blocks.COMPARATOR.defaultBlockState());
            f.callback = () -> f.put(SOURCE.south(), Blocks.STONE.defaultBlockState());
            assertFalse(f.source.getBlockState(SOURCE.south()).isRedstoneConductor(f.world, SOURCE.south()));
            try (var lease = f.base.owner.leases().acquire();
                 var owner = OwnershipContext.enter(f.base.ownerId, lease.epoch(), OperationCategory.TICK);
                 var scope = f.diagnostic.enter(f.world, "minecraft:vault", SOURCE, f.execution, f.base.directory.snapshot().generation())) {
                assertThrows(ChunkProtocolException.class, () -> f.world.updateNeighbourForOutputSignal(SOURCE, Blocks.VAULT));
            }
            assertEquals(1, f.callbacks);
            assertEquals(SOURCE.south(2), f.failures.getFirst().queriedPosition());
        }
    }

    @Test void nonconductorDoesNotRequestSecondHopOrEmitFailure() throws Exception {
        try (var f = new Fixture()) {
            try (var lease = f.base.owner.leases().acquire();
                 var owner = OwnershipContext.enter(f.base.ownerId, lease.epoch(), OperationCategory.TICK);
                 var scope = f.diagnostic.enter(f.world, "minecraft:vault", SOURCE, f.execution, f.base.directory.snapshot().generation())) {
                f.world.updateNeighbourForOutputSignal(SOURCE, Blocks.VAULT);
            }
            assertTrue(f.failures.isEmpty());
            assertNull(((FullHolderEvidence) f.targetLifetime.holder()).phoenium$futures().get(ChunkStatus.FULL.getIndex()),
                    "no conditional read means no public FULL subscriber slot was selected");
            assertTrue(f.base.world.tickets.getTickets(TARGET_CHUNK.pack()).isEmpty());
        }
    }

    static final class Fixture implements AutoCloseable {
        final FullRegistrationFixture base = new FullRegistrationFixture(true);
        final List<BlockEntityChunkQueryDiagnostic.Failure> failures = new ArrayList<>();
        final BlockEntityChunkQueryDiagnostic diagnostic = new BlockEntityChunkQueryDiagnostic(failures::add);
        final ServerLevel world;
        final LevelChunk source;
        final ChunkHolderLifetime sourceLifetime, targetLifetime;
        final RegionalServiceDispatcher.Execution execution;
        int updates, callbacks;
        Runnable callback = () -> {};
        Fixture() throws Exception {
            var dimension = new DimensionType(false, true, false, false, 1, -64, 384, 384,
                    HolderSet.direct(), 0, null, null, null, null, HolderSet.direct(), Optional.empty());
            world = stub(ServerLevel.class, List.of(
                    new Answer(ServerLevel.class.getMethod("dimensionType"), args -> dimension),
                    new Answer(ServerLevel.class.getMethod("getChunkSource"), args -> base.world.cache),
                    new Answer(ServerLevel.class.getMethod("getServer"), args -> base.world.level.getServer()),
                    new Answer(ServerLevel.class.getMethod("palettedContainerFactory"), args -> base.world.level.palettedContainerFactory()),
                    new Answer(ServerLevel.class.getMethod("getGameTime"), args -> 1L),
                    new Answer(ServerLevel.class.getMethod("sendBlockUpdated", BlockPos.class, BlockState.class, BlockState.class, int.class),
                            args -> { updates++; return null; }),
                    new Answer(ServerLevel.class.getMethod("neighborChanged", BlockState.class, BlockPos.class, Block.class,
                            net.minecraft.world.level.redstone.Orientation.class, boolean.class),
                            args -> { callbacks++; callback.run(); return null; })));
            set(world, ServerLevel.class, "chunkSource", base.world.cache);
            set(base.world.cache, ServerChunkCache.class, "level", world);
            set(base.world.cache, ServerChunkCache.class, "mainThreadProcessor",
                    allocate(Class.forName("net.minecraft.server.level.ServerChunkCache$MainThreadExecutor")));
            source = new LevelChunk(world, new ProtoChunk(SOURCE_CHUNK, UpgradeData.EMPTY, world,
                    world.palettedContainerFactory(), null), null);
            ChunkHolder sourceHolder = base.holder(SOURCE_CHUNK);
            ((FullHolderEvidence) sourceHolder).phoenium$futures().set(ChunkStatus.FULL.getIndex(),
                    CompletableFuture.completedFuture(ChunkResult.of(source)));
            base.start(TARGET_CHUNK, ChunkStatus.FULL); base.drain();
            ChunkHolder targetHolder = base.holder(TARGET_CHUNK);
            sourceLifetime = lifetime(sourceHolder);
            targetLifetime = lifetime(targetHolder);
            execution = new RegionalServiceDispatcher.Execution("vault-diagnostic", 1, 91,
                    RegionalServiceKind.BLOCK_ENTITY_TICK, new RoutingKey(base.worldId, 6, 10), base.owner);
        }
        ChunkHolderLifetime lifetime(ChunkHolder holder) {
            var id = base.services.lifecycles().activateHolder(1,
                    new RoutingKey(base.worldId, holder.getPos().x(), holder.getPos().z()), holder);
            var lifetime = new ChunkHolderLifetime(base.services, id, holder);
            ((FullHolderEvidence) holder).phoenium$lifetime(lifetime);
            return lifetime;
        }
        void put(BlockPos position, BlockState state) {
            source.getSection(source.getSectionIndex(position.getY())).setBlockState(
                    position.getX() & 15, position.getY() & 15, position.getZ() & 15, state);
        }
        @Override public void close() { base.stop(); }
    }
}
