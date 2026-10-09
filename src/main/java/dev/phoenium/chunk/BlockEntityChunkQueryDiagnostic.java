package dev.phoenium.chunk;

import dev.phoenium.core.TopologyGeneration;
import dev.phoenium.ownership.OwnershipContext;
import dev.phoenium.service.RegionalServiceDispatcher;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.slf4j.LoggerFactory;

/** Observation only: never schedules a chunk, waits, or supplies a read result. */
public final class BlockEntityChunkQueryDiagnostic {
    private static final ThreadLocal<Scope> CURRENT = new dev.phoenium.core.FastThreadLocal<>();
    /** S9-D4: open scopes on all threads (see FullFinishingQueries.ACTIVE for the exactness argument). */
    private static final java.util.concurrent.atomic.AtomicInteger ACTIVE = new java.util.concurrent.atomic.AtomicInteger();
    public static boolean idle() { return ACTIVE.get() == 0; }
    private static Scope current() { return ACTIVE.get() == 0 ? null : CURRENT.get(); }
    private final AtomicBoolean reported = new AtomicBoolean();
    private final Consumer<Failure> sink;

    public BlockEntityChunkQueryDiagnostic() {
        this(failure -> LoggerFactory.getLogger("Phoenium").error(
                "PHOENIUM_BLOCK_ENTITY_CHUNK_QUERY_FIRST_FAILURE {}", failure));
    }
    BlockEntityChunkQueryDiagnostic(Consumer<Failure> sink) { this.sink = sink; }

    public Scope enter(ServerLevel world, String type, BlockPos position,
                       RegionalServiceDispatcher.Execution execution, TopologyGeneration topology) {
        if (CURRENT.get() != null) throw new IllegalStateException("nested block entity diagnostic scope");
        Scope scope = new Scope(world, type, position.immutable(), execution, topology);
        CURRENT.set(scope); ACTIVE.incrementAndGet();
        return scope;
    }

    public final class Scope implements AutoCloseable {
        final ServerLevel world;
        final String type;
        final BlockPos position;
        final RegionalServiceDispatcher.Execution execution;
        final TopologyGeneration topology;
        final HolderObservation source;
        BlockPos read;
        Query query;
        private boolean closed;
        Scope(ServerLevel world, String type, BlockPos position,
              RegionalServiceDispatcher.Execution execution, TopologyGeneration topology) {
            this.world = world; this.type = type; this.position = position;
            this.execution = execution; this.topology = topology;
            this.source = observe(world, ChunkPos.containing(position));
        }
        void report(Query query, Throwable failure) {
            if (read == null || !reported.compareAndSet(false, true)) return;
            try {
                HolderObservation atFailure = observe(world, query.position);
                sink.accept(new Failure(type, position, ChunkPos.containing(position), read, query.position,
                        query.status.toString(), query.create, execution.kind().name(), execution.worldInstance(),
                        execution.operationId(), topology, OwnershipContext.current().orElse(null), source,
                        query.holder, atFailure, identity(query.selected), readiness(query.selected),
                        query.holder != null && identity(query.selected).equals(query.holder.fullSlot()),
                        ChunkPos.containing(position).equals(query.position), false,
                        failure.getClass().getName(), failure.getMessage()));
            } catch (RuntimeException | Error diagnosticFailure) {
                // Observation must not mutate the original exception, even when the sink fails.
            }
        }
        @Override public void close() {
            if (closed) return;
            closed = true; CURRENT.set(null); ACTIVE.decrementAndGet();
        }
    }

    public static BlockState blockState(net.minecraft.world.level.Level world, BlockPos position,
                                        Supplier<BlockState> original) {
        Scope scope = current();
        if (scope == null || scope.world != world) return original.get();
        BlockPos previous = scope.read;
        scope.read = position.immutable();
        try { return original.get(); } finally { scope.read = previous; }
    }

    public static ChunkAccess chunk(ServerLevel world, int x, int z, ChunkStatus status, boolean create,
                                   Supplier<ChunkAccess> original) {
        dev.phoenium.live.PortalDestinationObservation.query(world,x,z,status,create);
        Scope scope = current();
        if (scope == null || scope.world != world) {
            var result=original.get();
            dev.phoenium.live.PortalDestinationObservation.result(world,x,z,status,result);
            return result;
        }
        Query previous = scope.query;
        Query query = new Query(new ChunkPos(x, z), status, create);
        scope.query = query;
        try { return original.get(); }
        catch (RuntimeException | Error failure) {
            scope.report(query, failure);
            throw failure;
        } finally { scope.query = previous; }
    }

