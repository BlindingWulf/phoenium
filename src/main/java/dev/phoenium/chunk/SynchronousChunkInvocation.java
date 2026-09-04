package dev.phoenium.chunk;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;

/**
 * Exact argument-reference locator for the otherwise unchanged $3/$4/read methods. The key has
 * only immutable stage identity, not a holder, service, callback or lease. No current-holder or
 * coordinate lookup exists. The finite index lives only across the synchronous invocation.
 */
public final class SynchronousChunkInvocation implements AutoCloseable {
    public static final class Key {
        private final ChunkCustodyModel.StageId stage;
        private Key(ChunkCustodyModel.StageId stage) { this.stage = stage; }
        public ChunkCustodyModel.StageId stage() { return stage; }
    }
    private static final ReentrantLock INDEX_GATE = new ReentrantLock();
    private static final IdentityHashMap<Key,SynchronousChunkInvocation> INDEX = new IdentityHashMap<>();
    private final Key key;
    private final ChunkStageClaim claim;
    private final ChunkPrerequisiteServices services;
    private final ChunkPos position;
    private final SerializableChunkData parsed;
    private final LightInputService.BatchId batch;
    private final boolean sky;
    private final List<LightInputService.OccurrenceId> occurrences;
    private int cursor;

    public SynchronousChunkInvocation(ChunkStageClaim claim,ChunkPrerequisiteServices services,
            ChunkPos position,SerializableChunkData parsed,LightInputService.BatchId batch,boolean sky) {
        this.claim = Objects.requireNonNull(claim); this.services = Objects.requireNonNull(services);
        this.position = Objects.requireNonNull(position); this.parsed = parsed; this.batch = batch; this.sky = sky;
        claim.require(claim.stage()); key = new Key(claim.stage());
        occurrences = batch == null ? List.of() : services.light().snapshot(batch).orElseThrow().occurrences().stream()
                .map(LightInputService.OccurrenceSnapshot::id).toList();
        var argument = (ChunkInvocationArgument) (Object) position;
        if (argument.phoenium$invocation() != null) throw new ChunkProtocolException("overlapping original chunk argument invocation");
        INDEX_GATE.lock(); try { INDEX.put(key,this); } finally { INDEX_GATE.unlock(); }
        argument.phoenium$invocation(key);
    }
    public static SynchronousChunkInvocation require(ChunkPos position) {
        var key = ((ChunkInvocationArgument) (Object) position).phoenium$invocation();
        if (key == null) throw new ChunkProtocolException("unbound controlled chunk invocation");
        final SynchronousChunkInvocation invocation;
        INDEX_GATE.lock(); try { invocation = INDEX.get(key); } finally { INDEX_GATE.unlock(); }
        if (invocation == null || invocation.position != position) throw new ChunkProtocolException("expired/foreign original chunk argument");
        invocation.claim.require(key.stage()); return invocation;
    }
    public static boolean bound(ChunkPos position) {
        return ((ChunkInvocationArgument) (Object) position).phoenium$invocation() != null;
    }
    public ChunkStageClaim claim() { claim.require(key.stage()); return claim; }
    public ChunkPrerequisiteServices services() { return services; }
    public void requireRead(SerializableChunkData data,ServerLevel level) {
        claim.require(key.stage());
        if (data != parsed || batch == null || level.dimensionType().hasSkyLight() != sky)
            throw new ChunkProtocolException("parsed input/dimension binding changed before read");
    }
    public void ingress(LightInputService.Kind kind,Object argument,DataLayer payload,
                        LightInputService.PhysicalIngress original) throws Throwable {
        claim.require(key.stage());
        if (cursor >= occurrences.size()) throw new ChunkProtocolException("undeclared original light call");
        var occurrence = occurrences.get(cursor);
        String section = kind == LightInputService.Kind.RETAIN ? "chunk" : Long.toString(((SectionPos) argument).asLong());
        if (occurrence.kind() != kind || !occurrence.sectionKey().equals(section))
            throw new ChunkProtocolException("original physical light order/section mismatch");
        if (kind != LightInputService.Kind.RETAIN) {
            int expected = 1;
            DataLayer expectedPayload = null;
            for (var entry : parsed.sectionData()) {
                if (entry.blockLight() != null) { if (expected++ == cursor) expectedPayload = entry.blockLight(); }
                if (sky && entry.skyLight() != null) { if (expected++ == cursor) expectedPayload = entry.skyLight(); }
            }
            if (payload != expectedPayload) throw new ChunkProtocolException("original light payload reference changed");
        } else if (argument != position) throw new ChunkProtocolException("original retain position changed");
        var carrier = (LightInvocationArgument) argument;
        if (carrier.phoenium$lightWitness() != null) throw new ChunkProtocolException("nested physical argument binding");
        services.light().invoke(claim,occurrence,insertion -> {
            carrier.phoenium$lightWitness(new LightMailboxCommand.Witness(occurrence,insertion));
            try { original.submit(insertion); } finally { carrier.phoenium$lightWitness(null); }
        });
        cursor++;
    }
    public void readReturned() { services.light().sealRead(claim,batch); }
    @Override public void close() {
        ((ChunkInvocationArgument) (Object) position).phoenium$invocation(null);
        INDEX_GATE.lock(); try { INDEX.remove(key); } finally { INDEX_GATE.unlock(); }
    }
    public static int retainedEntries() { INDEX_GATE.lock(); try { return INDEX.size(); } finally { INDEX_GATE.unlock(); } }
}
