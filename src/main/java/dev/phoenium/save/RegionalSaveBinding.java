package dev.phoenium.save;

import dev.phoenium.Phoenium;
import dev.phoenium.chunk.ChunkSaveRetention;
import dev.phoenium.chunk.ChunkBindingRuntime;
import dev.phoenium.core.WorldId;
import dev.phoenium.ownership.OwnershipContext;
import dev.phoenium.topology.RoutingKey;
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
        ChunkHolder phoenium$saveHolder(long position);
        ChunkHolder phoenium$pendingUnload(long position);
    }
    private static final ThreadLocal<Invocation> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<Unload> UNLOAD = new ThreadLocal<>();
    private RegionalSaveBinding() { }

    /** Current invocation diagnostic only; it retains no historical source or snapshot. */
    public static RegionalSaveCoordinator.Cut currentCut() {
        Invocation invocation = CURRENT.get();
        return invocation == null ? null : invocation.attempt.cut();
    }

    public static boolean save(RegionalSaveCoordinator coordinator, ChunkMap map, ServerLevel level,
                               ChunkAccess chunk, Supplier<Boolean> original) {
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
        ChunkBindingRuntime.WorldAuthority authority = Phoenium.liveRuntime().requireChunkWorld(level);
        Source source = coordinator.prepareSource(key, () -> {
            Unload unload = UNLOAD.get();
            if (unload != null && unload.map == map && ChunkSaveRetention.sameChunk(unload.chunk, chunk))
                return new Source(key, chunk, unload.retention.fork(), unload);
            ChunkHolder holder = ((MapAccess) map).phoenium$saveHolder(chunk.getPos().pack());
            if (holder == null) throw new IllegalStateException("original save has no selected source holder");
            return new Source(key, chunk, ChunkSaveRetention.retain(authority, holder, chunk), null);
        });
        RegionalSaveCoordinator.Attempt attempt = coordinator.begin(source);
        Invocation before = CURRENT.get();
        CURRENT.set(new Invocation(map, level, chunk, attempt));
        try { return original.get(); }
        catch (RuntimeException | Error failure) { attempt.failed(failure); throw failure; }
        finally {
            if (before == null) CURRENT.remove(); else CURRENT.set(before);
            attempt.finishBody();
        }
    }

    public static boolean markSaved(ChunkAccess chunk, Supplier<Boolean> original) {
        return invocation(chunk).attempt.markSaved(original);
    }
    public static SerializableChunkData capture(ServerLevel level, ChunkAccess chunk,
                                                Supplier<SerializableChunkData> original) {
        Invocation invocation = invocation(chunk);
        if (invocation.level != level) throw new IllegalStateException("save snapshot changed world instance");
        return invocation.attempt.capture(() -> RetainedChunkSnapshot.detach(original.get()));
    }
    public static <T> CompletableFuture<T> write(ChunkMap map, ChunkPos pos, Supplier<CompletableFuture<T>> original) {
        Invocation invocation = Objects.requireNonNull(CURRENT.get(), "write outside original save");
        if (invocation.map != map || !invocation.chunk.getPos().equals(pos))
            throw new IllegalStateException("save write changed its original source");
        try { return invocation.attempt.submitted(original.get()); }
        catch (RuntimeException | Error failure) { invocation.attempt.failed(failure); throw failure; }
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
        try (var retention = scope.retention) { original.run(); }
        finally {
            scope.open = false;
            if (before == null) UNLOAD.remove(); else UNLOAD.set(before);
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
        server.managedBlock(() -> { coordinator.pumpOne(); return coordinator.pending() == 0; });
        coordinator.requireFlushed();
    }

    public static RoutingKey key(ServerLevel level, ChunkPos pos) {
        return new RoutingKey(new WorldId(level.dimension().identifier().toString()), pos.x(), pos.z());
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
    private static final class Source implements RegionalSaveCoordinator.Source {
        final RoutingKey key;
        final ChunkAccess chunk;
        final ChunkSaveRetention retention;
        final Unload originalUnload;
        Source(RoutingKey key, ChunkAccess chunk, ChunkSaveRetention retention, Unload originalUnload) {
            this.key = key; this.chunk = chunk; this.retention = retention; this.originalUnload = originalUnload;
        }
        public RoutingKey key() { return key; }
        public boolean current() {
            return retention.matches(chunk) && (retention.current() || originalUnload != null && originalUnload.open);
        }
        public void restoreDirty() { chunk.markUnsaved(); }
        public void release() { retention.close(); }
    }
}
