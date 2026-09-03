package dev.phoenium.live;

import dev.phoenium.core.OwnerEpoch;
import dev.phoenium.core.TopologyGeneration;
import dev.phoenium.core.WorldId;
import dev.phoenium.failure.FailureBarrier;
import dev.phoenium.chunk.ChunkCompletionGateway;
import dev.phoenium.chunk.ChunkWorkRequest;
import dev.phoenium.ownership.ObjectOwnershipRegistry;
import dev.phoenium.ownership.ObjectOwnership;
import dev.phoenium.ownership.ObjectOperationExecutor;
import dev.phoenium.ownership.ObjectOwnerGateway;
import dev.phoenium.ownership.OwnedObjectEnvelope;
import dev.phoenium.ownership.OwnedObjectKind;
import dev.phoenium.mixin.ServerGamePacketListenerInvoker;
import dev.phoenium.region.RegionId;
import dev.phoenium.region.RegionLifecycle;
import dev.phoenium.region.RegionRuntime;
import dev.phoenium.scheduler.OwnerGateway;
import dev.phoenium.scheduler.RegionScheduler;
import dev.phoenium.scheduler.SchedulerLifecycle;
import dev.phoenium.task.AdmissionPolicy;
import dev.phoenium.task.RegionTaskEnvelope;
import dev.phoenium.task.TaskCorrelationId;
import dev.phoenium.task.TaskPayload;
import dev.phoenium.task.TaskSequence;
import dev.phoenium.topology.RegionDirectory;
import dev.phoenium.topology.RegionRoute;
import dev.phoenium.topology.RoutingKey;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.entity.Entity;
import org.slf4j.Logger;

/**
 * Stage 2's serial live binding. A Minecraft level is represented by one real
 * Phoenium region; this is deliberately not geographical regionalization.
 */
public final class PhoeniumLiveRuntime implements RegionScheduler.LiveTickExecutor, ObjectOperationExecutor {
    private final Logger logger;
    private final Map<WorldId, LiveRegion> regions = new LinkedHashMap<>();
    private final Map<RegionId, RegionRuntime> runtimes = new LinkedHashMap<>();
    private final RegionDirectory directory = new RegionDirectory();
    private final ObjectOwnershipRegistry objects = new ObjectOwnershipRegistry(directory);
    private final Map<TaskCorrelationId, PendingPacket> packets = new ConcurrentHashMap<>();
    private final Map<TaskCorrelationId, ChunkWorkRequest> chunkRequests = new ConcurrentHashMap<>();
    private final java.util.Set<WorldId> observedChunkFutureWorlds = ConcurrentHashMap.newKeySet();
    private final ThreadLocal<TaskCorrelationId> packetExecution = new ThreadLocal<>();
    private final FailureBarrier barrier = new FailureBarrier();
    private RegionScheduler scheduler;
    private OwnerGateway gateway;
    private ObjectOwnerGateway objectGateway;
    private ChunkCompletionGateway chunkGateway;
    private PendingTick pendingTick;
    private State state = State.NEW;
    private long lifecycleStarts;
    private long lifecycleStops;
    private long intercepts;
    private long dispatches;
    private long executions;
    private long duplicates;
    private long unexpectedBypasses;
    private long fallbacks;
    private long ownershipViolations;
    private long unleasedExecutions;
    private long hardBarriers;
    private long packetAdmissions;
    private long packetExecutions;
    private long packetStaleReroutes;
    private long packetRejections;
    private long packetRecursions;
    private long chunkCompletionAdmissions;
    private long chunkCompletionApplications;
    private long chunkCompletionReroutes;
    private long chunkCompletionRejections;
    private long producerThreadChunkMutations;
    private long sequence;
    private final long injectedFailureAt = Long.getLong("phoenium.test.failLiveTickAt", -1L);

    public PhoeniumLiveRuntime(Logger logger) { this.logger = Objects.requireNonNull(logger, "logger"); }

