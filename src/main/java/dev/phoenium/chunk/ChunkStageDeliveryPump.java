package dev.phoenium.chunk;

import dev.phoenium.topology.RegionDirectory;
import dev.phoenium.topology.RegionRoute;
import java.util.Objects;

/**
 * No-owner producer handoff.  It publishes identity before delivery, resolves the current route,
 * and hands a typed stage to an owner inbox without joining, blocking, or executing the stage.
 */
public final class ChunkStageDeliveryPump {
    private final RegionDirectory directory;
    private final ChunkStageOwnerGateway ownerGateway;
    private final ChunkStageExecutor executor;
    private final java.util.concurrent.ConcurrentHashMap<PendingVanillaStage<?, ?>, RegionDirectory.PendingChange> parked
            = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean open = true;

    public ChunkStageDeliveryPump(RegionDirectory directory, ChunkStageOwnerGateway ownerGateway, ChunkStageExecutor executor) {
        this.directory = Objects.requireNonNull(directory, "directory"); this.ownerGateway = Objects.requireNonNull(ownerGateway, "ownerGateway");
        this.executor = Objects.requireNonNull(executor, "executor");
    }
    public ChunkStageOwnerGateway.Delivery publishAndDeliver(PendingVanillaStage<?, ?> stage) {
        Objects.requireNonNull(stage, "stage");
        stage.publishReady();
        return deliverPublished(stage);
    }
    /** Used after a stale inbox delivery; StageId remains queued and is never replaced. */
    public ChunkStageOwnerGateway.Delivery deliverPublished(PendingVanillaStage<?, ?> stage) {
        Objects.requireNonNull(stage, "stage");
        if (!stage.published()) throw new IllegalStateException("stage must be published before delivery");
        if (!open) { stage.control(ChunkStageAdmission.Result.SHUTDOWN); return ChunkStageOwnerGateway.Delivery.REJECTED_SHUTDOWN; }
        ChunkStageAdmission.Result terminal = stage.deliveryControl();
        if (terminal != null) {
            parked.remove(stage);
            stage.control(terminal);
            return terminal == ChunkStageAdmission.Result.SHUTDOWN ? ChunkStageOwnerGateway.Delivery.REJECTED_SHUTDOWN
                    : ChunkStageOwnerGateway.Delivery.REJECTED_ROUTE;
        }
        RegionDirectory.PendingChange change = directory.pendingChange(stage.chunk()).orElse(null);
        if (change != null && !change.completed() && !stage.outcome().isDone()) {
            parked.put(stage, change);
            // A racing shutdown must not leave a newly parked reference behind.
            if (!open && parked.remove(stage, change)) stage.control(ChunkStageAdmission.Result.SHUTDOWN);
            return ChunkStageOwnerGateway.Delivery.PARKED_TOPOLOGY;
        }
        RegionRoute route = directory.resolve(stage.chunk()).orElse(null);
        if (route == null) {
            // Recheck publication: the proposal may have committed between the two observations.
            route = directory.resolve(stage.chunk()).orElse(null);
            if (route == null) {
                executor.rejectUnroutable(stage);
                return ChunkStageOwnerGateway.Delivery.REJECTED_ROUTE;
            }
        }
        ChunkStageOwnerGateway.Delivery delivery = ownerGateway.submit(route, stage);
        if (delivery == ChunkStageOwnerGateway.Delivery.REJECTED_SHUTDOWN) stage.control(ChunkStageAdmission.Result.SHUTDOWN);
        else if (delivery == ChunkStageOwnerGateway.Delivery.REJECTED_ROUTE) executor.rejectUnroutable(stage);
        return delivery;
    }

    /** The real owner-free event-loop poll consumes the coordinator's completion wakeup. */
    public void retryTopology() {
        parked.forEach((stage, change) -> {
            if (stage.outcome().isDone()) parked.remove(stage, change);
            else if ((change.completed() || !open || stage.deliveryControl() != null) && parked.remove(stage, change)) deliverPublished(stage);
        });
    }

    public int parkedCount() { return parked.size(); }
    public void shutdown() {
        open = false;
        parked.forEach((stage, change) -> {
            if (parked.remove(stage, change)) stage.control(ChunkStageAdmission.Result.SHUTDOWN);
        });
    }
}
