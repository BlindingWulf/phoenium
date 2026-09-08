package dev.phoenium.stage4probe;

import dev.phoenium.Phoenium;
import dev.phoenium.chunk.FullRegionalEnclosure;
import dev.phoenium.core.OwnerEpoch;
import dev.phoenium.core.WorldId;
import dev.phoenium.live.PhoeniumLiveRuntime;
import dev.phoenium.ownership.ObjectOwnership;
import dev.phoenium.ownership.ObjectOwnershipRegistry;
import dev.phoenium.ownership.OwnedObjectKind;
import dev.phoenium.ownership.OwnershipContext;
import dev.phoenium.region.RegionId;
import dev.phoenium.topology.RegionDirectory;
import dev.phoenium.topology.RoutingKey;
import dev.phoenium.topology.TopologyCoordinator;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

/**
 * Test-only topology scope for the real-client fixture's server-side teleports.
 *
 * <p>{@link #prepare} must run at an owner-free server-command boundary before {@code /tp}. It
 * temporarily routes the destination's finite native view/simulation cohort to the player's
 * authentic current owner through the installed topology coordinator. The caller must then run
 * the original teleport, complete its native acknowledgement/movement, and wait for the client to
 * apply the destination FULL payload before calling {@link Prepared#completeAfterClientFull} at a
 * later owner-free command boundary. Completion verifies the native section, distance and FULL
 * prerequisites before restoring the ordinary fixture routes through the same coordinator.
 *
 * <p>The scope never publishes the directory or object registry itself. In a Stage 4 candidate,
 * the coordinator therefore invokes the installed regional-state migration for both transitions;
 * against the frozen reference it uses the same participant and object-ledger reconciliation.
 */
public final class Stage4TeleportTopology {
    private static final int CLIENT_BORDER = 1;
    private static final int MAX_ADVANCES = 64;

    private Stage4TeleportTopology() { }

    public static Prepared prepare(ServerLevel level, ServerPlayer player, BlockPos destination) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(destination, "destination");
        requireBoundary(level);
        if (player.level() != level) throw new IllegalArgumentException("teleport player belongs to another level");

        PhoeniumLiveRuntime live = Objects.requireNonNull(Phoenium.liveRuntime(), "live runtime");
        if (Boolean.getBoolean("phoenium.stage4.candidate")) {
            MinecraftServer installedServer = privateField(live, "serverInstance", MinecraftServer.class);
            if (installedServer != level.getServer())
                throw new IllegalStateException("teleport topology scope belongs to another server instance");
        }
        FullRegionalEnclosure enclosure = privateField(live, "fullEnclosure", FullRegionalEnclosure.class);
        if (enclosure == null) throw new IllegalStateException("regional enclosure is not installed");
        RegionDirectory directory = privateField(live, "directory", RegionDirectory.class);
        ObjectOwnershipRegistry objects = privateField(live, "objects", ObjectOwnershipRegistry.class);

        String objectId = player.getUUID().toString();
        ObjectOwnership binding = objects.current(objectId)
            .orElseThrow(() -> new IllegalStateException("teleport player lacks current ownership " + objectId));
        if (binding.kind() != OwnedObjectKind.PLAYER)
            throw new IllegalStateException("teleport object is not a player " + objectId);
        WorldId world = new WorldId(level.dimension().identifier().toString());
        RoutingKey source = key(world, player.chunkPosition().x(), player.chunkPosition().z());
        if (!binding.routingKey().equals(source))
            throw new IllegalStateException("teleport player routing is stale before preparation");
        var sourceRoute = directory.resolve(source)
            .orElseThrow(() -> new IllegalStateException("teleport source lacks explicit route " + source));
        if (!sourceRoute.region().equals(binding.owner()))
            throw new IllegalStateException("teleport source route differs from retained player owner");

