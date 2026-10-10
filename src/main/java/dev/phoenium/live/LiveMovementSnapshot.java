package dev.phoenium.live;

import dev.phoenium.core.WorldId;
import dev.phoenium.topology.LiveMovementFootprint;
import dev.phoenium.topology.RoutingKey;
import java.util.List;
import java.util.Set;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

/**
 * A deliberately small, immutable adaptation of the state that exists at the approved valid-input
 * movement seam.  This is not a replacement player model: it is consumed once to decide whether
 * vanilla's complete handler may be replayed under the current owner lease.
 */
public record LiveMovementSnapshot(LiveMovementFootprint footprint, Disposition disposition, boolean passenger) {
    public enum Disposition {
        CLASSIFIED,
        NON_FINITE,
        AWAITING_TELEPORT,
        /** A rider whose vehicle is absent, removed, or in another level: no vanilla passenger state to replay. */
        PASSENGER,
        NO_PHYSICS,
        SLEEPING,
        DIMENSION_TRANSITION,
        LAST_GOOD_MISMATCH,
        UNREPRESENTABLE_BOUNDS
    }

    public boolean classified() { return disposition == Disposition.CLASSIFIED; }

    /** The player facts the valid-input seam reads, separated from the live player so classification is pure. */
    public record State(WorldId world, boolean passenger, boolean vehicleInLevel, boolean noPhysics, boolean sleeping,
                        boolean changingDimension, double x, double y, double z, AABB bounds, double maxUpStep) {
        public static State of(ServerPlayer player) {
            Entity vehicle = player.getVehicle();
            return new State(WorldId.of(player.level().dimension().identifier().toString()), vehicle != null,
                vehicle != null && !vehicle.isRemoved() && vehicle.level() == player.level(),
                player.noPhysics, player.isSleeping(), player.isChangingDimension(),
                player.getX(), player.getY(), player.getZ(), player.getBoundingBox(), player.maxUpStep());
        }
    }

    public static LiveMovementSnapshot capture(ServerPlayer player, ServerboundMovePlayerPacket packet,
                                               double lastGoodX, double lastGoodY, double lastGoodZ,
                                               boolean awaitingTeleport) {
        return classify(State.of(player), packet.getX(player.getX()), packet.getY(player.getY()), packet.getZ(player.getZ()),
            lastGoodX, lastGoodY, lastGoodZ, awaitingTeleport);
    }

    public static LiveMovementSnapshot classify(State state, double requestedX, double requestedY, double requestedZ,
                                                double lastGoodX, double lastGoodY, double lastGoodZ,
                                                boolean awaitingTeleport) {
        if (awaitingTeleport) return rejected(Disposition.AWAITING_TELEPORT);
        if (state.passenger()) {
            // Vanilla's passenger branch discards the packet position (and lastGood): it snaps only the
            // rotation at the rider's current position and re-runs ChunkMap.move, which is where a rider
            // pairs trackers whose chunks were still pending when it was added (a mounted portal arrival).
            // Its footprint is therefore the settled rider position with no sweep.
            if (state.changingDimension()) return rejected(Disposition.DIMENSION_TRANSITION);
            if (!state.vehicleInLevel()) return rejected(Disposition.PASSENGER);
            if (!finite(state.x(), state.y(), state.z())) return rejected(Disposition.NON_FINITE);
            return footprint(state, state.x(), state.y(), state.z(), 0.0D, 0.0D, 0.0D, true);
        }
        if (state.noPhysics()) return rejected(Disposition.NO_PHYSICS);
        if (state.sleeping()) return rejected(Disposition.SLEEPING);
        if (state.changingDimension()) return rejected(Disposition.DIMENSION_TRANSITION);

        double currentX = state.x();
        double currentY = state.y();
        double currentZ = state.z();
        if (!finite(currentX, currentY, currentZ, requestedX, requestedY, requestedZ,
                lastGoodX, lastGoodY, lastGoodZ)) return rejected(Disposition.NON_FINITE);
        // The handler's delta is measured from lastGood.  A disagreement means a correction or
        // another unclassified handler state is already in progress, so ordinary replay is unsafe.
        if (Double.compare(currentX, lastGoodX) != 0 || Double.compare(currentY, lastGoodY) != 0
            || Double.compare(currentZ, lastGoodZ) != 0) return rejected(Disposition.LAST_GOOD_MISMATCH);

        double dx = requestedX - lastGoodX;
        double dy = requestedY - lastGoodY;
        double dz = requestedZ - lastGoodZ;
        if (!finite(dx, dy, dz)) return rejected(Disposition.NON_FINITE);
        return footprint(state, requestedX, requestedY, requestedZ, dx, dy, dz, false);
    }

    private static LiveMovementSnapshot footprint(State state, double requestedX, double requestedY, double requestedZ,
                                                  double dx, double dy, double dz, boolean passenger) {
        AABB source = state.bounds();
        AABB swept = source.expandTowards(dx, dy, dz);
        // The one-block inflation covers collision cursor edges, support, and fluid horizontal
        // neighbor inspection.  The step path can be no narrower than this horizontal envelope.
        AABB padded = swept.inflate(1.0D, Math.max(1.0D, state.maxUpStep()) + 1.0D, 1.0D);
        WorldId world = state.world();
        LiveMovementFootprint.ChunkBox broad = box(world, padded);
        if (broad == null) return rejected(Disposition.UNREPRESENTABLE_BOUNDS);
        RoutingKey sourceKey = key(world, state.x(), state.z());
        RoutingKey destination = key(world, requestedX, requestedZ);
        if (sourceKey == null || destination == null) return rejected(Disposition.UNREPRESENTABLE_BOUNDS);
        LiveMovementFootprint footprint = new LiveMovementFootprint(world, destination,
            List.of(broad), List.of(broad), List.of(broad), List.of(broad), List.of(broad),
            keys(sourceKey, destination));
        return new LiveMovementSnapshot(footprint, Disposition.CLASSIFIED, passenger);
    }

    private static LiveMovementSnapshot rejected(Disposition reason) { return new LiveMovementSnapshot(null, reason, false); }
    private static boolean finite(double... values) {
        for (double value : values) if (!Double.isFinite(value)) return false;
        return true;
    }
    private static RoutingKey key(WorldId world, double x, double z) {
        long chunkX = floor(x) >> 4;
        long chunkZ = floor(z) >> 4;
        if (!representable(chunkX) || !representable(chunkZ)) return null;
        return new RoutingKey(world, (int) chunkX, (int) chunkZ);
    }
    private static LiveMovementFootprint.ChunkBox box(WorldId world, AABB bounds) {
        if (!finite(bounds.minX, bounds.maxX, bounds.minZ, bounds.maxZ)) return null;
        long minX = floor(bounds.minX) >> 4;
        long maxX = floor(bounds.maxX) >> 4;
        long minZ = floor(bounds.minZ) >> 4;
        long maxZ = floor(bounds.maxZ) >> 4;
        if (!representable(minX) || !representable(maxX) || !representable(minZ) || !representable(maxZ)) return null;
        return new LiveMovementFootprint.ChunkBox(world, minX, maxX, minZ, maxZ);
    }
    private static long floor(double value) {
        if (value < Long.MIN_VALUE || value > Long.MAX_VALUE) return Long.MIN_VALUE;
        return (long) Math.floor(value);
    }
    private static boolean representable(long value) { return value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE; }
    private static Set<RoutingKey> keys(RoutingKey source, RoutingKey destination) {
        return source.equals(destination) ? Set.of(source) : Set.of(source, destination);
    }
}
