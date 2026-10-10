package dev.phoenium.service;

import dev.phoenium.chunk.FullFinishingServices;
import dev.phoenium.core.WorldId;
import dev.phoenium.mixin.RegionalLevelTicksAccess;
import dev.phoenium.mixin.RegionalChunkMapAccess;
import dev.phoenium.mixin.RegionalServerLevelAccess;
import dev.phoenium.ownership.ObjectOwnershipRegistry;
import dev.phoenium.topology.RegionDirectory;
import dev.phoenium.topology.RegionalStateMigration;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.LevelChunkTicks;
import net.minecraft.world.ticks.LevelTicks;
import net.minecraft.world.ticks.ScheduledTick;

/** Connected per-server registry for actual Minecraft scheduled ticks and block events. */
public final class RegionalWorldServices implements RegionalStateMigration {
    private final RegionDirectory directory;
    private final RegionalServiceDispatcher dispatcher;
    private final ObjectOwnershipRegistry objects;
    private final Map<ServerLevel, WorldServices> worlds = new IdentityHashMap<>();
    private final Map<LevelTicks<?>, TickBinding<?>> ticks = new IdentityHashMap<>();
    private final java.util.Set<ServerLevel> bindings = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    private volatile boolean terminated;

    public RegionalWorldServices(RegionDirectory directory, RegionalServiceDispatcher dispatcher,
                                 ObjectOwnershipRegistry objects) {
        this.directory = Objects.requireNonNull(directory, "directory");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
        this.objects = Objects.requireNonNull(objects, "objects");
    }

    @SuppressWarnings("unchecked")
    public void bind(ServerLevel level) {
        synchronized (this) {
            requireActiveLifetime();
            Objects.requireNonNull(level, "level");
            if (worlds.containsKey(level)) return;
            if (!bindings.add(level)) throw new IllegalStateException("regional world binding already in progress");
        }
        LevelTicks<Block> blockTicks = null;
        LevelTicks<Fluid> fluidTicks = null;
        WorldServices services;
        try {
            if (Thread.holdsLock(this))
                throw new IllegalStateException("regional world callbacks cannot run under the binding monitor");
            WorldId world = WorldId.of(level.dimension().identifier().toString());
            blockTicks = level.getBlockTicks();
            fluidTicks = level.getFluidTicks();
            var blockAccess = (RegionalLevelTicksAccess<Block>) blockTicks;
            var fluidAccess = (RegionalLevelTicksAccess<Fluid>) fluidTicks;
            var blocks = new RegionalScheduledTickService<Block>(world, directory, dispatcher,
                blockAccess.phoenium$getTickCheck(), true, this);
            var fluids = new RegionalScheduledTickService<Fluid>(world, directory, dispatcher,
                fluidAccess.phoenium$getTickCheck(), false, this);
            blocks.bindOriginalCycle(blockAccess.phoenium$getNextTickForContainer(), blockAccess.phoenium$getToRunThisTick(),
                blockAccess.phoenium$getAlreadyRunThisTick(), blockAccess.phoenium$getToRunThisTickSet());
            fluids.bindOriginalCycle(fluidAccess.phoenium$getNextTickForContainer(), fluidAccess.phoenium$getToRunThisTick(),
                fluidAccess.phoenium$getAlreadyRunThisTick(), fluidAccess.phoenium$getToRunThisTickSet());
            blocks.bootstrap(new LinkedHashMap<>(blockAccess.phoenium$getAllContainers()));
            fluids.bootstrap(new LinkedHashMap<>(fluidAccess.phoenium$getAllContainers()));
            var events = new RegionalBlockEventService<BlockEventData>(world, directory, dispatcher, BlockEventData::pos);
            var originalEvents = ((RegionalServerLevelAccess) level).phoenium$getBlockEvents();
            events.bootstrap(List.copyOf(originalEvents));
            originalEvents.clear();
            var tracking = new RegionalTrackingService(world, level, directory, dispatcher, objects);
            ChunkMap chunkMap = level.getChunkSource().chunkMap;
            tracking.reconcileSource(((RegionalChunkMapAccess) chunkMap).phoenium$getTrackedEntities());
            var gameplay = new RegionalChunkGameplayService(world, level, level.getChunkSource(), directory, dispatcher, objects);
            services = new WorldServices(level, blocks, fluids, events, tracking, gameplay, chunkMap);
        } catch (Throwable failure) {
            synchronized (this) { bindings.remove(level); }
            throw failure;
        }
        synchronized (this) {
            if (!bindings.remove(level)) throw new IllegalStateException("regional world binding reservation lost");
            requireActiveLifetime();
            if (worlds.putIfAbsent(level, services) != null)
                throw new IllegalStateException("regional world published during binding");
            ticks.put(blockTicks, new TickBinding<>(services, services.blockTicks()));
            ticks.put(fluidTicks, new TickBinding<>(services, services.fluidTicks()));
        }
    }

