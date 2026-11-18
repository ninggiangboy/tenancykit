package dev.ngb.tenant.resource.domain.order;

/**
 * The lifecycle of a resource order. Only an order whose reservation succeeded is queued.
 *
 * <p>{@code CANCELING} is the observable intermediate of a cancel that also reaches Billing: the order records that it
 * is being cancelled before the remote call, so a process that dies mid-cancel leaves a state a reaper can finish
 * rather than an order that looks untouched while Billing already cancelled the subscription.
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
