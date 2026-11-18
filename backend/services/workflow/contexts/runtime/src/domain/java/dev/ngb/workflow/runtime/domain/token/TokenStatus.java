package dev.ngb.workflow.runtime.domain.token;

/** Where one token stands. READY is claimable, RUNNING is leased, WAITING is parked on a timer or an event. */
public enum TokenStatus {
    READY,
    RUNNING,
    WAITING,
    DONE,
    FAILED,
    CANCELLED;

    public boolean live() {
        return this == READY || this == RUNNING || this == WAITING;
    }
}