    public synchronized RegionalServiceDispatcher dispatcher() { return dispatcher; }

    /** An ended binding is not the pre-start vanilla bootstrap path. */
    public void requireActiveLifetime() {
        if (terminated) throw new IllegalStateException("regional world service requested after shutdown");
    }

    public void runChunkGameplay(ServerChunkCache cache) { gameplay(cache).runOriginal(); }
    public net.minecraft.world.level.NaturalSpawner.SpawnState createSpawnState(ServerChunkCache cache, int count,
            Iterable<net.minecraft.world.entity.Entity> entities, net.minecraft.world.level.NaturalSpawner.ChunkGetter chunks,
            RegionalChunkGameplayService.SpawnFactory factory) {
        return gameplay(cache).createSpawnState(count, entities, chunks, factory);
    }
    public void collectSpawningChunks(ServerChunkCache cache, ChunkMap map,
            List<net.minecraft.world.level.chunk.LevelChunk> output) {
        gameplay(cache).collectSpawningChunks(map, output);
    }
    public void spawningChunkCallback(ServerChunkCache cache, net.minecraft.world.level.chunk.LevelChunk chunk,
            boolean canSpawn, java.util.function.Consumer<net.minecraft.world.level.chunk.LevelChunk> ticker) {
        gameplay(cache).spawningChunkCallback(chunk, canSpawn, ticker);
    }
    public void broadcastChunkCallback(ServerChunkCache cache, net.minecraft.world.level.chunk.LevelChunk chunk, Runnable original) {
        gameplay(cache).broadcastChunkCallback(chunk, original);
    }
    public void customSpawnerCallback(ServerLevel level, net.minecraft.world.level.CustomSpawner spawner,
            boolean spawnEnemies, Runnable original) {
        gameplay(level.getChunkSource()).customSpawnerCallback(spawner, spawnEnemies, original);
    }
    public void forEachBlockTickingChunk(ServerChunkCache cache, ChunkMap map,
            java.util.function.Consumer<net.minecraft.world.level.chunk.LevelChunk> output) {
        gameplay(cache).forEachBlockTickingChunk(map, output);
    }
    public void runEntityTicks(ServerLevel level, net.minecraft.world.level.entity.EntityTickList entities,
            java.util.function.Consumer<net.minecraft.world.entity.Entity> output) {
        WorldServices services;
        synchronized (this) { services = worlds.get(level); }
        if (services == null) throw new IllegalStateException("unbound regional entity tick world");
        services.gameplay().entityTicks(entities, output);
    }
    public void runBlockEntities(ServerLevel level) {
        WorldServices services;
        synchronized (this) { services = worlds.get(level); }
        if (services == null) throw new IllegalStateException("unbound regional block entity world");
        services.gameplay().tickBlockEntities();
    }
    public void runDragonFight(ServerLevel level,
            net.minecraft.world.level.dimension.end.EnderDragonFight fight) {
        WorldServices services;
        synchronized (this) { services = worlds.get(level); }
        if (services == null) throw new IllegalStateException("unbound regional dragon-fight world");
        services.gameplay().tickDragonFight(fight);
    }
    public void blockEntityTickerAdded(ServerLevel level, net.minecraft.world.level.block.entity.TickingBlockEntity ticker) {
        WorldServices services;
        synchronized (this) { services = worlds.get(level); }
        if (services != null) services.gameplay().tickerAdded(ticker);
    }
    public net.minecraft.world.entity.player.Player nearestNaturalSpawnPlayer(ServerLevel level, double x, double y, double z) {
        return gameplay(level.getChunkSource()).nearestNaturalSpawnPlayer(x, y, z);
    }
    public double naturalSpawnPlayerDistance(ServerLevel level, net.minecraft.world.entity.player.Player player,
            double x, double y, double z) {
        return gameplay(level.getChunkSource()).naturalSpawnPlayerDistance(player, x, y, z);
    }
    public boolean activeNaturalSpawn(ServerLevel level) {
        WorldServices services;
        synchronized (this) { services = worlds.get(level); }
        return services != null && services.gameplay().active();
    }
    public boolean retainedSpawningWeather(ServerLevel level, boolean thunder) {
        return gameplay(level.getChunkSource()).retainedWeather(thunder);
    }
    public boolean activeSpawningWeather(ServerLevel level) {
        WorldServices services;
        synchronized (this) { services = worlds.get(level); }
        return services != null && services.gameplay().activeWeather();
    }
    private RegionalChunkGameplayService gameplay(ServerChunkCache cache) {
        WorldServices services;
        synchronized (this) { services = worlds.get(((dev.phoenium.mixin.RegionalServerChunkCacheAccess) cache).phoenium$getLevel()); }
        if (services == null || services.level().getChunkSource() != cache)
            throw new IllegalStateException("unbound regional chunk gameplay cache");
        return services.gameplay();
    }

