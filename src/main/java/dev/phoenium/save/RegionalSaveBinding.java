package dev.phoenium.save;

import dev.phoenium.Phoenium;
import dev.phoenium.chunk.ChunkSaveRetention;
import dev.phoenium.chunk.FullFinishingServices;
import dev.phoenium.core.WorldId;
import dev.phoenium.ownership.OwnershipContext;
import dev.phoenium.topology.RoutingKey;
import dev.phoenium.topology.RegionDirectory;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;

/** Actual Minecraft save producer scopes. Pending unloads retain the original holder
 * before conditional removal; a later source cannot borrow that older incarnation. */
public final class RegionalSaveBinding {
    public interface MapAccess {
        ChunkHolder phoenium$pendingUnload(long position);
        ChunkHolder phoenium$visibleSaveHolder(long position);
        void phoenium$retainVisibleSave(ChunkHolder holder, ChunkSaveRetention retention);
        ChunkSaveRetention phoenium$borrowVisibleSave(ChunkHolder holder, ChunkAccess selected);
    }
    private static final ThreadLocal<Invocation> CURRENT = new dev.phoenium.core.FastThreadLocal<>();
    private static final ThreadLocal<Unload> UNLOAD = new dev.phoenium.core.FastThreadLocal<>();
    private static final ThreadLocal<Selection> SELECTION = new dev.phoenium.core.FastThreadLocal<>();
    private static final ThreadLocal<Batch> BATCH = new dev.phoenium.core.FastThreadLocal<>();
    private RegionalSaveBinding() { }

    /** Current invocation diagnostic only; it retains no historical source or snapshot. */
    public static RegionalSaveCoordinator.Cut currentCut() {
        Invocation invocation = CURRENT.get();
        return invocation == null ? null : invocation.attempt.cut();
    }

    public static boolean save(RegionalSaveCoordinator coordinator, ChunkMap map, ServerLevel level,
                               ChunkAccess chunk, Supplier<Boolean> original) {
        FullFinishingServices.requireWorld(level, FullFinishingServices.Kind.UNSAVED);
        RoutingKey key = key(level, chunk.getPos());
        // Bound outstanding snapshot records before allocating another. Readiness and original
        // I/O progress are pumped only outside ownership; no polling while holding a region.
        if (coordinator.pending() >= 128) {
            if (OwnershipContext.current().isPresent())
                throw new IllegalStateException("save capacity must be awaited before source ownership");
            level.getServer().managedBlock(() -> {
                coordinator.pumpOne();
                return coordinator.pending() < 128;
            });
        }
        Batch batch = BATCH.get();
        // Autosave and final unload also need a cut across dirty claim and capture. An
        // enclosing save-all reservation (or an already active exact owner) supplies it.
        boolean enclosed = batch != null && batch.map == map && batch.cut != null;
        try (var operationCut = enclosed || OwnershipContext.current().isPresent()
                ? null : retainTopologyCut(coordinator, level)) {
            return saveWithinCut(coordinator, map, level, chunk, key, original);
        }
    }

    private static boolean saveWithinCut(RegionalSaveCoordinator coordinator, ChunkMap map, ServerLevel level,
                                         ChunkAccess chunk, RoutingKey key, Supplier<Boolean> original) {
        Source source = coordinator.prepareSource(key, () -> {
            Unload unload = UNLOAD.get();
            if (unload != null && unload.map == map && ChunkSaveRetention.sameChunk(unload.chunk, chunk))
                return new Source(key, level, chunk, unload.retention.fork(), unload, null);
            Selection selected = Objects.requireNonNull(SELECTION.get(), "original save has no selected holder scope");
            selected.require(map, chunk);
            return new Source(key, level, chunk, selected.retention.fork(), null, selected);
        });
        RegionalSaveCoordinator.Attempt attempt = coordinator.begin(source);
        Invocation before = CURRENT.get();
        CURRENT.set(new Invocation(map, level, chunk, attempt));
        try { return original.get(); }
        catch (RuntimeException | Error failure) { attempt.failed(failure); throw failure; }
        finally {
            if (before == null) CURRENT.set(null); else CURRENT.set(before);
            attempt.finishBody();
        }
    }

