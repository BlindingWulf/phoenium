package dev.phoenium.chunk;

import dev.phoenium.core.*;
import dev.phoenium.ownership.*;
import dev.phoenium.region.RegionId;
import dev.phoenium.topology.*;
import java.util.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.level.*;
import net.minecraft.world.level.storage.PrimaryLevelData;
import static dev.phoenium.chunk.FullDecisionWitnessSupport.*;

/** Exact claim/service operation with controlled world endpoints; no FULL readiness is invented. */
final class FullServiceOperationFixture implements AutoCloseable {
    final MinecraftServer server;
    final FullFinishingServices service;
    final List<ServerLevel> worlds = new ArrayList<>();
    final Object holder = new Object();
    final ChunkCustodyModel.StageId stage;
    final RegionId region;
    final RegionLeaseManager leases;
    FullServiceOperationFixture() throws Exception { this(stub(MinecraftServer.class, List.of())); }
    FullServiceOperationFixture(MinecraftServer server) {
        this.server = server; service = FullFinishingServices.forServer(server);
        WorldId world = new WorldId("service-source");
        var life = new ChunkCustodyModel.LifecycleId("service-source", 1, world, new RoutingKey(world, 0, 0), 1);
        stage = new ChunkCustodyModel.StageId(new ChunkCustodyModel.WorkId(life, 1), ChunkStageKind.FULL_FINISH, "FULL", 0);
        region = new RegionId(world, "source"); leases = new RegionLeaseManager(region);
    }
    ServerLevel world() throws Exception {
        ServerLevel world = stub(ServerLevel.class, List.of(new Answer(ServerLevel.class.getMethod("getServer"), args -> server)));
        var data = new PrimaryLevelData(new LevelSettings("service", GameType.SURVIVAL,
                LevelSettings.DifficultySettings.DEFAULT, true, WorldDataConfiguration.DEFAULT),
                PrimaryLevelData.SpecialWorldProperty.NONE, com.mojang.serialization.Lifecycle.stable());
        set(world, Level.class, "levelData", data); set(world, ServerLevel.class, "serverLevelData", data);
        set(world, Level.class, "dimension", Level.OVERWORLD);
        set(world, ServerLevel.class, "environmentAttributes", EnvironmentAttributeSystem.builder().build());
        worlds.add(world); service.registerWorld(world); return world;
    }
    interface Body { void run(ChunkStageClaim claim) throws Exception; }
    void full(Body body) throws Exception {
        var reservation = service.tryReserve(stage, holder, worlds, 12);
        org.junit.jupiter.api.Assertions.assertNotNull(reservation);
        try (reservation; var lease = leases.acquire();
             var owner = OwnershipContext.enter(region, lease.epoch(), OperationCategory.OWNER_TASK);
             var claim = new ChunkStageClaim(new ChunkCustodyModel(), stage, new RegionRoute(region, new TopologyGeneration(1), new OwnerEpoch(0)), lease, null);
             var turn = reservation.enter(claim)) { body.run(claim); }
    }
    public void close() { FullFinishingServices.stopped(server); }
}
