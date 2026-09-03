package dev.phoenium.chunk;

import dev.phoenium.core.TopologyGeneration;
import dev.phoenium.core.WorldId;
import dev.phoenium.region.RegionId;
import dev.phoenium.topology.RoutingKey;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Staged custody ledger for asynchronous chunk completions. The producer future only advances
 * immutable stage metadata; the leased owner is the only claimant, so a completion is applied
 * exactly once per request identity. A stale owner observed at claim time is a reroute condition,
 * never a disk failure, and no stage here carries a fake chunk state.
 */
public final class ChunkCustody {
    public enum Stage { REQUESTED, READY, QUEUED, CLAIMED, APPLIED, FAILED, CANCELLED }

    /** Stable identity of one chunk work request across reroutes and topology generations. */
    public record CustodyId(WorldId world, RoutingKey chunkKey, long requestGeneration, TopologyGeneration requestedTopology) {
        public CustodyId {
            if (world == null || chunkKey == null || requestedTopology == null) throw new IllegalArgumentException("incomplete chunk custody identity");
            if (!world.equals(chunkKey.world())) throw new IllegalArgumentException("chunk custody identity crosses world");
        }
    }

    public static CustodyId id(ChunkWorkRequest request) {
        return new CustodyId(request.world(), request.chunkKey(), request.requestGeneration(), request.requestedTopologyGeneration());
    }

    private final Map<CustodyId, Stage> stages = new ConcurrentHashMap<>();
    private final Map<CustodyId, RegionId> claimedOwners = new ConcurrentHashMap<>();
    private final LongAdder duplicateClaims = new LongAdder();

    /** The producer observed its future; idempotent while still REQUESTED. */
    public void request(CustodyId id) { advance(id, Stage.REQUESTED); }

    /** The producer future completed normally; idempotent while still READY. */
    public void ready(CustodyId id) { advance(id, Stage.READY); }

    /** The completion envelope was admitted to an owner inbox or transition mailbox. */
    public void queued(CustodyId id) { advance(id, Stage.QUEUED); }

    /** The producer future completed with a failure before the completion could be queued as owner work. */
    public void fail(CustodyId id) {
        stages.compute(id, (ignored, current) -> {
            if (current == null) throw new IllegalStateException("chunk failure before custody request " + id);
            if (current == Stage.REQUESTED || current == Stage.READY) return Stage.FAILED;
            if (current == Stage.FAILED) return Stage.FAILED;
            throw new IllegalStateException("chunk failure after custody left the producer stage: " + current);
        });
    }

    /** Drop an unqueued completion. Terminal stages absorb the call; a claimed chunk cannot be cancelled. */
    public void cancel(CustodyId id) {
        stages.compute(id, (ignored, current) -> {
            if (current == null || current == Stage.APPLIED || current == Stage.FAILED || current == Stage.CANCELLED) return current;
            if (current == Stage.CLAIMED) throw new IllegalStateException("claimed chunk completion cannot be cancelled " + id);
            return Stage.CANCELLED;
        });
    }

    /** Exactly-once claim: true the first time the completion reaches the leased owner. */
    public boolean claim(CustodyId id, RegionId owner) {
        boolean[] claimed = new boolean[1];
        stages.compute(id, (ignored, current) -> {
            if (current == Stage.CLAIMED || current == Stage.APPLIED) { duplicateClaims.increment(); claimed[0] = false; return current; }
            if (current == null || current == Stage.REQUESTED || current == Stage.READY || current == Stage.QUEUED) {
                claimed[0] = true;
                return Stage.CLAIMED;
            }
            claimed[0] = false;
            return current;
        });
        if (claimed[0]) claimedOwners.putIfAbsent(id, owner);
        return claimed[0];
    }

    /** The owner records the validated completion admission; requires a successful claim. */
    public void apply(CustodyId id) {
        stages.compute(id, (ignored, current) -> {
            if (current == Stage.CLAIMED) return Stage.APPLIED;
            throw new IllegalStateException("chunk completion application requires a claim: " + current);
        });
    }

    public Optional<Stage> stage(CustodyId id) { return Optional.ofNullable(stages.get(id)); }
    public Optional<RegionId> claimedOwner(CustodyId id) { return Optional.ofNullable(claimedOwners.get(id)); }
    public long duplicateClaims() { return duplicateClaims.sum(); }

    private void advance(CustodyId id, Stage target) {
        stages.compute(id, (ignored, current) -> {
            if (current == null) {
                if (target == Stage.REQUESTED) return Stage.REQUESTED;
                throw new IllegalStateException("chunk custody advance before request: " + target);
            }
            if (current == target) return current;
            if (isTerminal(current)) throw new IllegalStateException("terminal chunk custody stage " + current + " cannot advance to " + target);
            if (rank(current) >= rank(target)) throw new IllegalStateException("chunk custody stage regression " + current + " -> " + target);
            return target;
        });
    }

    private static boolean isTerminal(Stage stage) { return stage == Stage.APPLIED || stage == Stage.FAILED || stage == Stage.CANCELLED; }
    private static int rank(Stage stage) {
        return switch (stage) {
            case REQUESTED -> 0;
            case READY -> 1;
            case QUEUED -> 2;
            case CLAIMED -> 3;
            case APPLIED -> 4;
            default -> -1;
        };
    }
}
