package dev.ngb.workflow.runtime.domain.instance;

/** Where an instance stands. A terminal status is the only one that carries a finish time. */
public enum InstanceStatus {
    RUNNING,
    WAITING,
    COMPLETED,
    FAILED,
    CANCELLED,
    COMPENSATING;

    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
