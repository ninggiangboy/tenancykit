package dev.ngb.workflow.tasks.domain.task;

/** Where one human task stands. An open task is the only one a person can act on. */
public enum TaskStatus {
    OPEN,
    CLAIMED,
    COMPLETED,
    CANCELLED,
    ESCALATED,
    EXPIRED;

    public boolean open() {
        return this == OPEN || this == CLAIMED || this == ESCALATED;
    }
}
