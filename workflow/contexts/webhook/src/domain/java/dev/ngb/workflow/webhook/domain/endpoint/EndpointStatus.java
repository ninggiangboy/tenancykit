package dev.ngb.workflow.webhook.domain.endpoint;

public enum EndpointStatus {
    ACTIVE,
    DISABLED,
    /**
     * The endpoint was deleted. The row stays so the deliveries it already received remain readable, and it is gone
     * from every list and every subscription.
     */
    DELETED
}
