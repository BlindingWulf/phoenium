package dev.phoenium.service;

import dev.phoenium.chunk.*;
import dev.phoenium.core.TopologyGeneration;
import dev.phoenium.mixin.*;
import dev.phoenium.ownership.OwnershipContext;
import dev.phoenium.topology.*;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import net.minecraft.world.level.block.entity.vault.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/** Two-level, single-use continuation for the audited Vault.Server.tick setChanged
 * call only. Native prefix remains native. No throw/yield passes through crash-report
 * handlers, and no Java frame, RNG state, or copied world graph is retained. */
public final class VaultBlockEntityTickContinuation implements AutoCloseable {
    public enum Phase { RUNNING_PREFIX, NEIGHBOR_LOOP, READ_SUSPENDED, PREPARING, PREPARED,
        RESUME_QUEUED, RESUMING_NEIGHBOR_LOOP, SET_CHANGED_COMPLETE, RESUMING_VAULT_SUFFIX,
        SETTLED, FAILED, CANCELLED, STALE, SHUTDOWN }
    private static final ThreadLocal<VaultBlockEntityTickContinuation> ACTIVE = new ThreadLocal<>();
    private final ServerLevel level;
    private final TickingBlockEntity ticker;
    private final RegionDirectory directory;
    private final RoutingKey source;
    private final long generation;
    private final BooleanSupplier open;
    private final Runnable ownerFree;
    private TopologyGeneration topology;
    private VaultBlockEntity vault;
    private LevelChunk chunk;
    private ChunkHolder holder;
    private ChunkHolderLifetime lifetime;
    private java.util.concurrent.CompletableFuture<ChunkResult<net.minecraft.world.level.chunk.ChunkAccess>> sourceSlot;
    private BlockPos position;
    private BlockState oldState, newState;
    private VaultServerData serverData;
    private VaultSharedData sharedData;
    private OutputNeighborSignalContinuation neighbors;
    private BlockEntityExternalReadPreparation query;
    private long queryGeneration;
    private Phase phase = Phase.RUNNING_PREFIX;
    private Throwable failure;
    private boolean entered, interceptOutput, closed;

    public VaultBlockEntityTickContinuation(ServerLevel level, TickingBlockEntity ticker, RegionDirectory directory,
            RoutingKey source, long generation, BooleanSupplier open, Runnable ownerFree) {
        this.level = Objects.requireNonNull(level); this.ticker = Objects.requireNonNull(ticker);
        this.directory = directory; this.source = source; this.generation = generation;
        this.open = open; this.ownerFree = ownerFree;
        if (generation <= 0) throw new IllegalArgumentException("invalid Vault tick generation");
    }
    public Phase phase() { return phase; }
    public Throwable failure() { return failure; }
    public OutputNeighborSignalContinuation neighbors() { return neighbors; }
    public BlockEntityExternalReadPreparation query() { return query; }

    /** Called under the original BLOCK_ENTITY_TICK owner. Non-Vault tickers never
     * acquire this scope; the exact bound object/chunk are captured here, not by position. */
    public void original(Runnable original) {
        requireOwner();
        if (entered || closed || ACTIVE.get() != null) throw new IllegalStateException("overlapping Vault tick operation");
        entered = true;
        if (!(ticker instanceof LocalNativeTickerAccess access)
                || !(access.phoenium$localBlockEntity() instanceof VaultBlockEntity entity)) { original.run(); return; }
        vault = entity; chunk = access.phoenium$localChunk(); position = entity.getBlockPos().immutable();
        topology = directory.snapshot().generation(); holder = BlockEntityExternalReadPreparation.visible(level, chunk.getPos().pack());
        lifetime = BlockEntityExternalReadPreparation.lifetime(holder);
        sourceSlot = holder == null ? null : ((FullHolderEvidence) holder).phoenium$futures()
            .get(net.minecraft.world.level.chunk.status.ChunkStatus.FULL.getIndex());
        if (!currentOwned()) throw new IllegalStateException("Vault tick lacks exact current incarnation");
        ACTIVE.set(this);
        try { original.run(); if (phase == Phase.RUNNING_PREFIX) phase = Phase.SETTLED; }
        catch (RuntimeException | Error cause) { fail(cause); throw cause; }
        finally { ACTIVE.remove(); }
    }

