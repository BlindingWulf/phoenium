package dev.phoenium.chunk;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;

/**
 * One provider-worker-local capability for the locked C2ME asynchronous DISK parse.  It grants
 * no regional mutation authority: its only live side effect is the exact ScalableLux persisted
 * light receipt path.  C2ME's deferred POI mutation is handled later by the regional dispatcher.
 */
public final class PrivateC2meReadInvocation implements AutoCloseable {
    private static final ThreadLocal<PrivateC2meReadInvocation> CURRENT = new ThreadLocal<>();
    private static final AtomicInteger RETAINED = new AtomicInteger();

    private final Thread creator;
    private final C2meReadAccess.Observation observation;
    private final ChunkPrerequisiteServices services;
    private final ChunkCustodyModel.LifecycleId lifecycle;
    private final ChunkCustodyModel.WorkId work;
    private final ChunkPos position;
    private final SerializableChunkData parsed;
    private final LightInputService.BatchId batch;
    private final ServerLevel level;
    private final boolean sky;
    private final List<LightInputService.OccurrenceId> occurrences;
    private boolean active = true;
    private int cursor;

    PrivateC2meReadInvocation(C2meReadAccess.Observation observation, ChunkPrerequisiteServices services,
                              ChunkCustodyModel.LifecycleId lifecycle, ChunkCustodyModel.WorkId work,
                              ChunkPos position, SerializableChunkData parsed, LightInputService.BatchId batch,
                              ServerLevel level) {
        this.creator = Thread.currentThread();
        this.observation = Objects.requireNonNull(observation);
        this.services = Objects.requireNonNull(services);
        this.lifecycle = Objects.requireNonNull(lifecycle);
        this.work = Objects.requireNonNull(work);
        this.position = Objects.requireNonNull(position);
        this.parsed = Objects.requireNonNull(parsed);
        this.batch = Objects.requireNonNull(batch);
        this.level = Objects.requireNonNull(level);
        this.sky = level.dimensionType().hasSkyLight();
        if (!ScalableLuxLightBridge.ownsPersistedReload(level.getLightEngine()))
            throw new ChunkProtocolException("C2ME private persisted read requires the exact ScalableLux engine");
        if (CURRENT.get() != null) throw new ChunkProtocolException("overlapping private C2ME persisted read");
        requireCurrent();
        this.occurrences = services.light().snapshot(batch).orElseThrow(
                () -> new ChunkProtocolException("private C2ME read lost its light batch")).occurrences().stream()
                .map(LightInputService.OccurrenceSnapshot::id).toList();
        CURRENT.set(this);
        RETAINED.incrementAndGet();
    }

    /** Null means this thread is on the ordinary synchronous path.  A mismatched active private
     * invocation is an invariant failure and may not silently fall through to another authority. */
    public static PrivateC2meReadInvocation current(ChunkPos position) {
        PrivateC2meReadInvocation invocation = CURRENT.get();
        if (invocation == null) return null;
        invocation.requireThreadAndPosition(position);
        invocation.requireCurrent();
        return invocation;
    }

    public void requireRead(SerializableChunkData data, ServerLevel readLevel) {
        requireThreadAndPosition(position);
        requireCurrent();
        if (data != parsed || readLevel != level || data.chunkPos() != position && !data.chunkPos().equals(position)
                || readLevel.dimensionType().hasSkyLight() != sky)
            throw new ChunkProtocolException("private C2ME parsed input/world binding changed before read");
        if (!ScalableLuxLightBridge.ownsPersistedReload(readLevel.getLightEngine()))
            throw new ChunkProtocolException("private C2ME read lost exact ScalableLux ownership");
    }

    public void ingress(LightInputService.Kind kind, Object argument, DataLayer payload,
                        LightInputService.PhysicalIngress original) throws Throwable {
        requireThreadAndPosition(position);
        requireCurrent();
        if (cursor >= occurrences.size()) throw new ChunkProtocolException("undeclared private C2ME light call");
        LightInputService.OccurrenceId occurrence = occurrences.get(cursor);
        String section = kind == LightInputService.Kind.RETAIN ? "chunk"
                : Long.toString(((SectionPos) argument).asLong());
        if (occurrence.kind() != kind || !occurrence.sectionKey().equals(section))
            throw new ChunkProtocolException("private C2ME light order/section mismatch");
        if (kind == LightInputService.Kind.RETAIN) {
            if (argument != position) throw new ChunkProtocolException("private C2ME retain position identity changed");
        } else {
            int expected = 1;
            DataLayer expectedPayload = null;
            for (SerializableChunkData.SectionData entry : parsed.sectionData()) {
                if (entry.blockLight() != null && expected++ == cursor) expectedPayload = entry.blockLight();
                if (sky && entry.skyLight() != null && expected++ == cursor) expectedPayload = entry.skyLight();
            }
            if (payload != expectedPayload)
                throw new ChunkProtocolException("private C2ME light payload reference changed");
        }
        LightInvocationArgument carrier = (LightInvocationArgument) argument;
        if (carrier.phoenium$lightWitness() != null)
            throw new ChunkProtocolException("nested private C2ME physical argument binding");
        services.light().invokePrivateC2meRead(this, occurrence, insertion -> {
            carrier.phoenium$lightWitness(new LightMailboxCommand.Witness(occurrence, insertion));
            try { original.submit(insertion); }
            finally { carrier.phoenium$lightWitness(null); }
        });
        cursor++;
    }

    public void readReturned() {
        requireThreadAndPosition(position);
        requireCurrent();
        services.light().sealPrivateC2meRead(this, batch);
    }

    boolean authorizes(LightInputService service, LightInputService.BatchId expected) {
        requireThreadAndPosition(position);
        requireCurrent();
        return service == services.light() && expected.equals(batch);
    }

    private void requireThreadAndPosition(ChunkPos expected) {
        if (!active || CURRENT.get() != this) throw new ChunkProtocolException("expired private C2ME read capability");
        if (Thread.currentThread() != creator) throw new ChunkProtocolException("private C2ME read capability crossed threads");
        if (expected != position) throw new ChunkProtocolException("private C2ME read position identity changed");
    }

    private void requireCurrent() {
        if (!active || Thread.currentThread() != creator)
            throw new ChunkProtocolException("expired or transferred private C2ME read capability");
        if (!services.lifecycles().observeSameHolder(lifecycle, observation.centerHolder())
                || !services.works().isCurrentWork(work) || !observation.stillCurrent())
            throw new ChunkProtocolException("stale private C2ME DISK lineage");
    }

    @Override public void close() {
        if (Thread.currentThread() != creator)
            throw new ChunkProtocolException("private C2ME read closed by another thread");
        if (!active) return;
        if (CURRENT.get() != this) throw new ChunkProtocolException("private C2ME read scope identity changed");
        active = false;
        CURRENT.remove();
        RETAINED.decrementAndGet();
    }

    public static int retainedEntries() { return RETAINED.get(); }
}
