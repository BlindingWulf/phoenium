package dev.phoenium.live;

import dev.phoenium.failure.FailureBarrier;
import dev.phoenium.scheduler.RegionScheduler;
import dev.phoenium.topology.RegionDirectory;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class PhoeniumLiveRuntimePhaseProgressionTest {
    private enum Step { PREFIX_OWNER, SCHEDULED_COORDINATOR, MIDDLE_OWNER, READINESS_COORDINATOR,
        BLOCK_EVENTS_COORDINATOR, FINAL_PREFIX_OWNER, FINAL_DECISION_COORDINATOR, FINAL_OWNER, DONE, STUCK }

    @Test void completePhaseChainUsesFourRealOwnerAdvancesAcrossConsecutiveCoordinatorSteps() {
        AtomicReference<Step> step = new AtomicReference<>(Step.PREFIX_OWNER);
        AtomicBoolean queued = new AtomicBoolean(true);
        AtomicInteger ownerAdvances = new AtomicInteger();
        var order = new java.util.ArrayList<String>();

        while (step.get() != Step.DONE) {
            if (!queued.get()) PhoeniumLiveRuntime.advanceConsecutiveOwnerFreePhases(queued::get,
                () -> step.get() == Step.DONE, () -> true, step::get, () -> {
                    switch (step.get()) {
                        case SCHEDULED_COORDINATOR -> {
                            order.add("coordinator:scheduled"); step.set(Step.MIDDLE_OWNER); queued.set(true);
                        }
                        case READINESS_COORDINATOR -> {
                            order.add("coordinator:readiness"); step.set(Step.BLOCK_EVENTS_COORDINATOR);
                        }
                        case BLOCK_EVENTS_COORDINATOR -> {
                            order.add("coordinator:block-events"); step.set(Step.FINAL_PREFIX_OWNER); queued.set(true);
                        }
                        case FINAL_DECISION_COORDINATOR -> {
                            order.add("coordinator:final-decision"); step.set(Step.FINAL_OWNER); queued.set(true);
                        }
                        default -> fail("unexpected coordinator step " + step.get());
                    }
                });
            if (!queued.get()) continue;
            ownerAdvances.incrementAndGet(); queued.set(false);
            switch (step.get()) {
                case PREFIX_OWNER -> { order.add("owner:prefix"); step.set(Step.SCHEDULED_COORDINATOR); }
                case MIDDLE_OWNER -> { order.add("owner:middle"); step.set(Step.READINESS_COORDINATOR); }
                case FINAL_PREFIX_OWNER -> { order.add("owner:final-prefix"); step.set(Step.FINAL_DECISION_COORDINATOR); }
                case FINAL_OWNER -> { order.add("owner:final"); step.set(Step.DONE); }
                default -> fail("unexpected owner step " + step.get());
            }
        }

        assertEquals(4, ownerAdvances.get());
        assertEquals(java.util.List.of("owner:prefix", "coordinator:scheduled", "owner:middle",
            "coordinator:readiness", "coordinator:block-events", "owner:final-prefix",
            "coordinator:final-decision", "owner:final"), order);
    }

    @Test void unchangedOwnerFreePhaseFailsClosedWithoutCallingAnOwnerFallback() {
        AtomicReference<Step> step = new AtomicReference<>(Step.STUCK);
        AtomicInteger attempts = new AtomicInteger();
        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> PhoeniumLiveRuntime.advanceConsecutiveOwnerFreePhases(() -> false, () -> false,
                () -> true, step::get, attempts::incrementAndGet));
        assertEquals(1, attempts.get());
        assertTrue(failure.getMessage().contains("made no progress"));
    }

    @Test void aQueuedPhaseWithNoActualOwnerEnvelopeStillFailsClosed() {
        RegionScheduler empty = new RegionScheduler(new LinkedHashMap<>(), new RegionDirectory(), new FailureBarrier());
        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> PhoeniumLiveRuntime.advanceRequiredOwnerWork(empty));
        assertTrue(failure.getMessage().contains("not reachable through the owner scheduler"));
        assertEquals(0, empty.advances());
    }
}