    public static void selected(ServerLevel world, int x, int z, ChunkStatus status, boolean create,
                                CompletableFuture<ChunkResult<ChunkAccess>> future) {
        dev.phoenium.live.PortalDestinationObservation.selected(world,x,z,status,future);
        Scope scope = current();
        if (scope == null || scope.world != world || scope.query == null) return;
        Query query = scope.query;
        if (!query.position.equals(new ChunkPos(x, z)) || query.status != status || query.create != create) return;
        query.selected = future;
        query.holder = observe(world, query.position);
    }

    private static final class Query {
        final ChunkPos position;
        final ChunkStatus status;
        final boolean create;
        CompletableFuture<ChunkResult<ChunkAccess>> selected;
        HolderObservation holder;
        Query(ChunkPos position, ChunkStatus status, boolean create) {
            this.position = position; this.status = status; this.create = create;
        }
    }

    public record HolderObservation(String identity, ChunkCustodyModel.LifecycleId incarnation,
                                    boolean incarnationCurrent, int ticketLevel, String fullSlot,
                                    String readiness) { }
    public record Failure(String type, BlockPos sourcePosition, ChunkPos sourceChunk, BlockPos queriedPosition,
                          ChunkPos queriedChunk, String requestedStatus, boolean create,
                          String service, long worldInstance, long serviceTurn, TopologyGeneration topology,
                          OwnershipContext.Active owner, HolderObservation sourceHolder,
                          HolderObservation queriedHolder, HolderObservation holderAtFailure,
                          String selectedFuture, String selectedReadiness, boolean selectedIsHolderFullSlot,
                          boolean sameChunk, boolean typedCurrentInputReceipt,
                          String failureClass, String failureMessage) { }

    public static String portalHolderObservation(ServerLevel level,int x,int z,ChunkStatus status,CompletableFuture<?> selected) {
        try {
            var holder=((dev.phoenium.chunk.FullInputView)level.getChunkSource().chunkMap).phoenium$visibleInput(new net.minecraft.world.level.ChunkPos(x,z).pack());
            if(holder==null)return " holder=ABSENT lifetime=UNAVAILABLE";
            var evidence=(dev.phoenium.chunk.FullHolderEvidence)holder;var lifetime=evidence.phoenium$lifetime();
            var slot=evidence.phoenium$futures().get(status.getIndex());
            var provider=lifetime==null ? null : lifetime.services().residentPublication(lifetime);
            return " holder="+System.identityHashCode(holder)+" requestedStatusSlot="+(slot==null?"ABSENT":System.identityHashCode(slot))
                +" selectedIsRequestedSlot="+(selected==null?"UNAVAILABLE":selected==slot)+" lifetime="+(lifetime==null?"UNAVAILABLE":lifetime.lifecycle())
                +" providerPublication="+(provider==null?"UNAVAILABLE":System.identityHashCode(provider));
        } catch(Throwable unavailable) {return " holder=UNAVAILABLE lifetime=UNAVAILABLE providerPublication=UNAVAILABLE";}
    }
    private static HolderObservation observe(ServerLevel world, ChunkPos position) {
        ChunkHolder holder = ((FullInputView) world.getChunkSource().chunkMap).phoenium$visibleInput(position.pack());
        if (holder == null) return new HolderObservation("ABSENT", null, false, -1, "ABSENT", "ABSENT");
        FullHolderEvidence evidence = (FullHolderEvidence) holder;
        var lifetime = evidence.phoenium$lifetime();
        var slot = evidence.phoenium$futures().get(ChunkStatus.FULL.getIndex());
        return new HolderObservation(identity(holder), lifetime == null ? null : lifetime.lifecycle(),
                lifetime != null && lifetime.holder() == holder
                        && lifetime.services().lifecycles().isCurrent(lifetime.lifecycle()),
                holder.getTicketLevel(), identity(slot), readiness(slot));
    }
    private static String identity(Object object) {
        return object == null ? "ABSENT" : object.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(object));
    }
    private static String readiness(CompletableFuture<ChunkResult<ChunkAccess>> future) {
        if (future == null) return "ABSENT";
        if (!future.isDone()) return "PENDING";
        if (future.isCancelled()) return "CANCELLED";
        if (future.isCompletedExceptionally()) return "EXCEPTIONAL";
        var result = future.getNow(null);
        return result == null ? "NULL_RESULT" : result.orElse(null) == null ? "UNLOADED" : "READY";
    }
}