    public void start(MinecraftServer server) {
        if (state != State.NEW) throw new IllegalStateException("Phoenium lifecycle start repeated: " + state);
        Map<RoutingKey, RegionRoute> routes = new LinkedHashMap<>();
        Map<WorldId, RegionRoute> worldDefaults = new LinkedHashMap<>();
        for (ServerLevel level : server.getAllLevels()) {
            WorldId world = worldId(level);
            RegionId region = new RegionId(world, "stage2-live-world");
            RoutingKey key = new RoutingKey(world, 0, 0);
            RegionRuntime runtime = new RegionRuntime(region, new OwnerEpoch(0));
            runtime.transitionTo(RegionLifecycle.READY);
            LiveRegion previous = regions.put(world, new LiveRegion(level, region, key, runtime));
            if (previous != null) throw new IllegalStateException("duplicate controlled world " + world.value());
            runtimes.put(region, runtime);
            RegionRoute route = new RegionRoute(region, new TopologyGeneration(1), runtime.ownerEpoch());
            routes.put(key, route);
            worldDefaults.put(world, route);
        }
        if (regions.isEmpty()) throw new IllegalStateException("no server levels available for Stage 2 live binding");
        directory.publish(new TopologyGeneration(1), routes, worldDefaults);
        scheduler = new RegionScheduler(runtimes, directory, barrier, this, this);
        gateway = new OwnerGateway(directory, runtimes, scheduler);
        objectGateway = new ObjectOwnerGateway(objects, directory, gateway);
        chunkGateway = new ChunkCompletionGateway(directory, gateway);
        state = State.RUNNING;
        lifecycleStarts++;
        logStatus("started");
    }

    /** Called only from the redirect around MinecraftServer.tickChildren' single ServerLevel.tick invoke. */
    public void dispatchLevelTick(ServerLevel level, BooleanSupplier shouldKeepTicking) {
        intercepts++;
        if (!isRunning()) {
            // The redirect deliberately consumes the call after a hard barrier. It never invokes vanilla as a fallback.
            if (scheduler != null && scheduler.lifecycle() == SchedulerLifecycle.FAILED) hardBarriers = 1;
            return;
        }
        WorldId world = worldId(level);
        LiveRegion live = regions.get(world);
        if (live == null || live.level() != level) {
            unexpectedBypasses++;
            failClosed(new IllegalStateException("unregistered live level " + world.value()), null);
            return;
        }
        RegionRoute route = directory.resolve(live.key()).orElseThrow(() -> new IllegalStateException("missing route " + world.value()));
        RegionTaskEnvelope envelope = new RegionTaskEnvelope("live-level-tick", new TaskPayload.LiveLevelTick(world.value()), live.key(), route.generation(), route.ownerEpoch(), "minecraft-server", new TaskCorrelationId(++sequence), new TaskSequence(sequence), AdmissionPolicy.REJECT);
        if (gateway.submit(envelope) != OwnerGateway.Delivery.QUEUED) {
            unexpectedBypasses++;
            failClosed(new IllegalStateException("live tick admission rejected for " + world.value()), envelope);
            return;
        }
        dispatches++;
        pendingTick = new PendingTick(level, shouldKeepTicking, envelope);
        try {
            while (executions < dispatches && scheduler.lifecycle().acceptsSimulationWork()) {
                long before = scheduler.advances();
                scheduler.advanceOne();
                if (scheduler.advances() == before) {
                    if (scheduler.lifecycle() == SchedulerLifecycle.FAILED) break;
                    throw new IllegalStateException("admitted live tick was not reachable through the owner scheduler");
                }
            }
        } finally {
            pendingTick = null;
        }
        if (scheduler.lifecycle() == SchedulerLifecycle.FAILED) hardBarriers = 1;
    }

    @Override
    public void execute(RegionRuntime runtime, TaskPayload.LiveLevelTick tick) {
        PendingTick pending = pendingTick;
        if (pending == null || !tick.worldKey().equals(worldId(pending.level()).value())) {
            unleasedExecutions++;
            throw new IllegalStateException("live tick execution was not bound to its admitted envelope");
        }
        if (injectedFailureAt > 0 && dispatches == injectedFailureAt) {
            throw new IllegalStateException("Stage 2 requested live tick failure at dispatch " + dispatches);
        }
        pending.level().tick(pending.shouldKeepTicking());
        refreshEntityBindings(pending.level());
        executions++;
        runtime.tick();
    }

    public void stop() {
        if (state == State.STOPPED) return;
        if (scheduler != null) { scheduler.quiesce(); scheduler.stop(); }
        state = State.STOPPED;
        lifecycleStops++;
        logStatus("stopped");
    }

    public LiveDiagnostics diagnostics() {
        int activeLeases = runtimes.values().stream().mapToInt(runtime -> runtime.leases().hasActiveLease() ? 1 : 0).sum();
        return new LiveDiagnostics(state, scheduler == null ? null : scheduler.lifecycle(), lifecycleStarts, lifecycleStops, 1, false,
            regions.size(), activeLeases, intercepts, dispatches, executions, duplicates, unexpectedBypasses, fallbacks,
            ownershipViolations, unleasedExecutions, hardBarriers, barrier.tripped(), objects.liveCount(), objects.playerCount(),
            packetAdmissions, packetExecutions, packetStaleReroutes, packetRejections, packetRecursions,
            chunkCompletionAdmissions, chunkCompletionApplications, chunkCompletionReroutes, chunkCompletionRejections, producerThreadChunkMutations);
    }

