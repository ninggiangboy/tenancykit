package dev.ngb.tenant.resource.domain.order;

/** The status of one row in the execution queue this module owns. */
public enum QueueStatus {
    PENDING,
    RUNNING,
    DONE
}
