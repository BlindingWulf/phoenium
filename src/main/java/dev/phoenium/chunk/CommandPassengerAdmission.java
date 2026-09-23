package dev.phoenium.chunk;

import java.util.Objects;
import dev.phoenium.topology.RegionRoute;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/**
 * Custody for the exact native {@code ServerLevel.tryAddFreshEntityWithPassengers}
 * insertion call, after its duplicate-UUID test has already succeeded. The
 * inherited native loop inserts the root before each passenger and can start
 * tracking the root synchronously, so every passenger identity must be staged
 * before the first original insertion.
 */
public final class CommandPassengerAdmission {
    private CommandPassengerAdmission() { }

    /** A coordinator may stage a graph only inside one stable owner route. */
    public static RegionRoute requireCommonRoute(RegionRoute baseline, RegionRoute candidate) {
        Objects.requireNonNull(candidate, "candidate");
        if (baseline == null) return candidate;
        if (!baseline.region().equals(candidate.region())
                || !baseline.generation().equals(candidate.generation())
                || !baseline.ownerEpoch().equals(candidate.ownerEpoch()))
            throw new IllegalStateException("command passenger graph crosses region or topology boundary");
        return baseline;
    }

    public static void insert(ServerLevel level, Entity root, FreshPassengerAdmission.Publisher publisher,
            Runnable original) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(publisher, "publisher");
        Objects.requireNonNull(original, "original");
        if (root.getPassengers().isEmpty()) {
            original.run();
            return;
        }
        try (var turn = FullFinishingServices.forServer(level.getServer()).referenceAccess();
             var graph = FreshPassengerAdmission.begin(level, root, publisher)) {
            original.run();
            graph.validate();
        }
    }
}