    /** Mixin-visible marker check; this is the only re-entry exemption and it is scoped to one correlation id. */
    public boolean packetExecutionMarked() { return packetExecution.get() != null; }

    /** Called after PacketUtils has established server-thread execution, before movement handler mutation. */
    public void admitMovePacket(ServerGamePacketListenerImpl connection, ServerboundMovePlayerPacket packet) {
        ServerPlayer player = connection.player;
        if (player == null) { packetRejections++; return; }
        ObjectOwnership current = bindPlayer(player);
        RoutingKey destination = new RoutingKey(worldId(player.level()), Math.floorDiv((int) Math.floor(packet.getX(player.getX())), 16), Math.floorDiv((int) Math.floor(packet.getZ(player.getZ())), 16));
        // Movement establishes its transfer boundary before vanilla's position mutation, never as a post-mutation discovery.
        if (!current.routingKey().equals(destination)) current = objects.move(current.objectId(), destination);
        admitPacket(connection, player, new PendingMove(connection, packet), "player-movement", current);
    }

    /** Representative real interaction path: ServerboundInteractPacket / ServerPlayer.interactOn. */
    public void admitInteractPacket(ServerGamePacketListenerImpl connection, ServerboundInteractPacket packet) {
        admitPacket(connection, connection.player, new PendingInteract(connection, packet), "entity-interaction", null);
    }

    /**
     * The base 26.2 ServerChunkCache future is observed, not replaced. Its completion thread holds
     * only immutable request metadata and queues an owner envelope; it never receives a LevelChunk.
     */
    public void observeChunkFuture(ServerLevel level, int x, int z, ChunkStatus status,
                                   CompletableFuture<ChunkResult<ChunkAccess>> future) {
        if (!isRunning()) return;
        WorldId world = worldId(level);
        if (!observedChunkFutureWorlds.add(world)) return;
        TaskCorrelationId correlation = new TaskCorrelationId(++sequence);
        ChunkWorkRequest request = new ChunkWorkRequest(world, new RoutingKey(world, x, z), sequence,
            directory.snapshot().generation(), "base-server-chunk-future-" + status.getName(), correlation);
        chunkRequests.put(correlation, request);
        future.whenComplete((ignoredResult, ignoredFailure) -> admitChunkCompletion(request));
    }

    private void admitChunkCompletion(ChunkWorkRequest request) {
        if (!isRunning()) { chunkRequests.remove(request.correlationId()); return; }
        ChunkCompletionGateway.Delivery delivery = chunkGateway.complete(request);
        if (delivery == ChunkCompletionGateway.Delivery.QUEUED || delivery == ChunkCompletionGateway.Delivery.REROUTED_CURRENT_OWNER) {
            chunkCompletionAdmissions++;
            if (delivery == ChunkCompletionGateway.Delivery.REROUTED_CURRENT_OWNER) chunkCompletionReroutes++;
        } else {
            chunkRequests.remove(request.correlationId());
            chunkCompletionRejections++;
        }
    }

    private void admitPacket(ServerGamePacketListenerImpl connection, ServerPlayer player, PendingPacket packet, String operationKind, ObjectOwnership prepared) {
        if (!isRunning() || player == null) { packetRejections++; return; }
        ObjectOwnership binding = prepared == null ? bindPlayer(player) : prepared;
        TaskCorrelationId correlation = new TaskCorrelationId(++sequence);
        packets.put(correlation, packet);
        ObjectOwnerGateway.Delivery delivery = objectGateway.submit(new OwnedObjectEnvelope(binding.objectId(), binding.objectEpoch(), operationKind,
            "minecraft-packet", correlation, new TaskSequence(sequence), AdmissionPolicy.REROUTE));
        if (delivery == ObjectOwnerGateway.Delivery.QUEUED || delivery == ObjectOwnerGateway.Delivery.REROUTED) {
            packetAdmissions++;
            if (delivery == ObjectOwnerGateway.Delivery.REROUTED) packetStaleReroutes++;
        } else {
            packets.remove(correlation);
            packetRejections++;
        }
    }

    @Override
    public void execute(RegionRuntime runtime, TaskPayload.OwnedObjectOperation operation) {
        objects.requireCurrentOwner(operation.objectId(), operation.expectedObjectEpoch(), runtime.id());
        PendingPacket packet = packets.remove(operation.correlationId());
        if (packet == null) throw new IllegalStateException("missing admitted packet " + operation.correlationId());
        try (PacketExecutionScope ignored = enterPacketExecution(operation.correlationId())) {
            packet.execute();
            packetExecutions++;
        }
    }

