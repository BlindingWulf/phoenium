package dev.phoenium.chunk;

import dev.phoenium.topology.RoutingKey;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.*;
import java.util.function.LongPredicate;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

/** Dependency-only closure of the original structure-reference graph. No registry matcher,
 * structure predicate or piece bounding-box test runs before the original LocationPredicate. */
public final class FullStructureInputs {
    public interface ChunkView {
        Map<Structure, LongSet> phoenium$structureReferences();
        Map<Structure, StructureStart> phoenium$structureStarts();
    }
    public interface CacheView {
        long[] phoenium$structureCachePositions();
        ChunkStatus[] phoenium$structureCacheStatuses();
        ChunkAccess[] phoenium$structureCacheChunks();
    }
    enum State { READY, CHANGED, PENDING, STALE, CAPACITY }
    private record References(ChunkPos position, ChunkAccess actual, Map<Structure, LongSet> map,
                              List<Reference> entries) {
        boolean valid() {
            if (view(actual).phoenium$structureReferences() != map || map.size() != entries.size()) return false;
            for (var entry : entries) {
                if (map.get(entry.structure) != entry.alias || entry.alias.size() != entry.positions.length) return false;
                for (long position : entry.positions) if (!entry.alias.contains(position)) return false;
            }
            return true;
        }
    }
    private record Reference(Structure structure, LongSet alias, long[] positions) { }
    private record Cached(ChunkPos position, ChunkStatus status, ChunkAccess actual) { }
    private record Snapshot(List<References> references, List<Cached> cached, Set<ChunkPos> starts) { }
    private FullStructureInputs() { }

    static final class Plan {
        private final Set<ChunkPos> sources;
        private Snapshot snapshot;
        private long bytes;
        private boolean stale;
        Plan(Set<ChunkPos> sources) { this.sources = Set.copyOf(sources); }
        Set<ChunkPos> starts() { return snapshot == null ? Set.of() : snapshot.starts; }
        long bytes() { return bytes; }
        boolean current(Set<ChunkPos> required) { return !stale && sources.equals(required); }
        boolean empty() { return sources.isEmpty(); }

        State refine(FullRegistrationRuntime runtime, FullRegistrationRuntime.Graph graph,
                     List<FullInitializedInputs.Target> targets, LongPredicate reserve) {
            if (empty()) return State.READY;
            if (!graph.encloseContextTargets(targets)) return State.PENDING;
            var route = runtime.authority.directory().resolve(graph.lifecycle.chunkKey()).orElse(null);
            if (route == null) return State.PENDING;
            var holders = holders(targets);
            final Long measured;
            try (var turn = runtime.structureMutations.tryRead(graph.finishing.stage(), holders)) {
                if (turn == null) return State.PENDING;
                var result = FullContextRefinement.read(runtime, graph, route, graph.contextFootprint(targets), claim -> {
                    if (!validTargets(runtime, graph, targets, turn, claim)) return null;
                    return measure(runtime, targets);
                });
                if (result.state() != FullContextRefinement.State.OBSERVED || result.value() == null) return State.PENDING;
                measured = result.value();
            }
            // Charge the old receipt and complete replacement before allocating any arrays.
            if (!reserve.test(Math.addExact(bytes, measured))) return State.CAPACITY;
            final Snapshot next;
            try (var turn = runtime.structureMutations.tryRead(graph.finishing.stage(), holders)) {
                if (turn == null) return State.PENDING;
                var result = FullContextRefinement.read(runtime, graph, route, graph.contextFootprint(targets), claim -> {
                    if (!validTargets(runtime, graph, targets, turn, claim)) return null;
                    if (measure(runtime, targets) > measured) return null;
                    return capture(runtime, targets);
                });
                if (result.state() != FullContextRefinement.State.OBSERVED || result.value() == null) {
                    runtime.inputs.changed(); return State.PENDING;
                }
                next = result.value();
            }
            var priorStarts = starts();
            snapshot = next; bytes = measured;
            if (!priorStarts.equals(next.starts)) return State.CHANGED;
            return State.READY;
        }

        boolean validate(FullRegistrationRuntime runtime, FullRegistrationRuntime.Graph graph,
                         List<FullInitializedInputs.Target> targets, FullStructureMutationTurns.Read turn,
                         FullContextReadClaim claim) {
            if (empty()) return true;
            boolean valid = snapshot != null && turn != null && validTargets(runtime, graph, targets, turn, claim);
            if (valid) for (var references : snapshot.references) if (!references.valid()) { valid = false; break; }
            if (valid) for (var target : targets) {
                for (var status : List.of(ChunkStatus.STRUCTURE_REFERENCES, ChunkStatus.STRUCTURE_STARTS)) {
                    if (evidence(target, status) == null) continue;
                    var cached = cached(runtime.level().getChunkSource(), target.position(), status);
                    if (cached != null && !allows(target, status, cached)) { valid = false; break; }
                }
            }
            if (!valid) { stale = true; runtime.inputs.changed(); }
            return valid;
        }

