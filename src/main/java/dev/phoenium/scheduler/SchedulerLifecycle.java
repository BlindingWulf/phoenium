package dev.phoenium.scheduler;

public enum SchedulerLifecycle {
    RUNNING, QUIESCING, STOPPING, FAILED;

    public boolean acceptsSimulationWork() { return this == RUNNING; }
}
