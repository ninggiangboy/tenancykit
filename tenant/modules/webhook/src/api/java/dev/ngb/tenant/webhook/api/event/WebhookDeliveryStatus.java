package dev.ngb.tenant.webhook.api.event;

/** The delivery lifecycle of one received Billing event. */
public enum WebhookDeliveryStatus {
    RECEIVED,
    PROCESSED,
    FAILED
}
