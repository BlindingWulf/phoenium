package dev.phoenium.chunk;

import java.util.Objects;
import java.util.function.Supplier;
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

    /**
     * Every other native caller of the inherited {@code ServerLevelAccessor.addFreshEntityWithPassengers}
     * loop (skeleton trap, spawn eggs and jockeys, structure templates, raids, breeding...) gets the
     * same graph custody as the command path. A root already admitted by an enclosing command or
     * natural-spawn custody keeps that exact admission; a passenger-free root is the untouched call.
     */
    public static void insertInheritedGraph(ServerLevel level, Entity root,
            Supplier<FreshPassengerAdmission.Publisher> publisher, Runnable original) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(publisher, "publisher");
        Objects.requireNonNull(original, "original");
        if (root.getPassengers().isEmpty()) {
            original.run();
            return;
        }
        admitted(level, root, publisher, original);
    }

    public static void insert(ServerLevel level, Entity root, FreshPassengerAdmission.Publisher publisher,
            Runnable original) {
        Objects.requireNonNull(publisher, "publisher");
        admitted(level, root, () -> publisher, original);
    }

    /** A root already inside a pending (command, natural, or detached finalization) custody keeps it. */
    private static void admitted(ServerLevel level, Entity root,
            Supplier<FreshPassengerAdmission.Publisher> publisher, Runnable original) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(original, "original");
        if (root.getPassengers().isEmpty()
                || FullFinishingServices.forServer(level.getServer()).entityFacts.pendingPassengerIdentity(root)) {
            original.run();
            return;
        }
        try (var turn = FullFinishingServices.forServer(level.getServer()).referenceAccess();
             var graph = FreshPassengerAdmission.begin(level, root, publisher.get())) {
            original.run();
            graph.validate();
        }
    }
}