    /** Mixin at Vault.Server.tick bci 111, before access$000, retains local 7.
     * Returning true cancels the native caller, including bci 114-140. */
    public static boolean setChanged(ServerLevel level, BlockPos pos, BlockState oldState,
            BlockState newState, VaultServerData serverData, VaultSharedData sharedData) {
        var tick = ACTIVE.get();
        if (tick == null) return false;
        if (tick.phase != Phase.RUNNING_PREFIX || tick.level != level || !tick.position.equals(pos)
                || tick.vault.getServerData() != serverData || tick.vault.getSharedData() != sharedData)
            throw new IllegalStateException("foreign or nested Vault setChanged seam");
        tick.oldState = oldState; tick.newState = newState; tick.serverData = serverData; tick.sharedData = sharedData;
        tick.phase = Phase.NEIGHBOR_LOOP; tick.interceptOutput = true;
        try { VaultSetChangedAccess.phoenium$setChanged(level, pos, oldState); }
        finally { tick.interceptOutput = false; }
        if (tick.phase != Phase.READ_SUSPENDED) tick.suffix();
        return true;
    }

    /** Scope is consumed before entering callbacks so recursive neighbor notifications
     * retain their original native behavior and cannot overwrite the outer PC. */
    public static boolean output(Level world, BlockPos pos, Block block) {
        var tick = ACTIVE.get();
        if (tick == null || !tick.interceptOutput) return false;
        if (world != tick.level || !pos.equals(tick.position) || block != tick.oldState.getBlock())
            throw new IllegalStateException("Vault output seam changed identity");
        tick.interceptOutput = false;
        tick.neighbors = new OutputNeighborSignalContinuation(tick.level, pos, block);
        tick.neighbors.run(tick);
        return true;
    }