    @Override
    public void execute(RegionRuntime runtime, TaskPayload.ChunkCompletion completion) {
        ChunkWorkRequest request = chunkRequests.remove(completion.correlationId());
        if (request == null || request.requestGeneration() != completion.requestGeneration()) throw new IllegalStateException("missing or stale chunk completion " + completion.correlationId());
        RegionRoute current = directory.resolve(request.chunkKey()).orElseThrow();
        if (!current.region().equals(runtime.id())) throw new IllegalStateException("chunk completion reached foreign owner");
        // This observer has no base-game result-application privilege; the leased owner records only a validated completion admission.
        chunkCompletionApplications++;
    }

    private PacketExecutionScope enterPacketExecution(TaskCorrelationId correlation) {
        if (packetExecution.get() != null) { packetRecursions++; throw new IllegalStateException("nested packet replay"); }
        packetExecution.set(correlation);
        return new PacketExecutionScope(correlation);
    }

    private void failClosed(Throwable failure, RegionTaskEnvelope envelope) {
        if (!barrier.tripped()) {
            barrier.trip(failure, directory.snapshot().generation(), new OwnerEpoch(0), envelope);
            hardBarriers = 1;
        }
    }
    private boolean isRunning() { return state == State.RUNNING && scheduler != null && scheduler.lifecycle().acceptsSimulationWork(); }
    private static WorldId worldId(ServerLevel level) { return new WorldId(level.dimension().identifier().toString()); }
    /**
     * Stage 3's live admission ledger observes the authoritative entity collection only while the
     * controlled level lease is active. Position is retained as a routing key; the temporary Stage 2
     * world-default route deliberately keeps execution serial until geography is regionalized later.
     */
    private void refreshEntityBindings(ServerLevel level) {
        WorldId world = worldId(level);
        HashSet<String> present = new HashSet<>();
        for (Entity entity : level.getAllEntities()) {
            String id = entity.getUUID().toString();
            present.add(id);
            RoutingKey key = new RoutingKey(world, entity.chunkPosition().x(), entity.chunkPosition().z());
            var existing = objects.current(id);
            if (existing.isEmpty()) objects.admit(id, entity instanceof ServerPlayer ? OwnedObjectKind.PLAYER : OwnedObjectKind.ENTITY, key);
            else if (!existing.orElseThrow().routingKey().equals(key)) objects.move(id, key);
        }
        for (var previous : objects.ownedInWorld(world)) if (!present.contains(previous.objectId())) objects.remove(previous.objectId());
    }
    private ObjectOwnership bindPlayer(ServerPlayer player) {
        WorldId world = worldId(player.level());
        String id = player.getUUID().toString();
        RoutingKey key = new RoutingKey(world, player.chunkPosition().x(), player.chunkPosition().z());
        var existing = objects.current(id);
        if (existing.isEmpty()) return objects.admit(id, OwnedObjectKind.PLAYER, key);
        if (!existing.orElseThrow().routingKey().equals(key)) return objects.move(id, key);
        return existing.orElseThrow();
    }
    private void logStatus(String event) { logger.info("PHOENIUM_STAGE2 {} {}", event, diagnostics().toLogLine()); }

    private sealed interface PendingPacket permits PendingMove, PendingInteract {
        void execute();
    }
    private record PendingMove(ServerGamePacketListenerImpl connection, ServerboundMovePlayerPacket packet) implements PendingPacket {
        @Override public void execute() { ((ServerGamePacketListenerInvoker) connection).phoenium$invokeMovePlayer(packet); }
    }
    private record PendingInteract(ServerGamePacketListenerImpl connection, ServerboundInteractPacket packet) implements PendingPacket {
        @Override public void execute() { ((ServerGamePacketListenerInvoker) connection).phoenium$invokeInteract(packet); }
    }
    private final class PacketExecutionScope implements AutoCloseable {
        private final TaskCorrelationId correlation;
        private boolean closed;
        private PacketExecutionScope(TaskCorrelationId correlation) { this.correlation = correlation; }
        @Override public void close() {
            if (closed || packetExecution.get() != correlation) throw new IllegalStateException("packet execution marker escaped");
            packetExecution.remove();
            closed = true;
        }
    }
    private record LiveRegion(ServerLevel level, RegionId id, RoutingKey key, RegionRuntime runtime) { }
    private record PendingTick(ServerLevel level, BooleanSupplier shouldKeepTicking, RegionTaskEnvelope envelope) { }
    public enum State { NEW, RUNNING, STOPPED }
}
