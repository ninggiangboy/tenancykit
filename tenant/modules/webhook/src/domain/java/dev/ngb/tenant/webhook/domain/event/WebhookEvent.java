package dev.ngb.tenant.webhook.domain.event;

import dev.ngb.core.ddd.AggregateRoot;
import dev.ngb.core.runtime.IdGenerator;
import dev.ngb.tenant.webhook.api.event.WebhookDecision;
import dev.ngb.tenant.webhook.api.event.WebhookDeliveryStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** One received Billing event, kept so a repeat delivery is recognised and the decision is auditable. */
public final class WebhookEvent extends AggregateRoot<UUID> {

    private final String billingEventId;
    private final String type;
    private final String tenantId;
    private final Instant occurredAt;
    private final String payload;
    private final Instant receivedAt;
    private final WebhookDeliveryStatus status;
    private final WebhookDecision decision;
    private final String detail;

    private WebhookEvent(
            UUID id,
            String billingEventId,
            String type,
            String tenantId,
            Instant occurredAt,
            String payload,
            Instant receivedAt,
            WebhookDeliveryStatus status,
            WebhookDecision decision,
            String detail) {
        super(id);
        this.billingEventId = Objects.requireNonNull(billingEventId, "billingEventId");
        this.type = Objects.requireNonNull(type, "type");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        this.payload = Objects.requireNonNull(payload, "payload");
        this.receivedAt = Objects.requireNonNull(receivedAt, "receivedAt");
        this.status = Objects.requireNonNull(status, "status");
        this.decision = decision;
        this.detail = detail;
    }

    public static WebhookEvent received(
            IdGenerator ids,
            String billingEventId, String type, String tenantId, Instant occurredAt, String payload, Instant receivedAt) {
        return new WebhookEvent(
                ids.newId(), billingEventId, type, tenantId, occurredAt, payload, receivedAt, WebhookDeliveryStatus.RECEIVED, null, null);
    }

    public static WebhookEvent restore(
            UUID id,
            String billingEventId,
            String type,
            String tenantId,
            Instant occurredAt,
            String payload,
            Instant receivedAt,
            WebhookDeliveryStatus status,
            WebhookDecision decision,
            String detail) {
        return new WebhookEvent(id, billingEventId, type, tenantId, occurredAt, payload, receivedAt, status, decision, detail);
    }

    public WebhookEvent processed(WebhookDecision nextDecision) {
        return new WebhookEvent(id(), billingEventId, type, tenantId, occurredAt, payload, receivedAt, WebhookDeliveryStatus.PROCESSED, nextDecision, detail);
    }

    public WebhookEvent failed(String reason) {
        return new WebhookEvent(id(), billingEventId, type, tenantId, occurredAt, payload, receivedAt, WebhookDeliveryStatus.FAILED, decision, reason);
    }

    public String billingEventId() {
        return billingEventId;
    }

    public String type() {
        return type;
    }

    public String tenantId() {
        return tenantId;
    }

    public Instant occurredAt() {
        return occurredAt;
    }

    public String payload() {
        return payload;
    }

    public Instant receivedAt() {
        return receivedAt;
    }

    public WebhookDeliveryStatus status() {
        return status;
    }

    public WebhookDecision decision() {
        return decision;
    }

    public String detail() {
        return detail;
    }
}
