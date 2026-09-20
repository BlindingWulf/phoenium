package dev.phoenium.live;

import dev.phoenium.core.WorldId;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Identity-only protocol for moving one native player incarnation between worlds.  This boundary
 * grants no authority and owns no routing state.  Its only job is to make the source-removal,
 * owner-free publication, destination-admission, and retained-continuation order unambiguous and
 * exactly once.
 */
public final class PlayerDimensionTransitionBoundary implements AutoCloseable {
    private final Map<Long, Slot> transitions = new HashMap<>();
    private final Map<SourceIdentity, Long> bySource = new HashMap<>();
    private long nextGeneration;
    private boolean closed;

    public synchronized Token begin(SourceIdentity source) {
        Objects.requireNonNull(source, "source");
        if (closed) throw new IllegalStateException("player dimension boundary is closed");
        if (source.sourceWorld().equals(source.destinationWorld()))
            throw new IllegalArgumentException("player dimension transition does not change worlds");
        if (bySource.containsKey(source))
            throw new IllegalStateException("player already has a pending dimension transition " + source);
        long generation = nextGeneration = Math.incrementExact(nextGeneration);
        Token token = new Token(source, generation);
        transitions.put(generation, new Slot(token, Phase.SOURCE_ACTIVE));
        bySource.put(source, generation);
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

    public synchronized SourceRemoval authorizeSourceRemoval(Token token, SourceIdentity current) {
        requireIdentity(token, current);
        Slot slot = require(token, Phase.SOURCE_ACTIVE);
        slot.phase = Phase.SOURCE_REMOVING;
        return new SourceRemoval(token, current);
    }

    public synchronized boolean recognizesExpectedRemoval(SourceRemoval authorization, SourceIdentity current) {
        if (authorization == null || current == null || !authorization.source().equals(current)) return false;
        Slot slot = transitions.get(authorization.token().generation());
        return slot != null && slot.token.equals(authorization.token())
            && slot.phase == Phase.SOURCE_REMOVING && slot.token.source().equals(current);
    }

    public synchronized void sourceRetired(SourceRemoval authorization) {
        Objects.requireNonNull(authorization, "authorization");
        requireIdentity(authorization.token(), authorization.source());
        require(authorization.token(), Phase.SOURCE_REMOVING).phase = Phase.SOURCE_RETIRED;
    }

    public synchronized DestinationAdmission authorizeDestinationAdmission(Token token) {
        Slot slot = require(token, Phase.SOURCE_RETIRED);
        slot.phase = Phase.DESTINATION_ADMITTING;
        return new DestinationAdmission(token);
    }

    /** A player transition preserves both incarnations; only routing/owner epochs may advance. */
    public synchronized void destinationAdmitted(DestinationAdmission authorization, DestinationIdentity destination) {
        Objects.requireNonNull(authorization, "authorization");
        Objects.requireNonNull(destination, "destination");
        Slot slot = require(authorization.token(), Phase.DESTINATION_ADMITTING);
        SourceIdentity source = authorization.token().source();
        if (!source.objectId().equals(destination.objectId()))
            throw new IllegalStateException("destination player changed UUID");
        if (source.nativeIncarnation() != destination.nativeIncarnation())
            throw new IllegalStateException("destination player changed native incarnation");
        if (source.registryIncarnation() != destination.registryIncarnation())
            throw new IllegalStateException("destination player changed ownership incarnation");
        if (!source.destinationWorld().equals(destination.world()))
            throw new IllegalStateException("destination player entered a different world");
        slot.phase = Phase.DESTINATION_ADMITTED;
    }

    public synchronized Continuation authorizeContinuation(Token token) {
        Slot slot = require(token, Phase.DESTINATION_ADMITTED);
        slot.phase = Phase.CONTINUATION_RUNNING;
        return new Continuation(token);
    }

    public synchronized void settle(Continuation authorization) {
        Objects.requireNonNull(authorization, "authorization");
        Slot slot = require(authorization.token(), Phase.CONTINUATION_RUNNING);
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
        if (slot == null || !slot.token.equals(token))
            throw new IllegalStateException("stale player transition generation " + token.generation());
        if (slot.phase != expected)
            throw new IllegalStateException("player transition generation " + token.generation()
                + " is " + slot.phase + ", expected " + expected);
        return slot;
    }

    private void requireIdentity(Token token, SourceIdentity current) {
        Objects.requireNonNull(current, "current");
        if (!token.source().equals(current))
            throw new IllegalStateException("player transition source identity changed");
    }

    private void retire(Slot slot, Phase terminal) {
        slot.phase = terminal;
        transitions.remove(slot.token.generation(), slot);
        bySource.remove(slot.token.source(), slot.token.generation());
    }

    public record SourceIdentity(String objectId, long nativeIncarnation, long registryIncarnation,
                                 WorldId sourceWorld, WorldId destinationWorld) {
        public SourceIdentity {
            Objects.requireNonNull(objectId, "objectId");
            Objects.requireNonNull(sourceWorld, "sourceWorld");
            Objects.requireNonNull(destinationWorld, "destinationWorld");
            if (nativeIncarnation < 0 || registryIncarnation < 0)
                throw new IllegalArgumentException("negative player incarnation");
        }
    }

    public record DestinationIdentity(String objectId, long nativeIncarnation, long registryIncarnation,
                                      WorldId world) {
        public DestinationIdentity {
            Objects.requireNonNull(objectId, "objectId");
            Objects.requireNonNull(world, "world");
            if (nativeIncarnation < 0 || registryIncarnation < 0)
                throw new IllegalArgumentException("negative destination incarnation");
        }
    }

    public record Token(SourceIdentity source, long generation) {
        public Token {
            Objects.requireNonNull(source, "source");
            if (generation <= 0) throw new IllegalArgumentException("non-positive player transition generation");
        }
    }

    public record SourceRemoval(Token token, SourceIdentity source) {
        public SourceRemoval { Objects.requireNonNull(token, "token"); Objects.requireNonNull(source, "source"); }
    }
    public record DestinationAdmission(Token token) {
        public DestinationAdmission { Objects.requireNonNull(token, "token"); }
    }
    public record Continuation(Token token) {
        public Continuation { Objects.requireNonNull(token, "token"); }
    }

    public enum Phase {
        SOURCE_ACTIVE, SOURCE_REMOVING, SOURCE_RETIRED, DESTINATION_ADMITTING,
        DESTINATION_ADMITTED, CONTINUATION_RUNNING, SETTLED, CANCELLED, RETIRED;
        private boolean terminal() { return this == SETTLED || this == CANCELLED || this == RETIRED; }
    }

    private static final class Slot {
        final Token token;
        Phase phase;
        Slot(Token token, Phase phase) { this.token = token; this.phase = phase; }
    }
}
