package dev.phoenium.stage4probe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.phoenium.chunk.FullRegionalEnclosure;
import dev.phoenium.core.OwnerEpoch;
import dev.phoenium.core.TopologyGeneration;
import dev.phoenium.core.WorldId;
import dev.phoenium.failure.FailureBarrier;
import dev.phoenium.ownership.ObjectOwnershipRegistry;
import dev.phoenium.ownership.OwnedObjectKind;
import dev.phoenium.region.RegionId;
import dev.phoenium.region.RegionLifecycle;
import dev.phoenium.region.RegionRuntime;
import dev.phoenium.scheduler.OwnerGateway;
import dev.phoenium.scheduler.RegionScheduler;
import dev.phoenium.topology.RegionDirectory;
import dev.phoenium.topology.RegionRoute;
import dev.phoenium.topology.RegionalStateMigration;
import dev.phoenium.topology.RoutingKey;
import dev.phoenium.topology.TopologyCoordinator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class Stage4TeleportTopologyTest {
    @Test
    void scopedTeleportUsesRealMigrationRestoresOrdinaryOwnersAndPreservesLaterRoutes() {
        Fixture f = new Fixture();
        AtomicInteger prepared = new AtomicInteger(), installed = new AtomicInteger();
        RegionalStateMigration migration = proposed -> {
            prepared.incrementAndGet();
            return published -> installed.incrementAndGet();
        };
        FullRegionalEnclosure enclosure = new FullRegionalEnclosure(
            f.directory, f.runtimes, f.gateway, f.objects, migration);
        var player = f.objects.admit("player", OwnedObjectKind.PLAYER, f.source);

        var receipt = Stage4TeleportTopology.prepareTopology(enclosure, f.directory, f.objects,
            player, f.destination, Set.of(f.source, f.destination, f.halo));

        assertEquals(f.a, f.owner(f.destination));
        assertEquals(f.a, f.owner(f.halo));
        assertEquals(f.b, f.owner(f.unrelated));
        assertEquals(f.a, f.objects.current("player").orElseThrow().owner());
        assertEquals(1, prepared.get());
        assertEquals(1, installed.get());

        // A separate, legitimate coordinator publication adds a route after preparation. The
        // teleport receipt must build restoration from this current view rather than republish
        // its captured map and erase the new route.
        Map<RoutingKey, RegionId> expanded = f.owners();
        expanded.put(f.later, f.a);
        TopologyCoordinator later = new TopologyCoordinator(f.directory, f.runtimes, f.gateway, f.objects);
        later.request(new TopologyCoordinator.Transition(Set.of(f.a), expanded));
        assertEquals(true, later.advance());

        var current = f.objects.current("player").orElseThrow();
        f.objects.updateRoutingKey("player", f.destination, current.objectEpoch());
        receipt.complete("player", player.incarnation(), f.destination);

        assertEquals(f.b, f.owner(f.destination));
        assertEquals(f.b, f.owner(f.halo));
        assertEquals(f.b, f.owner(f.unrelated));
        assertEquals(f.a, f.owner(f.later));
        assertEquals(f.b, f.objects.current("player").orElseThrow().owner());
        assertEquals(f.destination, f.objects.current("player").orElseThrow().routingKey());
        assertEquals(2, prepared.get());
        assertEquals(2, installed.get());
        assertFalse(enclosure.advance());
    }

    @Test
    void restorationRejectsAnInterveningChangeInsteadOfOverwritingIt() {
        Fixture f = new Fixture();
        FullRegionalEnclosure enclosure = new FullRegionalEnclosure(
            f.directory, f.runtimes, f.gateway, f.objects, RegionalStateMigration.NONE);
        var player = f.objects.admit("player", OwnedObjectKind.PLAYER, f.source);
        var receipt = Stage4TeleportTopology.prepareTopology(enclosure, f.directory, f.objects,
            player, f.destination, Set.of(f.source, f.destination, f.halo));
        var current = f.objects.current("player").orElseThrow();
        f.objects.updateRoutingKey("player", f.destination, current.objectEpoch());

        Map<RoutingKey, RegionId> changed = f.owners();
        changed.put(f.halo, f.b);
        TopologyCoordinator other = new TopologyCoordinator(f.directory, f.runtimes, f.gateway, f.objects);
        other.request(new TopologyCoordinator.Transition(Set.of(f.a, f.b), changed));
        assertEquals(true, other.advance());

        assertThrows(IllegalStateException.class,
            () -> receipt.complete("player", player.incarnation(), f.destination));
        assertEquals(f.b, f.owner(f.halo));
    }

    @Test
    void restorationRejectsAPlayerThatLeftAndReturnedToTheTemporaryOwnerEpoch() {
        Fixture f = new Fixture();
        FullRegionalEnclosure enclosure = new FullRegionalEnclosure(
            f.directory, f.runtimes, f.gateway, f.objects, RegionalStateMigration.NONE);
        var player = f.objects.admit("player", OwnedObjectKind.PLAYER, f.source);
        var receipt = Stage4TeleportTopology.prepareTopology(enclosure, f.directory, f.objects,
            player, f.destination, Set.of(f.source, f.destination, f.halo));
        var moved = f.objects.updateRoutingKey("player", f.destination,
            f.objects.current("player").orElseThrow().objectEpoch());

        Map<RoutingKey, RegionId> toB = f.owners();
        toB.put(f.destination, f.b);
        transition(f, toB);
        Map<RoutingKey, RegionId> backToA = f.owners();
        backToA.put(f.destination, f.a);
        transition(f, backToA);

        var returned = f.objects.current("player").orElseThrow();
        assertEquals(f.a, returned.owner());
        assertEquals(f.destination, returned.routingKey());
        assertFalse(moved.objectEpoch().equals(returned.objectEpoch()));
        assertThrows(IllegalStateException.class,
            () -> receipt.complete("player", player.incarnation(), f.destination));
    }

    @Test
    void preparationRejectsMissingClosureRouteWithoutPublishingAuthority() {
        Fixture f = new Fixture();
        FullRegionalEnclosure enclosure = new FullRegionalEnclosure(
            f.directory, f.runtimes, f.gateway, f.objects, RegionalStateMigration.NONE);
        var player = f.objects.admit("player", OwnedObjectKind.PLAYER, f.source);
        RoutingKey missing = new RoutingKey(f.world, 99, 99);
        long generation = f.directory.snapshot().generation().value();

        assertThrows(IllegalStateException.class, () -> Stage4TeleportTopology.prepareTopology(
            enclosure, f.directory, f.objects, player, f.destination, Set.of(f.source, f.destination, missing)));
        assertEquals(generation, f.directory.snapshot().generation().value());
        assertEquals(f.b, f.owner(f.destination));
    }

    @Test
    void exactFixtureTeleportSequenceRestoresDomainsBeforeMergeAndKeepsSplitOwnersDistinct() {
        WorldId world = new WorldId("minecraft:overworld");
        RegionId a = new RegionId(world, "stage3-fixture-a");
        RegionId b = new RegionId(world, "stage3-fixture-b");
        RegionRuntime aRuntime = Fixture.ready(a), bRuntime = Fixture.ready(b);
        Map<RegionId, RegionRuntime> runtimes = new LinkedHashMap<>(Map.of(a, aRuntime, b, bRuntime));
        RegionDirectory directory = new RegionDirectory();
        TopologyGeneration generation = new TopologyGeneration(1);
        Map<RoutingKey, RegionRoute> declared = new LinkedHashMap<>();
        for (int x = -22; x <= 54; x++) for (int z = -22; z <= 22; z++) {
            RoutingKey key = new RoutingKey(world, x, z);
            RegionId owner = x >= 5 && x <= 32 && z >= -4 && z <= 4 ? b : a;
            declared.put(key, new RegionRoute(owner, generation,
                owner.equals(a) ? aRuntime.ownerEpoch() : bRuntime.ownerEpoch()));
        }
        directory.publish(generation, declared);
        ObjectOwnershipRegistry objects = new ObjectOwnershipRegistry(directory);
        RegionScheduler scheduler = new RegionScheduler(runtimes, directory, new FailureBarrier());
        OwnerGateway gateway = new OwnerGateway(directory, runtimes, scheduler);
        AtomicInteger migrations = new AtomicInteger();
        RegionalStateMigration migration = proposed -> published -> migrations.incrementAndGet();
        FullRegionalEnclosure enclosure = new FullRegionalEnclosure(directory, runtimes, gateway, objects, migration);
        String playerId = "fixture-player";
        RoutingKey current = new RoutingKey(world, 0, 0);
        objects.admit(playerId, OwnedObjectKind.PLAYER, current);

        // view=5, simulation=5, plus the native one-chunk client border.
        for (RoutingKey destination : new RoutingKey[] {
            new RoutingKey(world, 0, 0), new RoutingKey(world, 7, 0),
            new RoutingKey(world, 32, 0), new RoutingKey(world, 32, 12),
            new RoutingKey(world, 0, 0)
        }) current = scopedMove(enclosure, directory, objects, playerId, current, destination, 6);

        assertEquals(a, directory.resolve(new RoutingKey(world, 0, 0)).orElseThrow().region());
        assertEquals(b, directory.resolve(new RoutingKey(world, 7, 0)).orElseThrow().region());
        assertEquals(b, directory.resolve(new RoutingKey(world, 32, 0)).orElseThrow().region());
        assertEquals(a, directory.resolve(new RoutingKey(world, 32, 12)).orElseThrow().region());

        Set<RoutingKey> merge = Set.of(new RoutingKey(world, 0, 0), new RoutingKey(world, 7, 0));
        boolean merged = enclosure.enclose(merge);
        for (int attempt = 0; !merged && attempt < 8; attempt++) {
            merged = enclosure.enclose(merge);
            if (!merged) enclosure.advance();
        }
        assertEquals(true, merged);

        Map<RoutingKey, RegionId> split = new LinkedHashMap<>();
        directory.snapshot().routes().forEach((key, route) -> split.put(key,
            key.x() >= 12 && key.x() <= 54 && key.z() >= -22 && key.z() <= 22 ? b : a));
        TopologyCoordinator coordinator = coordinator(enclosure);
        coordinator.request(new TopologyCoordinator.Transition(Set.of(a, b), split));
        assertEquals(true, coordinator.advance());

        current = scopedMove(enclosure, directory, objects, playerId, current,
            new RoutingKey(world, 7, 0), 6);
        current = scopedMove(enclosure, directory, objects, playerId, current,
            new RoutingKey(world, 32, 0), 6);
        current = scopedMove(enclosure, directory, objects, playerId, current,
            new RoutingKey(world, 32, 12), 6);

        assertEquals(b, objects.current(playerId).orElseThrow().owner());
        assertEquals(a, directory.resolve(new RoutingKey(world, 7, 0)).orElseThrow().region());
        assertEquals(b, directory.resolve(new RoutingKey(world, 32, 0)).orElseThrow().region());
        assertEquals(b, directory.resolve(new RoutingKey(world, 32, 12)).orElseThrow().region());
        assertEquals(true, migrations.get() >= 6);
    }

    @Test void unchangedReceiptAcceptsCurrentOwnerAfterIndependentLegitimateConvergence() {
        Fixture f = new Fixture();
        Map<RoutingKey, RegionId> unified = f.owners();
        unified.replaceAll((key, owner) -> f.a);
        transition(f, unified);
        var enclosure = new FullRegionalEnclosure(f.directory, f.runtimes, f.gateway, f.objects, RegionalStateMigration.NONE);
        var player = f.objects.admit("player", OwnedObjectKind.PLAYER, f.source);
        var receipt = Stage4TeleportTopology.prepareTopology(enclosure, f.directory, f.objects,
            player, f.destination, Set.of(f.source, f.destination, f.halo));
        f.objects.updateRoutingKey("player", f.destination, player.objectEpoch());
        unified.replaceAll((key, owner) -> f.b);
        transition(f, unified);
        receipt.complete("player", player.incarnation(), f.destination);
        assertEquals(f.b, f.objects.current("player").orElseThrow().owner());
        assertEquals(f.b, f.owner(f.halo));
        assertThrows(IllegalStateException.class, () -> receipt.complete("player", player.incarnation(), f.destination));
    }

    private static RoutingKey scopedMove(FullRegionalEnclosure enclosure, RegionDirectory directory,
                                         ObjectOwnershipRegistry objects, String playerId,
                                         RoutingKey source, RoutingKey destination, int radius) {
        var player = objects.current(playerId).orElseThrow();
        assertEquals(source, player.routingKey());
        var receipt = Stage4TeleportTopology.prepareTopology(enclosure, directory, objects, player,
            destination, Stage4TeleportTopology.closure(source, destination, radius));
        player = objects.current(playerId).orElseThrow();
        objects.updateRoutingKey(playerId, destination, player.objectEpoch());
        receipt.complete(playerId, player.incarnation(), destination);
        return destination;
    }

    private static TopologyCoordinator coordinator(FullRegionalEnclosure enclosure) {
        try {
            var field = FullRegionalEnclosure.class.getDeclaredField("coordinator");
            field.setAccessible(true);
            return (TopologyCoordinator) field.get(enclosure);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void transition(Fixture f, Map<RoutingKey, RegionId> owners) {
        TopologyCoordinator coordinator = new TopologyCoordinator(f.directory, f.runtimes, f.gateway, f.objects);
        coordinator.request(new TopologyCoordinator.Transition(Set.of(f.a, f.b), owners));
        assertEquals(true, coordinator.advance());
    }

    private static final class Fixture {
        final WorldId world = new WorldId("teleport-test");
        final RegionId a = new RegionId(world, "a"), b = new RegionId(world, "b");
        final RoutingKey source = new RoutingKey(world, 0, 0);
        final RoutingKey destination = new RoutingKey(world, 7, 0);
        final RoutingKey halo = new RoutingKey(world, 8, 0);
        final RoutingKey unrelated = new RoutingKey(world, 20, 0);
        final RoutingKey later = new RoutingKey(world, 30, 0);
        final RegionRuntime aRuntime = ready(a), bRuntime = ready(b);
        final Map<RegionId, RegionRuntime> runtimes = new LinkedHashMap<>(Map.of(a, aRuntime, b, bRuntime));
        final RegionDirectory directory = new RegionDirectory();
        final ObjectOwnershipRegistry objects = new ObjectOwnershipRegistry(directory);
        final RegionScheduler scheduler = new RegionScheduler(runtimes, directory, new FailureBarrier());
        final OwnerGateway gateway = new OwnerGateway(directory, runtimes, scheduler);

        Fixture() {
            TopologyGeneration generation = new TopologyGeneration(1);
            directory.publish(generation, Map.of(
                source, new RegionRoute(a, generation, aRuntime.ownerEpoch()),
                destination, new RegionRoute(b, generation, bRuntime.ownerEpoch()),
                halo, new RegionRoute(b, generation, bRuntime.ownerEpoch()),
                unrelated, new RegionRoute(b, generation, bRuntime.ownerEpoch())));
        }

        RegionId owner(RoutingKey key) { return directory.resolve(key).orElseThrow().region(); }

        Map<RoutingKey, RegionId> owners() {
            Map<RoutingKey, RegionId> result = new LinkedHashMap<>();
            directory.snapshot().routes().forEach((key, route) -> result.put(key, route.region()));
            return result;
        }

        static RegionRuntime ready(RegionId id) {
            RegionRuntime runtime = new RegionRuntime(id, new OwnerEpoch(0));
            runtime.transitionTo(RegionLifecycle.READY);
            return runtime;
        }
    }
}