    public static boolean markSaved(ChunkAccess chunk, Supplier<Boolean> original) {
        return invocation(chunk).attempt.markSaved(original);
    }
    public static <T> T readSource(ChunkAccess chunk, Supplier<T> original) {
        return invocation(chunk).attempt.readSource(original);
    }
    public static <T> T readSource(Supplier<T> original) {
        return Objects.requireNonNull(CURRENT.get(), "source read outside original save").attempt.readSource(original);
    }

    /** Preserve the holder actually selected by vanilla's visible-map traversal. An updating-map
     * lookup at a later save site is not evidence for that original source incarnation. */
    public static <T> T selectionScope(ChunkMap map, Supplier<T> original) {
        Selection before = SELECTION.get();
        Selection selected = new Selection(map);
        SELECTION.set(selected);
        try (selected) { return original.get(); }
        finally { if (before == null) SELECTION.set(null); else SELECTION.set(before); }
    }
    public static ChunkAccess select(RegionalSaveCoordinator coordinator, ChunkMap map, ServerLevel level,
                                     ChunkHolder holder, Supplier<ChunkAccess> original) {
        Selection selection = Objects.requireNonNull(SELECTION.get(), "holder read outside original save traversal");
        if (selection.map != map) throw new IllegalStateException("save traversal changed chunk map");
        selection.clear();
        return coordinator.prepareSource(key(level, holder.getPos()), () -> {
            FullFinishingServices.requireWorld(level, FullFinishingServices.Kind.UNSAVED);
            ChunkAccess selected = original.get();
            if (selected != null) {
                selection.chunk = selected;
                selection.retention = ((MapAccess) map).phoenium$borrowVisibleSave(holder, selected);
                if (selection.retention == null)
                    selection.retention = ChunkSaveRetention.retain(Phoenium.liveRuntime().requireChunkWorld(level), holder, selected);
            }
            return selected;
        });
    }
    public static boolean isUnsaved(RegionalSaveCoordinator coordinator, ChunkMap map, ServerLevel level,
                                    ChunkAccess chunk, Supplier<Boolean> original) {
        Selection selected = Objects.requireNonNull(SELECTION.get(), "dirty read outside original save traversal");
        return coordinator.prepareSource(key(level, chunk.getPos()), () -> {
            selected.require(map, chunk);
            return original.get();
        });
    }

    /** Save-all holds an immutable topology reservation, never a gameplay lease. Original
     * readiness settles first; capture, encoding, storage and acknowledgement run outside
     * the directory monitor. Pending I/O records remain free to migrate after this call. */
    public static void batch(RegionalSaveCoordinator coordinator, ChunkMap map, ServerLevel level,
                             boolean flush, Runnable original) {
        if (OwnershipContext.current().isPresent()) throw new IllegalStateException("save-all entered inside regional ownership");
        Batch before = BATCH.get();
        Batch batch = new Batch(coordinator, map, level);
        BATCH.set(batch);
        try (batch) {
            if (!flush) batch.retainCut();
            selectionScope(map, () -> { original.run(); return null; });
        } finally { if (before == null) BATCH.set(null); else BATCH.set(before); }
    }
    public static List<ChunkHolder> readyBatch(ChunkMap map, List<ChunkHolder> original) {
        Batch batch = Objects.requireNonNull(BATCH.get(), "selected save-all holders outside original batch");
        if (batch.map != map) throw new IllegalStateException("save-all holder list changed chunk map");
        for (ChunkHolder holder : original) batch.level.getServer().managedBlock(holder::isReadyForSaving);
        batch.retainCut();
        return original;
    }
    public static SerializableChunkData capture(ServerLevel level, ChunkAccess chunk,
                                                Supplier<SerializableChunkData> original) {
        Invocation invocation = invocation(chunk);
        if (invocation.level != level) throw new IllegalStateException("save snapshot changed world instance");
        FullFinishingServices.requireWorld(level, FullFinishingServices.Kind.TICK_LIST);
        return invocation.attempt.capture(() -> RetainedChunkSnapshot.detach(original.get()));
    }
    public static <T> CompletableFuture<T> write(ChunkMap map, ChunkPos pos, Supplier<CompletableFuture<T>> original) {
        Invocation invocation = Objects.requireNonNull(CURRENT.get(), "write outside original save");
        if (invocation.map != map || !invocation.chunk.getPos().equals(pos))
            throw new IllegalStateException("save write changed its original source");
        try { return invocation.attempt.submitted(FullFinishingServices.ownerFreeIo(original)); }
        catch (RuntimeException | Error failure) { invocation.attempt.failed(failure); throw failure; }
    }

