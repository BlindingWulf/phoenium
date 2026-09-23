package dev.phoenium.live;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Test-only observer for relocation traces. Callers supply facts at the actual native hooks;
 * the observer records and checks them but never routes, repairs, or suppresses a transfer.
 * Only the recent event buffer is bounded. Transaction state and the first violation remain
 * available throughout a large deterministic storm.
 */
public final class PortalTransactionObserver {
    public enum Profile { CROSS_DIM_ENTITY, CROSS_DIM_PLAYER, SAME_DIMENSION, NATIVE_REFUSAL }
    public enum Owner { NONE, SOURCE, DESTINATION, FOREIGN }
    public enum Phase {
        CAPTURE, SOURCE_PREFIX, SOURCE_UNWOUND, DESTINATION_SEARCH, DESTINATION_READY,
        DESTINATION_PLATFORM, SOURCE_RETIRE, DESTINATION_CONSTRUCT, RESTORE,
        RESTORE_DESTINATION,
        DESTINATION_INSERT, ADDRESSABLE, TRACKING, CALLBACK, SUFFIX,
        SYNCHRONOUS_WAIT, NATIVE_UNRETAINED, SETTLED, CANCELLED, FAILED
    }
    public record Identity(long generation, UUID uuid, String type, long nativeIncarnation,
            long registryIncarnation, String route, String sourceWorld, String destinationWorld,
            String sourceRoutingKey, String destinationRoutingKey,
            String candidateSha256, String testSourceSha256, Profile profile) {
        public Identity {
            if (generation < 0 || nativeIncarnation < 0 || registryIncarnation < 0)
                throw new IllegalArgumentException("negative relocation identity field");
            Objects.requireNonNull(uuid);
            Objects.requireNonNull(type);
            Objects.requireNonNull(route);
            Objects.requireNonNull(sourceWorld);
            Objects.requireNonNull(destinationWorld);
            Objects.requireNonNull(sourceRoutingKey);
            Objects.requireNonNull(destinationRoutingKey);
            Objects.requireNonNull(candidateSha256);
            Objects.requireNonNull(testSourceSha256);
            Objects.requireNonNull(profile);
        }
    }
    public record Event(Identity identity, Phase phase, Owner owner, String ownerContext,
            String thread, String detail, long nanoTime) {
        public Event {
            Objects.requireNonNull(identity);
            Objects.requireNonNull(phase);
            Objects.requireNonNull(owner);
            Objects.requireNonNull(ownerContext);
            Objects.requireNonNull(thread);
            Objects.requireNonNull(detail);
        }
        public static Event now(Identity identity, Phase phase, Owner owner, String ownerContext, String detail) {
            return new Event(identity, phase, owner, ownerContext,
                Thread.currentThread().getName(), detail, System.nanoTime());
        }
    }
    public record Violation(Identity identity, Phase phase, String reason, Event event) { }

    private static final EnumSet<Phase> DESTINATION_PHASES = EnumSet.of(
        Phase.DESTINATION_PLATFORM, Phase.DESTINATION_CONSTRUCT, Phase.RESTORE_DESTINATION,
        Phase.DESTINATION_INSERT,
        Phase.ADDRESSABLE, Phase.TRACKING, Phase.CALLBACK);
    private static final EnumSet<Phase> SOURCE_PHASES = EnumSet.of(
        Phase.CAPTURE, Phase.SOURCE_PREFIX, Phase.SOURCE_RETIRE, Phase.RESTORE, Phase.SUFFIX);
    private final int traceLimit;
    private final ArrayDeque<Event> recent = new ArrayDeque<>();
    private final Map<Identity, State> transactions = new HashMap<>();
    private Violation firstViolation;
    private long eventCount;
    private long droppedEvents;

    public PortalTransactionObserver(int traceLimit) {
        if (traceLimit < 1) throw new IllegalArgumentException("traceLimit must be positive");
        this.traceLimit = traceLimit;
    }

