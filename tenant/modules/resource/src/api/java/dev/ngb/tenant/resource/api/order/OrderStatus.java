package dev.ngb.tenant.resource.api.order;

/** The lifecycle of a resource order as the order and execution api expose it. */
public enum OrderStatus {
    PENDING,
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    SUSPENDED,
    CANCELED
}
