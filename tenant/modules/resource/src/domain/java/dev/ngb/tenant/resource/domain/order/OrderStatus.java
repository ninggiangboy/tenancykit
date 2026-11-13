package dev.ngb.tenant.resource.domain.order;

/** The lifecycle of a resource order. Only an order whose reservation succeeded is queued. */
public enum OrderStatus {
    PENDING,
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    SUSPENDED,
    CANCELED
}