    public synchronized void observe(Event event) {
        Objects.requireNonNull(event);
        eventCount++;
        if (recent.size() == traceLimit) { recent.removeFirst(); droppedEvents++; }
        recent.addLast(event);
        State state = transactions.computeIfAbsent(event.identity(), ignored -> new State());
        if (state.terminal) fail(event, "event followed terminal settlement");
        if (event.phase() == Phase.NATIVE_UNRETAINED)
            fail(event, "native transfer reached without retained caller profile");
        if (event.phase() == Phase.SYNCHRONOUS_WAIT && event.owner() != Owner.NONE)
            fail(event, "synchronous destination wait held owner authority");
        if (DESTINATION_PHASES.contains(event.phase()) && event.owner() != Owner.DESTINATION)
            fail(event, "destination work or tracking ran outside destination owner");
        if (SOURCE_PHASES.contains(event.phase()) && event.owner() != Owner.SOURCE)
            fail(event, "source work ran outside source owner");
        if ((event.phase() == Phase.SOURCE_UNWOUND || event.phase() == Phase.DESTINATION_SEARCH
                || event.phase() == Phase.DESTINATION_READY) && event.owner() != Owner.NONE)
            fail(event, "owner-free coordination retained authority");
        if (event.phase() == Phase.DESTINATION_INSERT && !state.seen.contains(Phase.SOURCE_RETIRE)
                && event.identity().profile() != Profile.SAME_DIMENSION)
            fail(event, "destination insertion preceded source retirement");
        if (event.phase() == Phase.TRACKING
                && (!state.seen.contains(Phase.DESTINATION_INSERT) || !state.seen.contains(Phase.ADDRESSABLE)))
            fail(event, "tracking preceded insertion or addressability publication");
        if (event.phase() == Phase.CALLBACK && event.identity().profile() != Profile.SAME_DIMENSION
                && !state.seen.contains(Phase.TRACKING))
            fail(event, "native callback preceded destination tracking");
        if (event.phase() == Phase.SETTLED) {
            EnumSet<Phase> required = required(event.identity().profile());
            if (!state.seen.containsAll(required)) {
                EnumSet<Phase> missing = required.clone(); missing.removeAll(state.seen);
                fail(event, "settlement skipped required phases " + missing);
            }
            state.terminal = true;
        } else if (event.phase() == Phase.CANCELLED || event.phase() == Phase.FAILED) {
            if (!state.seen.contains(Phase.CAPTURE)) fail(event, "terminal outcome lacks capture");
            if (event.phase() == Phase.FAILED) fail(event, "relocation reported failure: " + event.detail());
            state.terminal = true;
        }
        state.seen.add(event.phase());
    }

    private static EnumSet<Phase> required(Profile profile) {
        return switch (profile) {
            case CROSS_DIM_ENTITY -> EnumSet.of(Phase.CAPTURE, Phase.SOURCE_UNWOUND,
                Phase.DESTINATION_SEARCH, Phase.DESTINATION_READY, Phase.SOURCE_RETIRE,
                Phase.DESTINATION_CONSTRUCT, Phase.RESTORE, Phase.RESTORE_DESTINATION,
                Phase.DESTINATION_INSERT,
                Phase.ADDRESSABLE, Phase.TRACKING, Phase.CALLBACK, Phase.SUFFIX);
            case CROSS_DIM_PLAYER -> EnumSet.of(Phase.CAPTURE, Phase.SOURCE_UNWOUND,
                Phase.SOURCE_RETIRE, Phase.DESTINATION_INSERT, Phase.ADDRESSABLE,
                Phase.TRACKING, Phase.CALLBACK, Phase.SUFFIX);
            case SAME_DIMENSION -> EnumSet.of(Phase.CAPTURE, Phase.SOURCE_UNWOUND,
                Phase.DESTINATION_READY, Phase.CALLBACK, Phase.SUFFIX);
            case NATIVE_REFUSAL -> EnumSet.of(Phase.CAPTURE, Phase.SOURCE_UNWOUND,
                Phase.DESTINATION_SEARCH, Phase.SUFFIX);
        };
    }

    private void fail(Event event, String reason) {
        if (firstViolation == null) firstViolation = new Violation(event.identity(), event.phase(), reason, event);
    }
    public synchronized Violation firstViolation() { return firstViolation; }
    public synchronized long eventCount() { return eventCount; }
    public synchronized long droppedEvents() { return droppedEvents; }
    public synchronized List<Event> recent() { return List.copyOf(recent); }
    public synchronized int transactionCount() { return transactions.size(); }
    public synchronized void assertClean() {
        if (firstViolation != null)
            throw new AssertionError("portal transaction observer: " + firstViolation.reason()
                + " generation=" + firstViolation.identity().generation()
                + " phase=" + firstViolation.phase() + " recent=" + new ArrayList<>(recent));
        for (var entry : transactions.entrySet()) if (!entry.getValue().terminal)
            throw new AssertionError("portal transaction observer: unfinished generation "
                + entry.getKey().generation() + " phases=" + entry.getValue().seen);
    }
    private static final class State {
        final EnumSet<Phase> seen = EnumSet.noneOf(Phase.class);
        boolean terminal;
    }
}