    public void initializeTracking() {
        List<WorldServices> snapshot;
        synchronized (this) { snapshot = List.copyOf(worlds.values()); }
        for (WorldServices services : snapshot) {
            FullFinishingServices.requireWorld(services.level(), FullFinishingServices.Kind.TRACKER);
            FullFinishingServices.requireServer(services.level().getServer(), FullFinishingServices.Kind.CONNECTIONS);
            services.tracking().initialize();
        }
    }

    @SuppressWarnings("unchecked")
    public <T> void containerAdded(LevelTicks<T> owner, ChunkPos pos, LevelChunkTicks<T> container) {
        TickBinding<T> binding;
        synchronized (this) { binding = (TickBinding<T>) ticks.get(owner); }
        if (binding != null) binding.service().addContainer(pos.pack(), container);
    }
    @SuppressWarnings("unchecked")
    public <T> void containerRemoved(LevelTicks<T> owner, ChunkPos pos, LevelChunkTicks<T> container) {
        TickBinding<T> binding;
        synchronized (this) { binding = (TickBinding<T>) ticks.get(owner); }
        if (binding != null) binding.service().removeContainer(pos.pack(), container);
    }
    @SuppressWarnings("unchecked")
    public <T> boolean schedule(LevelTicks<T> owner, ScheduledTick<T> tick) {
        TickBinding<T> binding;
        synchronized (this) { binding = (TickBinding<T>) ticks.get(owner); }
        if (binding == null) return false;
        FullFinishingServices.requireWorld(binding.world().level(), FullFinishingServices.Kind.TICK_LIST);
        long chunkKey = ChunkPos.pack(tick.pos());
        LevelChunkTicks<T> container = ((RegionalLevelTicksAccess<T>) owner).phoenium$getAllContainers().get(chunkKey);
        if (container == null) return false;
        if (dev.phoenium.ownership.OwnershipContext.current().isPresent()) {
            binding.service().requireScheduleOwner(chunkKey, container);
            return false;
        }
        var key = new dev.phoenium.topology.RoutingKey(binding.service().world(),
            ChunkPos.getX(chunkKey), ChunkPos.getZ(chunkKey));
        dispatcher.ensureClosure(key, 1);
        dispatcher.executeAndDrain(key, binding.service().producerKind(), ignored -> {
            binding.service().requireScheduleOwner(chunkKey, container);
            owner.schedule(tick);
        });
        return true;
    }
    @SuppressWarnings("unchecked")
    public <T> boolean mutateTickArea(LevelTicks<T> owner, LevelTicks<T> source,
            net.minecraft.world.level.levelgen.structure.BoundingBox area, net.minecraft.core.Vec3i offset, Runnable original) {
        TickBinding<T> destinationBinding, sourceBinding;
        synchronized (this) {
            destinationBinding = (TickBinding<T>) ticks.get(owner);
            sourceBinding = (TickBinding<T>) ticks.get(source);
        }
        if (destinationBinding == null && sourceBinding == null) return false;
        if (destinationBinding == null || sourceBinding == null)
            throw new IllegalStateException("tick area operation crosses bound world lifetime");
        FullFinishingServices.requireWorld(destinationBinding.world().level(), FullFinishingServices.Kind.TICK_LIST);
        FullFinishingServices.requireWorld(sourceBinding.world().level(), FullFinishingServices.Kind.TICK_LIST);
        if (owner != source && !destinationBinding.service().world().equals(sourceBinding.service().world())
                && dev.phoenium.ownership.OwnershipContext.current().isEmpty()) {
            copyAreaAcrossWorlds(owner, sourceBinding.service(), area, offset);
            return true;
        }
        destinationBinding.service().mutateArea(sourceBinding.service(), area, offset, original);
        return true;
    }

