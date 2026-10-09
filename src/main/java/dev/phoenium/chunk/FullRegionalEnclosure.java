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
        // S9-D7: in a uniformly routed world each key below has no pending change and resolves to one region.
        if (!keys.isEmpty()) {
            var world = keys.iterator().next().world();
            if (directory.uniformRoute(world) != null && RegionDirectory.sameWorld(keys, world)) return true;
        }
        // S9-D7: every admission of an already enclosed footprint resolves to one region. Keep the same per-key
        // checks in the same order, and build the ordered participant set only once a second region appears
        // (RegionId.equals and compareTo agree, so the set's contents and order are unchanged).
        RegionId first = null;
        TreeSet<RegionId> participants = null;
        for (var key : keys) {
            if (directory.pendingChange(key).isPresent()) return false;
            var route = directory.resolve(key).orElse(null);
            if (route == null) return false;
            RegionId region = route.region();
            if (participants != null) participants.add(region);
            else if (first == null) first = region;
            else if (!first.equals(region)) { participants = new TreeSet<>(); participants.add(first); participants.add(region); }
        }
        if (participants == null || participants.size() <= 1) return true;
        TreeSet<RegionId> merged = participants;
        var destination = merged.first();
        if (Boolean.getBoolean("phoenium.test.diagnostics")) {
            try {
                org.slf4j.LoggerFactory.getLogger(FullRegionalEnclosure.class).info(
                    "PHOENIUM_REGION_ENCLOSURE participants={} destination={} keys={} sample={}",
                    merged, destination, keys.size(), keys.stream().limit(6).toList());
                var frames = new Throwable().getStackTrace();
                StringBuilder stack = new StringBuilder();
                for (int i = 1; i < Math.min(frames.length, 16); i++) stack.append(" | at ").append(frames[i]);
                org.slf4j.LoggerFactory.getLogger(FullRegionalEnclosure.class).info("PHOENIUM_REGION_ENCLOSURE_STACK{}", stack);
            } catch (Throwable ignored) { /* Diagnostics never replace the topology outcome. */ }
        }
        if (merged.stream().anyMatch(region -> !region.world().equals(destination.world())))
            throw new ChunkProtocolException("regional FULL enclosure cannot cross world identity");
        var owners = new LinkedHashMap<RoutingKey, RegionId>();
        directory.snapshot().routes().forEach((key, route) -> owners.put(key,
                merged.contains(route.region()) ? destination : route.region()));
        coordinator.recordConvergence(keys, new TopologyCoordinator.Transition(merged, owners),
                "FULL initialized-input and callback enclosure");
        advance();
        return false; // The caller must recapture the published routes and input receipt.
    }
}
