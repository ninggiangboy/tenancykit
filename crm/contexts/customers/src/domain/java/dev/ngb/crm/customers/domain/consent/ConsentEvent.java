package dev.ngb.crm.customers.domain.consent;

import dev.ngb.core.id.TenantId;
import dev.ngb.crm.customers.domain.customer.CustomerId;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One record of a customer's agreement to one topic, appended and never rewritten.
 *
 * <p>{@code occurredAt} is when the consent changed in the Tenant's own world and orders the history;
 * {@code recordedAt} is when crm learned of it, which may be later. {@code source} and {@code evidenceRef} are the
 * Tenant's own free text, and {@code consentRef} is its optional key for the record: the same reference with the same
 * body replays the record instead of appending a second one.
 */
public final class ConsentEvent {

    private final UUID id;
    private final TenantId tenantId;
    private final CustomerId customerId;
    private final String topicKey;
    private final ConsentStatus status;
    private final Instant occurredAt;
    private final Instant recordedAt;
    private final String source;
    private final String evidenceRef;
    private final String consentRef;

    private ConsentEvent(
            UUID id,
            TenantId tenantId,
            CustomerId customerId,
            String topicKey,
            ConsentStatus status,
            Instant occurredAt,
            Instant recordedAt,
            String source,
            String evidenceRef,
            String consentRef) {
        this.id = Objects.requireNonNull(id, "id");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.customerId = Objects.requireNonNull(customerId, "customerId");
        this.topicKey = Objects.requireNonNull(topicKey, "topicKey");
        this.status = Objects.requireNonNull(status, "status");
        this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        this.recordedAt = Objects.requireNonNull(recordedAt, "recordedAt");
        this.source = source;
        this.evidenceRef = evidenceRef;
        this.consentRef = consentRef;
    }

    /** A record to append now. */
    public static ConsentEvent record(
            UUID id,
            TenantId tenantId,
            CustomerId customerId,
            String topicKey,
            ConsentStatus status,
            Instant occurredAt,
            Instant recordedAt,
            String source,
            String evidenceRef,
            String consentRef) {
        return new ConsentEvent(
                id, tenantId, customerId, topicKey, status, occurredAt, recordedAt, source, evidenceRef, consentRef);
    }

    /** The record a repository reads back from storage. */
    public static ConsentEvent restore(
            UUID id,
            TenantId tenantId,
            CustomerId customerId,
            String topicKey,
            ConsentStatus status,
            Instant occurredAt,
            Instant recordedAt,
            String source,
            String evidenceRef,
            String consentRef) {
        return new ConsentEvent(
                id, tenantId, customerId, topicKey, status, occurredAt, recordedAt, source, evidenceRef, consentRef);
    }

    /**
     * Whether a request asks for exactly what this record already holds, which is what makes a retry a replay rather
     * than a conflict or a second record.
     */
    public boolean sameRequest(
            String topicKey, ConsentStatus status, Instant occurredAt, String source, String evidenceRef) {
        return this.topicKey.equals(topicKey)
                && this.status == status
                && this.occurredAt.equals(occurredAt)
                && Objects.equals(this.source, source)
                && Objects.equals(this.evidenceRef, evidenceRef);
    }

    public UUID id() {
        return id;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public CustomerId customerId() {
        return customerId;
    }

    public String topicKey() {
        return topicKey;
    }

    public ConsentStatus status() {
        return status;
    }

    public Instant occurredAt() {
        return occurredAt;
    }

    public Instant recordedAt() {
        return recordedAt;
    }

    public String source() {
        return source;
    }

    public String evidenceRef() {
        return evidenceRef;
    }

    public String consentRef() {
        return consentRef;
    }
}