    /**
     * /clone between dimensions: LevelTicks.copyAreaFrom reads one world's tick list and schedules into
     * another's, which no single owner closure covers. Owner-free, it is split at the world boundary: an
     * owner-free snapshot under the source world's closure, then each copied tick goes through the
     * destination's ordinary owner-free LevelTicks.schedule route. Under an owner the regional
     * mutateArea refusal stands (command confinement keeps in-region commands out of other worlds).
     */
    private <T> void copyAreaAcrossWorlds(LevelTicks<T> destination, RegionalScheduledTickService<T> source,
            net.minecraft.world.level.levelgen.structure.BoundingBox area, net.minecraft.core.Vec3i offset) {
        List<ScheduledTick<T>> found = source.snapshotArea(area);
        for (ScheduledTick<T> tick : RegionalScheduledTickService.copiedTicks(found, offset)) destination.schedule(tick);
    }

    @SuppressWarnings("unchecked")
    public <T> void scheduled(LevelTicks<T> owner, long chunkKey, LevelChunkTicks<T> container) {
        TickBinding<T> binding;
        synchronized (this) { binding = (TickBinding<T>) ticks.get(owner); }
        if (binding != null) binding.service().scheduled(chunkKey, container);
    }
    @SuppressWarnings("unchecked")
    public synchronized <T> Boolean willTickThisTick(LevelTicks<T> owner, BlockPos pos, T type) {
        TickBinding<T> binding = (TickBinding<T>) ticks.get(owner);
        if (binding == null) return null;
        FullFinishingServices.requireWorld(binding.world().level(), FullFinishingServices.Kind.TICK_LIST);
        return binding.service().willTickThisTick(pos, type);
    }
    @SuppressWarnings("unchecked")
    public <T> boolean runScheduled(LevelTicks<T> owner, long currentTick, int maxTicks, BiConsumer<BlockPos, T> output) {
        TickBinding<T> binding;
        synchronized (this) { binding = (TickBinding<T>) ticks.get(owner); }
        if (binding == null) return false;
        FullFinishingServices.requireWorld(binding.world().level(), FullFinishingServices.Kind.TICK_LIST);
        binding.service().tick(currentTick, maxTicks, output);
        return true;
    }

    public boolean enqueueBlockEvent(ServerLevel level, BlockEventData event) {
        WorldServices services;
        synchronized (this) { services = worlds.get(level); }
        if (services == null) return false;
        FullFinishingServices.requireWorld(level, FullFinishingServices.Kind.TICK_LIST);
        if (dev.phoenium.ownership.OwnershipContext.current().isPresent()) services.events().enqueue(event);
        else {
            var key = new dev.phoenium.topology.RoutingKey(services.blockTicks().world(),
                event.pos().getX() >> 4, event.pos().getZ() >> 4);
            dispatcher.ensureClosure(key, 1);
            dispatcher.executeAndDrain(key, RegionalServiceKind.BLOCK_EVENT,
                ignored -> services.events().enqueue(event));
        }
        return true;
    }

    public boolean runBlockEvents(ServerLevel level) {
        WorldServices services;
        synchronized (this) { services = worlds.get(level); }
        if (services == null) return false;
        FullFinishingServices.requireWorld(level, FullFinishingServices.Kind.TICK_LIST);
        services.events().runCycle(event -> level.shouldTickBlocksAt(event.pos()), event -> {
            if (((RegionalServerLevelAccess) level).phoenium$invokeDoBlockEvent(event)) {
                FullFinishingServices.requireServer(level.getServer(), FullFinishingServices.Kind.CONNECTIONS);
                try (var recipients = RegionalBlockEventBroadcast.open(level)) {
                    level.getServer().getPlayerList().broadcast(null, event.pos().getX(), event.pos().getY(),
                        event.pos().getZ(), 64.0, level.dimension(),
                        new ClientboundBlockEventPacket(event.pos(), event.block(), event.paramA(), event.paramB()));
                }
            }
        });
        return true;
    }

