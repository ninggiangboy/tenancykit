package dev.ngb.tenant.resource.api.order;

/**
 * The lifecycle of a resource order as the order and execution api expose it.
 *
 * <p>{@code CANCELING} is visible to clients because it is the truth: a cancel that reaches Billing writes it before
 * the remote call, so a request that dies mid-cancel leaves an order a reaper will finish.
 */
public enum OrderStatus {
    PENDING,
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    SUSPENDED,
    CANCELING,
    CANCELED
}
