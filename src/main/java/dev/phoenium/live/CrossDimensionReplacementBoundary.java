package dev.phoenium.live;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Identity-only protocol for one remove-and-recreate dimension transition.  It does not grant
 * entity authority and deliberately owns no routing metadata; callers must obtain the current
 * source or destination owner separately for every authorized stage.
 */
public final class CrossDimensionReplacementBoundary implements AutoCloseable {
    private final Map<Long, Slot> transitions = new HashMap<>();
    private final Map<SourceIdentity, Long> bySource = new HashMap<>();
    private long nextGeneration;
    private boolean closed;

    public synchronized Token begin(SourceIdentity source) {
        Objects.requireNonNull(source, "source");
        if (closed) throw new IllegalStateException("cross-dimension replacement boundary is closed");
        if (bySource.containsKey(source)) throw new IllegalStateException("source already has a pending replacement " + source);
        nextGeneration = Math.incrementExact(nextGeneration);
        Token token = new Token(source, nextGeneration);
        transitions.put(token.generation(), new Slot(token, Phase.CAPTURED));
        bySource.put(source, token.generation());
        return token;
    }

    public synchronized boolean isCurrent(Token token) {
        Slot slot = transitions.get(token.generation());
        return slot != null && slot.token.equals(token) && !slot.phase.terminal();
    }

    public synchronized Phase phase(Token token) {
        Slot slot = transitions.get(token.generation());
        return slot != null && slot.token.equals(token) ? slot.phase : Phase.RETIRED;
    }

    public synchronized void prepared(Token token) {
        require(token, Phase.CAPTURED).phase = Phase.PREPARED;
    }

    public synchronized SourceRemoval authorizeSourceRemoval(Token token, SourceIdentity current) {
        requireIdentity(token, current);
        Slot slot = require(token, Phase.PREPARED);
        slot.phase = Phase.SOURCE_RETIRING;
        return new SourceRemoval(token, current);
    }

    public synchronized boolean recognizesExpectedRemoval(SourceRemoval authorization, SourceIdentity current) {
        if (authorization == null || current == null || !authorization.source.equals(current)) return false;
        Slot slot = transitions.get(authorization.token.generation());
        return slot != null && slot.token.equals(authorization.token)
            && slot.phase == Phase.SOURCE_RETIRING && slot.token.source.equals(current);
    }

    public synchronized void sourceRetired(SourceRemoval authorization) {
        Objects.requireNonNull(authorization, "authorization");
        requireIdentity(authorization.token, authorization.source);
        require(authorization.token, Phase.SOURCE_RETIRING).phase = Phase.SOURCE_RETIRED;
    }

    public synchronized DestinationAdmission authorizeDestinationAdmission(Token token) {
        Slot slot = require(token, Phase.SOURCE_RETIRED);
        slot.phase = Phase.DESTINATION_ADMITTING;
        return new DestinationAdmission(token);
    }

    public synchronized void destinationAdmitted(DestinationAdmission authorization, DestinationIdentity destination) {
        Objects.requireNonNull(authorization, "authorization");
        Objects.requireNonNull(destination, "destination");
        Token token = authorization.token;
        Slot slot = require(token, Phase.DESTINATION_ADMITTING);
        SourceIdentity source = token.source;
        if (!source.objectId.equals(destination.objectId))
            throw new IllegalStateException("destination replacement changed UUID");
        if (source.nativeIncarnation == destination.nativeIncarnation)
            throw new IllegalStateException("destination reused the retired native incarnation");
        if (destination.registryIncarnation <= source.registryIncarnation)
            throw new IllegalStateException("destination did not advance the ownership incarnation");
        slot.destination = destination;
        slot.phase = Phase.DESTINATION_ADMITTED;
    }

    public synchronized Continuation authorizeReplacementContinuation(Token token) {
        Slot slot = require(token, Phase.DESTINATION_ADMITTED);
        slot.phase = Phase.CONTINUATION_RUNNING;
        return new Continuation(token, true);
    }

    public synchronized Continuation authorizeUnchangedSourceContinuation(Token token, SourceIdentity current) {
        requireIdentity(token, current);
        Slot slot = require(token, Phase.PREPARED);
        slot.phase = Phase.CONTINUATION_RUNNING;
        return new Continuation(token, false);
    }

    public synchronized void settle(Continuation authorization) {
        Objects.requireNonNull(authorization, "authorization");
        Slot slot = require(authorization.token, Phase.CONTINUATION_RUNNING);
        retire(slot, Phase.SETTLED);
    }

    public synchronized boolean cancel(Token token) {
        Slot slot = transitions.get(token.generation());
        if (slot == null || !slot.token.equals(token) || slot.phase.terminal()) return false;
        retire(slot, Phase.CANCELLED);
        return true;
    }

    public synchronized int pendingCount() { return transitions.size(); }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        for (Slot slot : transitions.values()) slot.phase = Phase.CANCELLED;
        transitions.clear();
        bySource.clear();
    }

    private Slot require(Token token, Phase expected) {
        Objects.requireNonNull(token, "token");
        Slot slot = transitions.get(token.generation());
        if (slot == null || !slot.token.equals(token)) throw new IllegalStateException("stale replacement generation " + token.generation());
        if (slot.phase != expected)
            throw new IllegalStateException("replacement generation " + token.generation() + " is " + slot.phase + ", expected " + expected);
        return slot;
    }

    private void requireIdentity(Token token, SourceIdentity current) {
        Objects.requireNonNull(current, "current");
        if (!token.source.equals(current)) throw new IllegalStateException("replacement source incarnation changed");
    }

    private void retire(Slot slot, Phase terminal) {
        slot.phase = terminal;
        transitions.remove(slot.token.generation(), slot);
        bySource.remove(slot.token.source(), slot.token.generation());
    }

    public record SourceIdentity(String objectId, long nativeIncarnation, long registryIncarnation) {
        public SourceIdentity {
            Objects.requireNonNull(objectId, "objectId");
            if (nativeIncarnation < 0 || registryIncarnation < 0) throw new IllegalArgumentException("negative source incarnation");
        }
    }

    public record DestinationIdentity(String objectId, long nativeIncarnation, long registryIncarnation) {
        public DestinationIdentity {
            Objects.requireNonNull(objectId, "objectId");
            if (nativeIncarnation < 0 || registryIncarnation < 0) throw new IllegalArgumentException("negative destination incarnation");
        }
    }

    public record Token(SourceIdentity source, long generation) {
        public Token {
            Objects.requireNonNull(source, "source");
            if (generation <= 0) throw new IllegalArgumentException("non-positive transition generation");
        }
    }

    public record SourceRemoval(Token token, SourceIdentity source) {
        public SourceRemoval { Objects.requireNonNull(token, "token"); Objects.requireNonNull(source, "source"); }
    }
    public record DestinationAdmission(Token token) {
        public DestinationAdmission { Objects.requireNonNull(token, "token"); }
    }
    public record Continuation(Token token, boolean replaced) {
        public Continuation { Objects.requireNonNull(token, "token"); }
    }

    public enum Phase {
        CAPTURED, PREPARED, SOURCE_RETIRING, SOURCE_RETIRED, DESTINATION_ADMITTING,
        DESTINATION_ADMITTED, CONTINUATION_RUNNING, SETTLED, CANCELLED, RETIRED;
        private boolean terminal() { return this == SETTLED || this == CANCELLED || this == RETIRED; }
    }

    private static final class Slot {
        final Token token;
        Phase phase;
        DestinationIdentity destination;
        Slot(Token token, Phase phase) { this.token = token; this.phase = phase; }
    }
}
