package dev.ngb.workflow.runtime.domain.instance;

public enum InstanceStatus {
    RUNNING,
    WAITING,
    COMPLETED,
    FAILED,
    FAILED_STUCK,
    CANCELLED,
    COMPENSATING,
    COMPENSATION_FAILED;

    public boolean terminal() {
        return this == COMPLETED || this == FAILED || this == FAILED_STUCK || this == CANCELLED
                || this == COMPENSATION_FAILED;
    }
}