        boolean allows(FullInitializedInputs.Target target, ChunkStatus status, ChunkAccess actual) {
            var evidence = evidence(target, status);
            if (evidence == null || actual == null) return false;
            if (actual == evidence.actual()) return true;
            return snapshot != null && snapshot.cached.stream().anyMatch(cached -> cached.position.equals(target.position())
                    && cached.status == status && cached.actual == actual);
        }

        private long measure(FullRegistrationRuntime runtime, List<FullInitializedInputs.Target> targets) {
            long total = Math.addExact(256L, Math.multiplyExact(96L, targets.size()));
            for (var target : targets) if (target.structureReferences() != null) {
                total = Math.addExact(total, measureReferences(target.structureReferences().actual()));
                var cached = cached(runtime.level().getChunkSource(), target.position(), ChunkStatus.STRUCTURE_REFERENCES);
                if (cached != null && cached != target.structureReferences().actual())
                    total = Math.addExact(total, measureReferences(cached));
            }
            return total;
        }
        private static long measureReferences(ChunkAccess actual) {
            long total = 96L;
            for (var entry : view(actual).phoenium$structureReferences().entrySet())
                total = Math.addExact(total, Math.addExact(64L, Math.multiplyExact(80L, entry.getValue().size())));
            return total;
        }
        private Snapshot capture(FullRegistrationRuntime runtime, List<FullInitializedInputs.Target> targets) {
            var references = new ArrayList<References>();
            var cache = new ArrayList<Cached>();
            var starts = new LinkedHashSet<ChunkPos>();
            for (var target : targets) {
                if (target.structureReferences() != null)
                    references.add(captureReferences(target.position(), target.structureReferences().actual(), starts));
                for (var status : List.of(ChunkStatus.STRUCTURE_REFERENCES, ChunkStatus.STRUCTURE_STARTS)) {
                    if (evidence(target, status) == null) continue;
                    var cached = cached(runtime.level().getChunkSource(), target.position(), status);
                    if (cached == null) continue;
                    cache.add(new Cached(target.position(), status, cached));
                    if (status == ChunkStatus.STRUCTURE_REFERENCES && cached != target.structureReferences().actual())
                        references.add(captureReferences(target.position(), cached, starts));
                }
            }
            return new Snapshot(List.copyOf(references), List.copyOf(cache), Collections.unmodifiableSet(starts));
        }
        private static References captureReferences(ChunkPos position, ChunkAccess actual, Set<ChunkPos> starts) {
            var map = view(actual).phoenium$structureReferences();
            var entries = new ArrayList<Reference>(map.size());
            for (var entry : map.entrySet()) {
                long[] positions = entry.getValue().toLongArray();
                for (long value : positions) starts.add(ChunkPos.unpack(value));
                entries.add(new Reference(entry.getKey(), entry.getValue(), positions));
            }
            return new References(position, actual, map, List.copyOf(entries));
        }
    }
    static FullStatusInputs.Evidence evidence(FullInitializedInputs.Target target, ChunkStatus status) {
        return status == ChunkStatus.STRUCTURE_REFERENCES ? target.structureReferences()
                : status == ChunkStatus.STRUCTURE_STARTS ? target.structureStarts() : null;
    }
    static List<GenerationChunkHolder> holders(List<FullInitializedInputs.Target> targets) {
        return targets.stream().filter(target -> target.structureReferences() != null || target.structureStarts() != null)
                .map(FullInitializedInputs.Target::holder).toList();
    }
    private static boolean validTargets(FullRegistrationRuntime runtime, FullRegistrationRuntime.Graph graph,
                                        List<FullInitializedInputs.Target> targets, FullStructureMutationTurns.Read turn,
                                        FullContextReadClaim claim) {
        for (var target : targets) if (target.structureReferences() != null || target.structureStarts() != null) {
            claim.require(graph, new RoutingKey(runtime.authority.world(), target.position().x(), target.position().z()));
            turn.require(graph.finishing.stage(), target.holder());
            if (runtime.visible(target.position()) != target.holder() || target.holder().getTicketLevel() != target.ticketLevel()
                    || target.structureReferences() != null && !FullStatusInputs.validates(target.holder(), target.structureReferences())
                    || target.structureStarts() != null && !FullStatusInputs.validates(target.holder(), target.structureStarts())) return false;
        }
        return true;
    }
    private static ChunkView view(ChunkAccess actual) {
        return (ChunkView) (actual instanceof ImposterProtoChunk imposter ? imposter.getWrapped() : actual);
    }
    private static ChunkAccess cached(ServerChunkCache cache, ChunkPos position, ChunkStatus status) {
        var view = (CacheView) cache;
        for (int index = 0; index < view.phoenium$structureCachePositions().length; index++)
            if (view.phoenium$structureCachePositions()[index] == position.pack()
                    && view.phoenium$structureCacheStatuses()[index] == status)
                return view.phoenium$structureCacheChunks()[index];
        return null;
    }
}
