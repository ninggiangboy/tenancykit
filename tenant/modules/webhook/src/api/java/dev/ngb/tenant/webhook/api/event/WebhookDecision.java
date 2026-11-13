package dev.ngb.tenant.webhook.api.event;

/** What the tenant decided to do with a received Billing event. */
public enum WebhookDecision {
    CONTINUE,
    RECORD_DEBT,
    CLEAR_DEBT,
    SUSPEND,
    EXTEND_PERIOD
}