    boolean readReady(BlockPos position) {
        requireOwner();
        if (BlockEntityExternalReadPreparation.ready(level, position)) return true;
        if (query != null) throw new IllegalStateException("Vault replaced an unconsumed query");
        query = new BlockEntityExternalReadPreparation(level, position, topology, generation,
            queryGeneration = Math.incrementExact(queryGeneration), this::current, ownerFree);
        phase = Phase.READ_SUSPENDED;
        return false;
    }
    public boolean current() {
        return !closed && !terminal() && open.getAsBoolean() && vault != null
            && topology.equals(directory.snapshot().generation())
            && directory.pendingChange(source).filter(change -> !change.completed()).isEmpty()
            && directory.resolve(source).isPresent()
            && BlockEntityExternalReadPreparation.visible(level, chunk.getPos().pack()) == holder
            && BlockEntityExternalReadPreparation.current(holder, lifetime)
            && sourceSlot != null && sourceSlot == ((FullHolderEvidence) holder).phoenium$futures()
                .get(net.minecraft.world.level.chunk.status.ChunkStatus.FULL.getIndex())
            && sourcePublishedChunk() == chunk
            && ticker instanceof LocalNativeTickerAccess access && access.phoenium$localBlockEntity() == vault
            && access.phoenium$localChunk() == chunk && vault.getLevel() == level && !vault.isRemoved();
    }
    private net.minecraft.world.level.chunk.ChunkAccess sourcePublishedChunk() {
        if (!sourceSlot.isDone() || sourceSlot.isCompletedExceptionally()) return null;
        var result = sourceSlot.getNow(null);
        var actual = result == null ? null : result.orElse(null);
        return actual instanceof net.minecraft.world.level.chunk.ImposterProtoChunk imposter ? imposter.getWrapped() : actual;
    }
    private boolean currentOwned() {
        requireOwner();
        if (!directory.resolve(source).orElseThrow().region().equals(OwnershipContext.current().orElseThrow().region()))
            throw new IllegalStateException("Vault continuation claimed a foreign owner");
        return current() && chunk.getBlockEntities().get(position) == vault;
    }
    public void prepare() {
        ownerFree.run();
        if (phase != Phase.READ_SUSPENDED && phase != Phase.PREPARING && phase != Phase.PREPARED) return;
        if (!open.getAsBoolean()) { retire(Phase.SHUTDOWN); return; }
        if (!current()) { retire(Phase.STALE); return; }
        phase = Phase.PREPARING;
        query.prepare();
        if (!query.current()) { retire(Phase.STALE); return; }
        if (query.prepared()) phase = Phase.PREPARED;
    }
    /** Invoked only by the reserved current BLOCK_ENTITY_TICK owner, never completion callbacks. */
    public void resume() {
        requireOwner();
        if (phase != Phase.RESUME_QUEUED && phase != Phase.PREPARED) return;
        if (!currentOwned() || !query.prepared()) { retire(Phase.STALE); return; }
        query.close(); query = null; phase = Phase.RESUMING_NEIGHBOR_LOOP;
        try { if (neighbors.run(this)) suffix(); }
        catch (RuntimeException | Error cause) { fail(cause); throw cause; }
    }
    private void suffix() {
        requireOwner(); phase = Phase.SET_CHANGED_COMPLETE;
        phase = Phase.RESUMING_VAULT_SUFFIX;
        // bci 114 reads the flag AFTER all neighbor callbacks, never a pre-cut boolean.
        if (((VaultSharedDataAccess) sharedData).phoenium$isDirty())
            level.sendBlockUpdated(position, oldState, newState, 2);
        ((VaultServerDataAccess) serverData).phoenium$isDirty(false);
        ((VaultSharedDataAccess) sharedData).phoenium$isDirty(false);
        phase = Phase.SETTLED;
    }
    public void finish(RegionalServiceDispatcher dispatcher, BiConsumer<RegionalServiceDispatcher.Execution, Runnable> owned) {
        if (query == null || terminal()) return;
        ownerFree.run();
        var loop = ((ChunkMapLoadAccess) level.getChunkSource().chunkMap).phoenium$mainExecutor();
        var fence = ((ManagedBlockPollTurnFence.Loop) loop).phoenium$naturalSpawnFence();
        try {
            while (query != null && !terminal()) {
                var pending = query;
                long exactQuery = queryGeneration;
                prepare();
                if (terminal()) return;
                try (var reservation = fence.reserve(generation, exactQuery, pending.dependency(),
                        () -> current() && query == pending && queryGeneration == exactQuery && pending.current(), ownerFree)
                        .prepareAtObservation(this::prepare).shutdownWhen(() -> !open.getAsBoolean())) {
                    reservation.awaitAndResume(loop::managedBlock, () -> {
                        if (phase != Phase.PREPARED) throw new IllegalStateException("Vault resume before FULL readiness");
                        phase = Phase.RESUME_QUEUED;
                        dispatcher.resumeVaultTick(reservation, source, execution -> owned.accept(execution, this::resume));
                    });
                    if (reservation.state() != ManagedBlockPollTurnFence.State.SETTLED) {
                        retire(reservation.state() == ManagedBlockPollTurnFence.State.SHUTDOWN ? Phase.SHUTDOWN : Phase.STALE);
                        return;
                    }
                }
            }
        } catch (RuntimeException | Error cause) { fail(cause); throw cause; }
    }
    private static void requireOwner() {
        if (OwnershipContext.current().isEmpty()) throw new IllegalStateException("Vault continuation without owner");
    }
    private boolean terminal() {
        return phase == Phase.SETTLED || phase == Phase.FAILED || phase == Phase.CANCELLED || phase == Phase.STALE || phase == Phase.SHUTDOWN;
    }
    private void fail(Throwable cause) {
        if (failure == null) failure = cause;
        phase = Phase.FAILED;
        try { if (query != null) query.close(); }
        catch (RuntimeException | Error cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
    }
    public void retire(Phase reason) {
        if (reason != Phase.CANCELLED && reason != Phase.STALE && reason != Phase.SHUTDOWN)
            throw new IllegalArgumentException("not a Vault retirement reason");
        if (terminal()) return;
        phase = reason;
        if (query != null) query.close();
    }
    @Override public void close() {
        retire(Phase.CANCELLED); closed = true;
        if (query != null) query.close(); query = null;
        vault = null; chunk = null; holder = null; lifetime = null; sourceSlot = null;
        serverData = null; sharedData = null; neighbors = null; oldState = null; newState = null;
    }
}