    public boolean runTracking(ChunkMap map) {
        RegionalChunkMapAccess access = (RegionalChunkMapAccess) map;
        ServerLevel level = access.phoenium$getLevel();
        WorldServices services;
        synchronized (this) { services = worlds.get(level); }
        if (services == null) return false;
        if (services.chunkMap() != map) throw new IllegalStateException("regional tracker ChunkMap identity changed");
        FullFinishingServices.requireWorld(level, FullFinishingServices.Kind.TRACKER);
        FullFinishingServices.requireServer(level.getServer(), FullFinishingServices.Kind.CONNECTIONS);
        dispatcher.drainBeforeSelection();
        List<net.minecraft.server.level.ServerPlayer> active = List.copyOf(access.phoenium$getPlayerMap().getAllPlayers());
        services.tracking().tick(map, active, List.copyOf(level.players()));
        return true;
    }

    /** Native section membership is registry metadata. Original save predicates, serialization,
     * and removal callbacks run only after all selected aliases/passengers have current authority. */
    public boolean storeEntitySections(ServerLevel level, long chunkKey,
            java.util.function.Supplier<List<net.minecraft.world.entity.Entity>> selection,
            java.util.function.BooleanSupplier original) {
        requireActiveLifetime();
        FullFinishingServices finishing = FullFinishingServices.forServer(level.getServer());
        FullFinishingServices.requireWorld(level, FullFinishingServices.Kind.ENTITY_REGISTRY);
        // As in ChunkMap.addEntity/removeEntity: older accepted owner work (a queued movement of a
        // selected entity) settles before the section selection, not inside this save's drain after it.
        if (dev.phoenium.ownership.OwnershipContext.current().isEmpty()) dispatcher.drainBeforeSelection();
        List<net.minecraft.world.entity.Entity> aliases = List.copyOf(selection.get());
        var sources = new java.util.ArrayList<dev.phoenium.chunk.FullEntityFacts.Candidate>();
        var relations = new java.util.ArrayList<List<dev.phoenium.chunk.FullEntityFacts.Candidate>>();
        WorldId world = WorldId.of(level.dimension().identifier().toString());
        var chunk = net.minecraft.world.level.ChunkPos.unpack(chunkKey);
        var key = new dev.phoenium.topology.RoutingKey(world, chunk.x(), chunk.z());
        var footprint = new java.util.LinkedHashSet<dev.phoenium.topology.RoutingKey>();
        footprint.add(key);
        for (var alias : aliases) {
            var source = finishing.entityFacts.trackingCandidate(level, alias);
            sources.add(source);
            footprint.add(new dev.phoenium.topology.RoutingKey(world, source.position().x(), source.position().z()));
            var passengers = finishing.entityFacts.trackingPassengers(source);
            relations.add(passengers);
            for (var passenger : passengers)
                footprint.add(new dev.phoenium.topology.RoutingKey(world, passenger.position().x(), passenger.position().z()));
        }
        if (dev.phoenium.ownership.OwnershipContext.current().isPresent()) dispatcher.requireCurrentClosure(footprint);
        else dispatcher.ensureClosure(footprint);
        java.util.function.BooleanSupplier body = () -> {
            dispatcher.requireCurrentClosure(footprint);
            if (!aliases.equals(selection.get())) throw new IllegalStateException("entity storage section membership changed");
            for (int index = 0; index < sources.size(); index++)
                if (!finishing.entityFacts.validTrackingCandidate(sources.get(index))
                        || !finishing.entityFacts.validTrackingPassengers(sources.get(index), relations.get(index)))
                    throw new IllegalStateException("entity storage lost its native source identity or passenger relation");
            return original.getAsBoolean();
        };
        if (dev.phoenium.ownership.OwnershipContext.current().isPresent()) return body.getAsBoolean();
        boolean[] result = new boolean[1];
        dispatcher.executeAndDrain(key, RegionalServiceKind.ENTITY_STORAGE_SAVE,
            execution -> result[0] = body.getAsBoolean());
        return result[0];
    }

    public boolean addTrackingEntity(ChunkMap map, net.minecraft.world.entity.Entity entity, Runnable original) {
        RegionalChunkMapAccess access = (RegionalChunkMapAccess) map;
        WorldServices services;
        synchronized (this) { services = worlds.get(access.phoenium$getLevel()); }
        if (services == null) return false;
        if (services.chunkMap() != map) throw new IllegalStateException("regional tracker ChunkMap identity changed");
        FullFinishingServices.requireWorld(services.level(), FullFinishingServices.Kind.TRACKER);
        FullFinishingServices.requireServer(services.level().getServer(), FullFinishingServices.Kind.CONNECTIONS);
        services.tracking().addEntity(map, entity, original);
        return true;
    }

