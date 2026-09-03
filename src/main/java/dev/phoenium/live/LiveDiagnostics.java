package dev.phoenium.live;

import dev.phoenium.scheduler.SchedulerLifecycle;

/** Immutable Stage 2 evidence snapshot; diagnostics never grant gameplay authority. */
public record LiveDiagnostics(
    PhoeniumLiveRuntime.State lifecycle, SchedulerLifecycle scheduler, long lifecycleStarts, long lifecycleStops,
    int simulationWorkers, boolean parallelGameplay, int controlledWorlds, int activeLeases,
    long intercepted, long dispatched, long executed, long duplicates, long unexpectedBypasses, long fallbacks,
    long ownershipViolations, long unleasedExecutions, long hardBarriers, boolean barrierTripped) {
    public String toLogLine() {
        return "lifecycle=" + lifecycle + " scheduler=" + scheduler + " workers=" + simulationWorkers
            + " parallel=OFF worlds=" + controlledWorlds + " leases=" + activeLeases + " intercepted=" + intercepted
            + " dispatched=" + dispatched + " executed=" + executed + " duplicate=" + duplicates
            + " bypass=" + unexpectedBypasses + " fallback=" + fallbacks + " ownershipViolations=" + ownershipViolations
            + " unleased=" + unleasedExecutions + " barriers=" + hardBarriers;
    }
}
