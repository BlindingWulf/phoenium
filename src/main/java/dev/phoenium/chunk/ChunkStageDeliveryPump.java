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

    public ChunkStageDeliveryPump(RegionDirectory directory, ChunkStageOwnerGateway ownerGateway) {
        this.directory = Objects.requireNonNull(directory, "directory"); this.ownerGateway = Objects.requireNonNull(ownerGateway, "ownerGateway");
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
        RegionRoute route = directory.resolve(stage.chunk()).orElse(null);
        if (route == null) { stage.control(ChunkStageAdmission.Result.CANCELLED); return ChunkStageOwnerGateway.Delivery.REJECTED_ROUTE; }
        ChunkStageOwnerGateway.Delivery delivery = ownerGateway.submit(route, stage);
        if (delivery == ChunkStageOwnerGateway.Delivery.REJECTED_SHUTDOWN) stage.control(ChunkStageAdmission.Result.SHUTDOWN);
        return delivery;
    }
}