    public boolean addTrackingPlayers(ChunkMap map, Object tracker, List<net.minecraft.server.level.ServerPlayer> players) {
        RegionalChunkMapAccess access = (RegionalChunkMapAccess) map;
        WorldServices services;
        synchronized (this) { services = worlds.get(access.phoenium$getLevel()); }
        if (services == null) return false;
        services.tracking().addPlayers(tracker, players);
        return true;
    }

    public boolean addTrackingPlayer(ChunkMap map, Object tracker,
                                     net.minecraft.server.level.ServerPlayer player, Runnable original) {
        RegionalChunkMapAccess access = (RegionalChunkMapAccess) map;
        WorldServices services;
        synchronized (this) { services = worlds.get(access.phoenium$getLevel()); }
        if (services == null) return false;
        services.tracking().addPlayer(tracker, player, original);
        return true;
    }

    public boolean removeTrackingEntity(ChunkMap map, net.minecraft.world.entity.Entity entity, Runnable original) {
        RegionalChunkMapAccess access = (RegionalChunkMapAccess) map;
        WorldServices services;
        synchronized (this) { services = worlds.get(access.phoenium$getLevel()); }
        if (services == null) return false;
        FullFinishingServices.requireWorld(services.level(), FullFinishingServices.Kind.TRACKER);
        FullFinishingServices.requireServer(services.level().getServer(), FullFinishingServices.Kind.CONNECTIONS);
        services.tracking().removeEntity(map, entity, original);
        return true;
    }

    public boolean removeTrackingPlayer(ChunkMap map, Object tracker,
                                        net.minecraft.server.level.ServerPlayer player, Runnable original) {
        RegionalChunkMapAccess access = (RegionalChunkMapAccess) map;
        WorldServices services;
        synchronized (this) { services = worlds.get(access.phoenium$getLevel()); }
        if (services == null) return false;
        services.tracking().removePlayer(tracker, player, original);
        return true;
    }

    public dev.phoenium.topology.LiveMovementFootprint prepareMovementTracking(
            net.minecraft.server.level.ServerPlayer player, dev.phoenium.topology.LiveMovementFootprint movement) {
        WorldServices services;
        synchronized (this) { services = worlds.get(player.level()); }
        if (services == null) throw new IllegalStateException("movement tracking world is not bound");
        var finishing = FullFinishingServices.forServer(services.level().getServer());
        try (var access = finishing.referenceAccess()) {
            FullFinishingServices.requireWorld(services.level(), FullFinishingServices.Kind.TRACKER);
            FullFinishingServices.requireServer(services.level().getServer(), FullFinishingServices.Kind.CONNECTIONS);
            return services.tracking().prepareMovement(player, movement);
        }
    }

    public boolean moveTrackingPlayer(ChunkMap map, net.minecraft.server.level.ServerPlayer player, Runnable original) {
        RegionalChunkMapAccess access = (RegionalChunkMapAccess) map;
        WorldServices services;
        synchronized (this) { services = worlds.get(access.phoenium$getLevel()); }
        if (services == null) return false;
        FullFinishingServices.requireWorld(services.level(), FullFinishingServices.Kind.TRACKER);
        FullFinishingServices.requireServer(services.level().getServer(), FullFinishingServices.Kind.CONNECTIONS);
        services.tracking().movePlayer(map, player, original);
        return true;
    }

    public boolean movedTrackingPlayers(ChunkMap map, Object tracker, List<net.minecraft.server.level.ServerPlayer> players) {
        RegionalChunkMapAccess access = (RegionalChunkMapAccess) map;
        WorldServices services;
        synchronized (this) { services = worlds.get(access.phoenium$getLevel()); }
        if (services == null) return false;
        services.tracking().movedPlayerUpdates(tracker, players);
        return true;
    }

