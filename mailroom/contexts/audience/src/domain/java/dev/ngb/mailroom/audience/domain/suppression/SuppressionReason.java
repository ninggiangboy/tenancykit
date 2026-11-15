package dev.ngb.mailroom.audience.domain.suppression;

/** Why an address is suppressed: the customer unsubscribed, or the Tenant blocked it on purpose. */
public enum SuppressionReason {
    UNSUBSCRIBED,
    MANUAL
}
