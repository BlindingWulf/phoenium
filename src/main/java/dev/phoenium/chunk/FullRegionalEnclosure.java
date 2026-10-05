package dev.phoenium.chunk;

import java.util.*;
import dev.phoenium.ownership.ObjectOwnershipRegistry;
import dev.phoenium.ownership.OwnershipContext;
import dev.phoenium.region.*;
import dev.phoenium.scheduler.OwnerGateway;
import dev.phoenium.topology.*;

/** Uses the existing quiescent topology protocol to merge complete participant domains.
 * No region waits for another, and no route is inferred from a future proposal.
 */
public final class FullRegionalEnclosure {
    private final RegionDirectory directory;
    private final TopologyCoordinator coordinator;
    public FullRegionalEnclosure(RegionDirectory directory, Map<RegionId, RegionRuntime> runtimes,
                                 OwnerGateway gateway, ObjectOwnershipRegistry objects) {
        this(directory, runtimes, gateway, objects, RegionalStateMigration.NONE);
    }
    public FullRegionalEnclosure(RegionDirectory directory, Map<RegionId, RegionRuntime> runtimes,
                                 OwnerGateway gateway, ObjectOwnershipRegistry objects,
                                 RegionalStateMigration stateMigration) {
        this.directory = directory;
        coordinator = new TopologyCoordinator(directory, runtimes, gateway, objects, stateMigration);
    }
    public boolean advance() {
        if (OwnershipContext.current().isPresent()) return false;
        return coordinator.advance();
    }
    public void installStateMigration(RegionalStateMigration stateMigration) {
        coordinator.installStateMigration(stateMigration);
    }
    public boolean enclose(Set<RoutingKey> keys) {
        if (OwnershipContext.current().isPresent()) throw new ChunkProtocolException("FULL enclosure requested inside an owner claim");
        if (coordinator.hasPendingConvergence() || coordinator.hasPendingTransition()) { advance(); return false; }
        var participants = new TreeSet<RegionId>();
        for (var key : keys) {
            if (directory.pendingChange(key).isPresent()) return false;
            var route = directory.resolve(key).orElse(null);
            if (route == null) return false;
            participants.add(route.region());
        }
        if (participants.size() <= 1) return true;
        var destination = participants.first();
        if (Boolean.getBoolean("phoenium.test.diagnostics")) {
            try {
                org.slf4j.LoggerFactory.getLogger(FullRegionalEnclosure.class).info(
                    "PHOENIUM_REGION_ENCLOSURE participants={} destination={} keys={} sample={}",
                    participants, destination, keys.size(), keys.stream().limit(6).toList());
                var frames = new Throwable().getStackTrace();
                StringBuilder stack = new StringBuilder();
                for (int i = 1; i < Math.min(frames.length, 16); i++) stack.append(" | at ").append(frames[i]);
                org.slf4j.LoggerFactory.getLogger(FullRegionalEnclosure.class).info("PHOENIUM_REGION_ENCLOSURE_STACK{}", stack);
            } catch (Throwable ignored) { /* Diagnostics never replace the topology outcome. */ }
        }
        if (participants.stream().anyMatch(region -> !region.world().equals(destination.world())))
            throw new ChunkProtocolException("regional FULL enclosure cannot cross world identity");
        var owners = new LinkedHashMap<RoutingKey, RegionId>();
        directory.snapshot().routes().forEach((key, route) -> owners.put(key,
                participants.contains(route.region()) ? destination : route.region()));
        coordinator.recordConvergence(keys, new TopologyCoordinator.Transition(participants, owners),
                "FULL initialized-input and callback enclosure");
        advance();
        return false; // The caller must recapture the published routes and input receipt.
    }
}