    public boolean movedTrackingPlayer(ChunkMap map, Object tracker,
                                       net.minecraft.server.level.ServerPlayer player, Runnable original) {
        RegionalChunkMapAccess access = (RegionalChunkMapAccess) map;
        // S9-D14: a selected tracker's callback inside a regional move: the moving player's plan names this level's
        // tracking service, so the world lookup (a monitor and a map probe per selected tracker) is skipped.
        RegionalTrackingService moving = RegionalTrackingService.movingService(player, access.phoenium$getLevel());
        if (moving != null) { moving.movedPlayerUpdate(tracker, player, original); return true; }
        WorldServices services;
        synchronized (this) { services = worlds.get(access.phoenium$getLevel()); }
        if (services == null) return false;
        services.tracking().movedPlayerUpdate(tracker, player, original);
        return true;
    }

    /** S9-D14: admission for ChunkMap.move's original updatePlayer; true where no regional tracking serves the map. */
    public boolean admitMovedTrackingPlayer(ChunkMap map, Object tracker, net.minecraft.server.level.ServerPlayer player) {
        RegionalChunkMapAccess access = (RegionalChunkMapAccess) map;
        RegionalTrackingService moving = RegionalTrackingService.movingService(player, access.phoenium$getLevel());
        if (moving != null) return moving.admitMovedPlayer(tracker, player);
        WorldServices services;
        synchronized (this) { services = worlds.get(access.phoenium$getLevel()); }
        return services == null || services.tracking().admitMovedPlayer(tracker, player);
    }

    public void trackingSourceAdded(ChunkMap map, net.minecraft.world.entity.Entity entity) {
        RegionalChunkMapAccess access = (RegionalChunkMapAccess) map;
        WorldServices services;
        synchronized (this) { services = worlds.get(access.phoenium$getLevel()); }
        if (services == null) return;
        if (services.chunkMap() != map) throw new IllegalStateException("regional tracker source map changed");
        FullFinishingServices.requireWorld(services.level(), FullFinishingServices.Kind.TRACKER);
        services.tracking().sourceAdded(access.phoenium$getTrackedEntities(), entity);
    }

    public void trackingSourceRemoved(ChunkMap map, net.minecraft.world.entity.Entity entity) {
        RegionalChunkMapAccess access = (RegionalChunkMapAccess) map;
        WorldServices services;
        synchronized (this) { services = worlds.get(access.phoenium$getLevel()); }
        if (services == null) return;
        if (services.chunkMap() != map) throw new IllegalStateException("regional tracker source map changed");
        FullFinishingServices.requireWorld(services.level(), FullFinishingServices.Kind.TRACKER);
        services.tracking().sourceRemoved(access.phoenium$getTrackedEntities(), entity);
    }

    public synchronized WorldServices world(ServerLevel level) {
        return Objects.requireNonNull(worlds.get(level), "unbound regional world services");
    }

    @Override
    public synchronized Prepared prepare(RegionDirectory.View proposed) {
        Map<ServerLevel, WorldMigration> plans = new IdentityHashMap<>();
        worlds.forEach((level, services) -> plans.put(level, new WorldMigration(
            services.blockTicks().planMigration(proposed), services.fluidTicks().planMigration(proposed),
            services.events().planMigration(proposed), services.tracking().planMigration(proposed))));
        return published -> {
            if (!published.equals(proposed)) throw new IllegalStateException("regional world-service migration view changed");
            synchronized (RegionalWorldServices.this) {
                if (!plans.keySet().equals(worlds.keySet()))
                    throw new IllegalStateException("regional world-service set changed during topology transition");
                plans.forEach((level, plan) -> {
                    WorldServices services = Objects.requireNonNull(worlds.get(level));
                    services.blockTicks().installMigration(plan.blocks(), published);
                    services.fluidTicks().installMigration(plan.fluids(), published);
                    services.events().installMigration(plan.events(), published);
                    services.tracking().installMigration(plan.tracking(), published);
                });
            }
        };
    }

    public synchronized void unbindAll() {
        terminated = true;
        worlds.clear();
        ticks.clear();
    }

    public record WorldServices(ServerLevel level, RegionalScheduledTickService<Block> blockTicks,
                                RegionalScheduledTickService<Fluid> fluidTicks,
                                RegionalBlockEventService<BlockEventData> events,
                                RegionalTrackingService tracking, RegionalChunkGameplayService gameplay, ChunkMap chunkMap) { }
    private record TickBinding<T>(WorldServices world, RegionalScheduledTickService<T> service) { }
    private record WorldMigration(RegionalScheduledTickService.MigrationPlan blocks,
                                  RegionalScheduledTickService.MigrationPlan fluids,
                                  RegionalBlockEventService.MigrationPlan events,
                                  RegionalTrackingService.MigrationPlan tracking) { }
}
