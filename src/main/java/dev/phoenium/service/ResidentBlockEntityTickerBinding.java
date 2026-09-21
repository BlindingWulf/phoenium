package dev.phoenium.service;

import dev.phoenium.chunk.*;
import dev.phoenium.core.TopologyGeneration;
import dev.phoenium.topology.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.chunk.LevelChunk;

/** Registration is not publication. This object never runs or replays a tick. */
public final class ResidentBlockEntityTickerBinding implements AutoCloseable {
    public enum State { REGISTERED_WAITING_SOURCE, SOURCE_BOUND_READY, ACTIVE, RETIRED }
    private final ServerLevel world;
    private final RegionDirectory directory;
    private final RoutingKey key;
    private final TopologyGeneration topology;
    private final RegionRoute route;
    private final BlockPos position;
    private TickingBlockEntity ticker;
    private BlockEntity entity;
    private LevelChunk chunk;
    private ChunkHolder holder;
    private ChunkHolderLifetime lifetime;
    private ResidentChunkSourceBinding.Registration registration;
    private ResidentChunkSourceBinding source;
    private State state = State.REGISTERED_WAITING_SOURCE;

    public ResidentBlockEntityTickerBinding(ServerLevel world, TickingBlockEntity ticker,
            RegionDirectory directory, RoutingKey key) {
        this.world = world; this.ticker = ticker; this.directory = directory; this.key = key;
        topology = directory.snapshot().generation();
        route = directory.resolve(key).orElse(null);
        position = ticker.getPos().immutable();
        if (ticker instanceof LocalNativeTickerAccess access) {
            entity = access.phoenium$localBlockEntity();
            chunk = access.phoenium$localChunk();
        }
        holder = FullFinishingServices.registeringHolder(world, net.minecraft.world.level.ChunkPos.pack(position));
        if (holder == null) holder = BlockEntityExternalReadPreparation.visible(world, net.minecraft.world.level.ChunkPos.pack(position));
        lifetime = BlockEntityExternalReadPreparation.lifetime(holder);
        if (lifetime != null) registration = new ResidentChunkSourceBinding.Registration(holder, lifetime);
    }

    public State state() { return state; }
    public ResidentChunkSourceBinding source() { return source; }
    public boolean ready() {
        if (state == State.RETIRED) return false;
        boolean sleeping = LithiumSleepingTickerBridge.isSleepingSentinel(ticker);
        if (entity == null || chunk == null || ticker.isRemoved() || entity.isRemoved()
                || entity.getLevel() != world
                || !sleeping && (!position.equals(ticker.getPos())
                    || !(ticker instanceof LocalNativeTickerAccess access)
                    || access.phoenium$localBlockEntity() != entity || access.phoenium$localChunk() != chunk)
                || chunk.getBlockEntities().get(position) != entity
                || !topology.equals(directory.snapshot().generation())
                || !java.util.Objects.equals(route, directory.resolve(key).orElse(null))
                || directory.pendingChange(key).filter(change -> !change.completed()).isPresent()
                || registration != null && !registration.current()) {
            close(); return false;
        }
        var visible = BlockEntityExternalReadPreparation.visible(world, chunk.getPos().pack());
        if (visible != null && holder != null && visible != holder) { close(); return false; }
        if (source != null) {
            if (!source.current()) { close(); return false; }
            return !sleeping;
        }
        var candidate = ResidentChunkSourceBinding.capture(world, chunk.getPos().pack());
        if (candidate == null) return false;
        if (candidate.chunk() != chunk || holder != null && candidate.holder() != holder
                || lifetime != null && candidate.lifetime() != lifetime) { close(); return false; }
        holder = candidate.holder(); lifetime = candidate.lifetime(); source = candidate;
        state = State.SOURCE_BOUND_READY;
        return !sleeping;
    }
    public boolean activate() {
        if (!ready()) return false;
        state = State.ACTIVE;
        return true;
    }
    @Override public void close() {
        state = State.RETIRED; source = null; registration = null; ticker = null; entity = null; chunk = null; holder = null; lifetime = null;
    }
}