    /** The original synchronous catch reports and returns false, so its failure would
     * otherwise be invisible to the enclosing save invocation. Record it before the
     * original reporting callback, without changing that callback or its exception. */
    public static void reportedFailure(ChunkMap map, ChunkPos pos, Throwable failure) {
        Invocation invocation = Objects.requireNonNull(CURRENT.get(), "reported failure outside original save");
        if (invocation.map != map || !invocation.chunk.getPos().equals(pos))
            throw new IllegalStateException("save failure changed its original source");
        invocation.attempt.failed(failure);
    }

    public static void unload(RegionalSaveCoordinator coordinator, ChunkMap map, ServerLevel level,
                              ChunkHolder holder, CompletableFuture<?> expectedSave, long position, Runnable original) {
        // These are original control-plane identities. A stale subscriber must retain the
        // original reschedule/no-op path without acquiring new rights to retired source data.
        if (holder.getSaveSyncFuture() != expectedSave
                || ((MapAccess) map).phoenium$pendingUnload(position) != holder) {
            original.run();
            return;
        }
        RoutingKey key = key(level, holder.getPos());
        var authority = Phoenium.liveRuntime().requireChunkWorld(level);
        Unload scope = coordinator.prepareSource(key, () -> {
            ChunkAccess chunk = holder.getLatestChunk();
            return chunk == null ? null : new Unload(map, chunk, ChunkSaveRetention.retain(authority, holder, chunk));
        });
        if (scope == null) { original.run(); return; }
        Unload before = UNLOAD.get(); UNLOAD.set(scope);
        try { original.run(); }
        finally {
            try {
                // Vanilla can revisit this exact retired holder through the old visible map
                // later in processUnloads. Transfer an existing use, never mint retired rights.
                if (!scope.retention.current() && ((MapAccess) map).phoenium$visibleSaveHolder(position) == holder)
                    ((MapAccess) map).phoenium$retainVisibleSave(holder, scope.retention.fork());
            } finally {
                scope.retention.close();
                scope.open = false;
                if (before == null) UNLOAD.set(null); else UNLOAD.set(before);
            }
        }
    }

    public static void mutateUnload(RegionalSaveCoordinator coordinator, ServerLevel level,
                                    ChunkAccess chunk, Runnable original) {
        Unload scope = Objects.requireNonNull(UNLOAD.get(), "mutation outside retained original unload");
        coordinator.unloadSource(key(level, chunk.getPos()), () -> {
            if (!scope.open || !scope.retention.matches(chunk))
                throw new IllegalStateException("unload lost its retained original source");
            original.run();
        });
    }

    public static void flush(MinecraftServer server, RegionalSaveCoordinator coordinator) {
        if (OwnershipContext.current().isPresent()) throw new IllegalStateException("save flush attempted under regional ownership");
        try (var turn = FullFinishingServices.forServer(server).serial(FullFinishingServices.Phase.SAVE)) {
            server.managedBlock(() -> { coordinator.pumpOne(); return coordinator.pending() == 0; });
            coordinator.requireFlushed();
        }
    }