        int radius = Math.addExact(Math.max(level.getServer().getPlayerList().getViewDistance(),
            level.getServer().getPlayerList().getSimulationDistance()), CLIENT_BORDER);
        RoutingKey target = key(world, destination.getX() >> 4, destination.getZ() >> 4);
        Set<RoutingKey> closure = closure(source, target, radius);
        CorePrepared core = prepareTopology(enclosure, directory, objects, binding, target, closure);
        System.out.println("STAGE4_TRACE_TELEPORT_TOPOLOGY_PREPARED player=" + objectId
            + " source=" + source.x() + "," + source.z()
            + " destination=" + target.x() + "," + target.z()
            + " radius=" + radius + " owner=" + binding.owner().value()
            + " generation=" + directory.snapshot().generation().value()
            + " changed=" + core.restoredOwners.size());
        Prepared prepared = new Prepared(level, player, objectId, binding.incarnation(), target, radius, core);
        // The scoped teleport helper must establish real terrain before the next native movement
        // packet can read it. Native force tickets request generation asynchronously; no owner
        // waits and no fabricated chunk/readiness is introduced. Release only tickets we added.
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            ChunkPos chunk = new ChunkPos(target.x() + dx, target.z() + dz);
            if (level.getChunkSource().updateChunkForced(chunk, true)) prepared.temporaryChunks.add(chunk);
            prepared.requests.put(chunk, ((dev.phoenium.stage4.Stage4ChunkLoadAccess) level.getChunkSource())
                .phoenium$requestChunk(chunk.x(), chunk.z(), net.minecraft.world.level.chunk.status.ChunkStatus.FULL, true));
        }
        return prepared;
    }

    public static final class Prepared {
        private final ServerLevel level;
        private final ServerPlayer player;
        private final String objectId;
        private final long incarnation;
        private final RoutingKey destination;
        private final int radius;
        private final CorePrepared core;
        private boolean completed;
        private boolean ready;
        private final Set<ChunkPos> temporaryChunks = new LinkedHashSet<>();
        private final Map<ChunkPos, java.util.concurrent.CompletableFuture<ChunkResult<net.minecraft.world.level.chunk.ChunkAccess>>>
            requests = new LinkedHashMap<>();
        private int readinessPolls;

        private Prepared(ServerLevel level, ServerPlayer player, String objectId, long incarnation,
                         RoutingKey destination, int radius, CorePrepared core) {
            this.level = level;
            this.player = player;
            this.objectId = objectId;
            this.incarnation = incarnation;
            this.destination = destination;
            this.radius = radius;
            this.core = core;
        }

        /**
         * Call only after the client harness has observed the exact destination FULL payload.
         * The server-side checks below additionally prove that the original movement handler
         * reached ChunkMap.move and installed native player demand before restoration begins.
         */
        public void completeAfterClientFull(ServerPlayer actualPlayer) {
            if (completed) throw new IllegalStateException("teleport topology scope completed twice");
            requireBoundary(level);
            if (actualPlayer != player || actualPlayer.level() != level
                    || !actualPlayer.getUUID().toString().equals(objectId))
                throw new IllegalStateException("teleport topology receipt used by another player or level");
            RoutingKey actual = key(destination.world(), actualPlayer.chunkPosition().x(), actualPlayer.chunkPosition().z());
            if (!actual.equals(destination))
                throw new IllegalStateException("player did not reach prepared teleport destination: " + actual);
            if (!actualPlayer.getLastSectionPos().equals(SectionPos.of(actualPlayer)))
                throw new IllegalStateException("native ChunkMap.move has not updated the player section");

            var chunkMap = level.getChunkSource().chunkMap;
            long packed = ChunkPos.pack(destination.x(), destination.z());
            if (chunkMap.getDistanceManager().hasPlayersNearby(packed) != net.minecraft.util.TriState.TRUE)
                throw new IllegalStateException("native DistanceManager player demand is absent at teleport destination");
            if (!chunkMap.isChunkTracked(actualPlayer, destination.x(), destination.z()))
                throw new IllegalStateException("native player tracking view has not reached teleport destination");
            ChunkHolder holder = chunkMap.getUpdatingChunkIfPresent(packed);
            if (holder == null || !successful(holder.getFullChunkFuture()))
                throw new IllegalStateException("authentic destination FULL holder is not complete");

            core.complete(objectId, incarnation, destination);
            for (ChunkPos chunk : temporaryChunks) level.getChunkSource().updateChunkForced(chunk, false);
            temporaryChunks.clear();
            requests.clear();
            completed = true;
            System.out.println("STAGE4_TRACE_TELEPORT_TOPOLOGY_COMPLETED player=" + objectId
                + " destination=" + destination.x() + "," + destination.z()
                + " radius=" + radius + " generation=" + core.directory.snapshot().generation().value());
        }

        public int destinationChunkX() { return destination.x(); }
        public int destinationChunkZ() { return destination.z(); }
        public int radius() { return radius; }

        public void observeDestinationFull() {
            if (ready) return;
            requireBoundary(level);
            readinessPolls++;
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                ChunkHolder holder = level.getChunkSource().chunkMap.getUpdatingChunkIfPresent(
                    ChunkPos.pack(destination.x() + dx, destination.z() + dz));
                if (holder == null || !successful(holder.getFullChunkFuture())) {
                    if (readinessPolls == 40 || readinessPolls == 200)
                        System.out.println("STAGE4_TRACE_TELEPORT_TERRAIN_PENDING destination=" + destination
                            + " chunk=" + (destination.x() + dx) + "," + (destination.z() + dz)
                            + " holder=" + (holder == null ? "absent" : "ticket=" + holder.getTicketLevel()
                                + " latest=" + holder.getLatestStatus() + " full=" + holder.getFullChunkFuture())
                            + " requests=" + requests);
                    return;
                }
            }
            ready = true;
            System.out.println("STAGE4_TRACE_TELEPORT_TERRAIN_READY destination="
                + destination.x() + "," + destination.z() + " chunks=9");
        }
    }

    static CorePrepared prepareTopology(FullRegionalEnclosure enclosure, RegionDirectory directory,
                                        ObjectOwnershipRegistry objects, ObjectOwnership player,
                                        RoutingKey destination, Set<RoutingKey> closure) {
        Objects.requireNonNull(enclosure, "enclosure");
        Objects.requireNonNull(directory, "directory");
        Objects.requireNonNull(objects, "objects");
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(destination, "destination");
        if (OwnershipContext.current().isPresent())
            throw new IllegalStateException("teleport topology prepared inside regional ownership");
        if (player.state() != ObjectOwnership.State.OWNED)
            throw new IllegalStateException("teleport player ownership is not live");
        if (!closure.contains(player.routingKey()) || !closure.contains(destination))
            throw new IllegalArgumentException("teleport closure omits source or destination");

        TopologyCoordinator coordinator = privateField(enclosure, "coordinator", TopologyCoordinator.class);
        requireIdle(coordinator);
        RegionDirectory.View before = directory.snapshot();
        Map<RoutingKey, RegionId> restored = new LinkedHashMap<>();
        Set<RegionId> participants = new LinkedHashSet<>();
        participants.add(player.owner());
        for (RoutingKey key : new TreeSet<>(closure)) {
            if (!key.world().equals(player.routingKey().world()))
                throw new IllegalArgumentException("teleport closure crosses world identity");
            if (directory.pendingChange(key).isPresent())
                throw new IllegalStateException("teleport closure overlaps a pending topology change");
            var route = directory.resolve(key)
                .orElseThrow(() -> new IllegalStateException("teleport closure lacks explicit route " + key));
            if (!route.region().equals(player.owner())) {
                restored.put(key, route.region());
                participants.add(route.region());
            }
        }

        if (!restored.isEmpty()) {
            Map<RoutingKey, RegionId> owners = owners(before);
            restored.keySet().forEach(key -> owners.put(key, player.owner()));
            coordinator.request(new TopologyCoordinator.Transition(participants, owners));
            drive(enclosure, coordinator);
        }
        ObjectOwnership current = requireIncarnation(objects, player.objectId(), player.incarnation());
        if (!current.routingKey().equals(player.routingKey()) || !current.owner().equals(player.owner()))
            throw new IllegalStateException("teleport preparation changed source player identity");
        for (RoutingKey key : closure) {
            var route = directory.resolve(key).orElseThrow();
            if (!route.region().equals(player.owner()))
                throw new IllegalStateException("teleport closure did not converge to current player owner");
        }
        return new CorePrepared(enclosure, coordinator, directory, objects, player.owner(),
            player.objectId(), player.incarnation(), player.objectEpoch(), destination,
            Set.copyOf(closure), Map.copyOf(restored));
    }

    static final class CorePrepared {
        private final FullRegionalEnclosure enclosure;
        private final TopologyCoordinator coordinator;
        private final RegionDirectory directory;
        private final ObjectOwnershipRegistry objects;
        private final RegionId temporaryOwner;
        private final String playerId;
        private final long incarnation;
        private final OwnerEpoch objectEpoch;
        private final RoutingKey destination;
        private final Set<RoutingKey> preparedKeys;
        private final Map<RoutingKey, RegionId> restoredOwners;
        private boolean completed;

        CorePrepared(FullRegionalEnclosure enclosure, TopologyCoordinator coordinator,
                     RegionDirectory directory, ObjectOwnershipRegistry objects,
                     RegionId temporaryOwner, String playerId, long incarnation,
                     OwnerEpoch objectEpoch, RoutingKey destination, Set<RoutingKey> preparedKeys,
                     Map<RoutingKey, RegionId> restoredOwners) {
            this.enclosure = enclosure;
            this.coordinator = coordinator;
            this.directory = directory;
            this.objects = objects;
            this.temporaryOwner = temporaryOwner;
            this.playerId = playerId;
            this.incarnation = incarnation;
            this.objectEpoch = objectEpoch;
            this.destination = destination;
            this.preparedKeys = preparedKeys;
            this.restoredOwners = restoredOwners;
        }

        void complete(String expectedPlayerId, long expectedIncarnation, RoutingKey actualDestination) {
            if (completed) throw new IllegalStateException("teleport topology core receipt completed twice");
            if (!playerId.equals(expectedPlayerId) || incarnation != expectedIncarnation
                    || !destination.equals(actualDestination))
                throw new IllegalStateException("teleport topology receipt identity changed");
            if (OwnershipContext.current().isPresent())
                throw new IllegalStateException("teleport topology restored inside regional ownership");
            requireIdle(coordinator);
            ObjectOwnership player = requireIncarnation(objects, playerId, incarnation);
            if (!player.routingKey().equals(destination) || !player.owner().equals(temporaryOwner))
                throw new IllegalStateException("teleport completion lacks the prepared player route and owner");
            if (!player.objectEpoch().equals(objectEpoch))
                throw new IllegalStateException("teleport player object epoch changed before restoration");
            for (RoutingKey key : preparedKeys) {
                var route = directory.resolve(key)
                    .orElseThrow(() -> new IllegalStateException("prepared teleport route disappeared " + key));
                if (!route.region().equals(temporaryOwner))
                    throw new IllegalStateException("prepared teleport closure changed before restoration " + key);
            }

            if (!restoredOwners.isEmpty()) {
                RegionDirectory.View current = directory.snapshot();
                Set<RegionId> participants = new LinkedHashSet<>();
                participants.add(temporaryOwner);
                for (var entry : restoredOwners.entrySet()) {
                    participants.add(entry.getValue());
                }
                // Start from the current view so unrelated worlds and routes admitted after prepare
                // remain present. Changed keys are transferred, with their regional state, back to
                // the exact ordinary fixture owners captured at preparation.
                Map<RoutingKey, RegionId> owners = owners(current);
                restoredOwners.forEach(owners::put);
                coordinator.request(new TopologyCoordinator.Transition(participants, owners));
                drive(enclosure, coordinator);
            }
            ObjectOwnership restoredPlayer = requireIncarnation(objects, playerId, incarnation);
            RegionId destinationOwner = directory.resolve(destination).orElseThrow().region();
            if (!restoredPlayer.routingKey().equals(destination) || !restoredPlayer.owner().equals(destinationOwner))
                throw new IllegalStateException("teleport player was not reconciled to restored destination owner");
            for (var entry : restoredOwners.entrySet())
                if (!directory.resolve(entry.getKey()).orElseThrow().region().equals(entry.getValue()))
                    throw new IllegalStateException("ordinary fixture route was not restored " + entry.getKey());
            completed = true;
        }
    }

    static Set<RoutingKey> closure(RoutingKey source, RoutingKey destination, int radius) {
        Set<RoutingKey> keys = new TreeSet<>();
        keys.add(source);
        for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++)
            keys.add(key(destination.world(), Math.addExact(destination.x(), dx), Math.addExact(destination.z(), dz)));
        return Set.copyOf(keys);
    }

    private static RoutingKey key(WorldId world, int x, int z) { return new RoutingKey(world, x, z); }

    private static Map<RoutingKey, RegionId> owners(RegionDirectory.View view) {
        Map<RoutingKey, RegionId> owners = new LinkedHashMap<>();
        view.routes().forEach((key, route) -> owners.put(key, route.region()));
        return owners;
    }

    private static void drive(FullRegionalEnclosure enclosure, TopologyCoordinator coordinator) {
        for (int attempt = 0; attempt < MAX_ADVANCES
                && (coordinator.hasPendingTransition() || coordinator.hasPendingConvergence()); attempt++)
            enclosure.advance();
        if (coordinator.hasPendingTransition() || coordinator.hasPendingConvergence())
            throw new IllegalStateException("teleport topology transition did not reach its owner-free boundary");
    }

    private static void requireIdle(TopologyCoordinator coordinator) {
        if (coordinator.hasPendingTransition() || coordinator.hasPendingConvergence())
            throw new IllegalStateException("teleport topology overlaps an existing transition");
    }

    private static ObjectOwnership requireIncarnation(ObjectOwnershipRegistry objects, String playerId, long incarnation) {
        ObjectOwnership current = objects.current(playerId)
            .orElseThrow(() -> new IllegalStateException("teleport player ownership is no longer live"));
        if (current.incarnation() != incarnation)
            throw new IllegalStateException("teleport player incarnation changed");
        return current;
    }

    private static boolean successful(java.util.concurrent.CompletableFuture<? extends ChunkResult<?>> future) {
        if (future == null || !future.isDone() || future.isCompletedExceptionally() || future.isCancelled()) return false;
        ChunkResult<?> result = future.getNow(null);
        return result != null && result.isSuccess() && result.orElse(null) != null;
    }

    private static void requireBoundary(ServerLevel level) {
        if (!level.getServer().isSameThread())
            throw new IllegalStateException("teleport topology scope must run on the server thread");
        if (OwnershipContext.current().isPresent())
            throw new IllegalStateException("teleport topology scope must run owner-free");
    }

    private static <T> T privateField(Object target, String name, Class<T> type) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(target));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("installed runtime lacks test topology field " + name, failure);
        }
    }
}