    public static RoutingKey key(ServerLevel level, ChunkPos pos) {
        return new RoutingKey(new WorldId(level.dimension().identifier().toString()), pos.x(), pos.z());
    }
    private static RegionDirectory.SaveCut retainTopologyCut(RegionalSaveCoordinator coordinator, ServerLevel level) {
        RegionDirectory.SaveCut[] cut = {null};
        level.getServer().managedBlock(() -> {
            Phoenium.liveRuntime().pumpFullOne();
            cut[0] = coordinator.tryRetainTopologyCut();
            return cut[0] != null;
        });
        return cut[0];
    }
    private static Invocation invocation(ChunkAccess chunk) {
        Invocation invocation = Objects.requireNonNull(CURRENT.get(), "snapshot outside original save");
        if (invocation.chunk != chunk) throw new IllegalStateException("save source identity changed");
        return invocation;
    }
    private record Invocation(ChunkMap map, ServerLevel level, ChunkAccess chunk, RegionalSaveCoordinator.Attempt attempt) { }
    private static final class Unload {
        final ChunkMap map;
        final ChunkAccess chunk;
        final ChunkSaveRetention retention;
        boolean open = true;
        Unload(ChunkMap map, ChunkAccess chunk, ChunkSaveRetention retention) {
            this.map = map; this.chunk = chunk; this.retention = retention;
        }
    }
    private static final class Selection implements AutoCloseable {
        final ChunkMap map;
        ChunkAccess chunk;
        ChunkSaveRetention retention;
        boolean open = true;
        Selection(ChunkMap map) { this.map = map; }
        void require(ChunkMap expectedMap, ChunkAccess expectedChunk) {
            if (!open || map != expectedMap || chunk != expectedChunk || retention == null || !retention.matches(expectedChunk))
                throw new IllegalStateException("save lost its original selected source incarnation");
        }
        void clear() {
            ChunkSaveRetention previous = retention;
            retention = null; chunk = null;
            if (previous != null) previous.close();
        }
        @Override public void close() { open = false; clear(); }
    }
    private static final class Batch implements AutoCloseable {
        final RegionalSaveCoordinator coordinator;
        final ChunkMap map;
        final ServerLevel level;
        RegionDirectory.SaveCut cut;
        Batch(RegionalSaveCoordinator coordinator, ChunkMap map, ServerLevel level) {
            this.coordinator = coordinator; this.map = map; this.level = level;
        }
        void retainCut() {
            if (cut != null) throw new IllegalStateException("save-all topology cut retained twice");
            cut = retainTopologyCut(coordinator, level);
        }
        @Override public void close() { if (cut != null) cut.close(); }
    }
    private static final class Source implements RegionalSaveCoordinator.Source {
        final RoutingKey key;
        final ServerLevel level;
        final ChunkAccess chunk;
        final ChunkSaveRetention retention;
        final Unload originalUnload;
        final Selection originalSelection;
        Source(RoutingKey key, ServerLevel level, ChunkAccess chunk, ChunkSaveRetention retention, Unload originalUnload, Selection originalSelection) {
            this.key = key; this.level = level; this.chunk = chunk; this.retention = retention; this.originalUnload = originalUnload;
            this.originalSelection = originalSelection;
        }
        public RoutingKey key() { return key; }
        public boolean current() {
            return retention.matches(chunk) && (retention.current() || originalUnload != null && originalUnload.open
                    || originalSelection != null && originalSelection.open && originalSelection.chunk == chunk);
        }
        public void restoreDirty() {
            FullFinishingServices.requireWorld(level, FullFinishingServices.Kind.UNSAVED);
            chunk.markUnsaved();
        }
        public void release() { retention.close(); }
    }
}
