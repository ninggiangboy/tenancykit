package dev.ngb.email.sending.domain.email;

import dev.ngb.core.id.TenantId;
import dev.ngb.email.shared.EmailKind;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One email this app has decided to send, and the record of what happened to it.
 *
 * <p>The row exists before the command reaches the broker, so the outcome mailer reports always has somewhere to land.
 * `requestId` is the idempotency key mailer's ledger is keyed by; it is unique per tenant, so a retried API call or a
 * relay republish resolves to the same ledger. `bodyHtml`/`bodyText` are the only personal data here and housekeeping
 * removes them first.
 */
public record Email(
        UUID id,
        TenantId tenantId,
        EmailKind kind,
        UUID campaignId,
        UUID receiverId,
        String toAddress,
        String fromKey,
        String fromName,
        String replyTo,
        String subject,
        UUID templateId,
        Integer templateRevision,
        EmailStatus status,
        String statusReason,
        String requestId,
        String bodyHtml,
        String bodyText,
        Instant bodyPurgedAt,
        Instant deliveryUpdatedAt,
        Instant createdAt,
        Instant updatedAt) {

    private static final int MAX_SUBJECT = 998;

    public Email {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(toAddress, "toAddress");
        Objects.requireNonNull(fromKey, "fromKey");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (subject.isBlank() || subject.length() > MAX_SUBJECT || subject.contains("\r") || subject.contains("\n")) {
            throw new IllegalArgumentException("an email needs a subject of at most " + MAX_SUBJECT + " characters");
        }
    }

    public static Email queued(
            UUID id,
            TenantId tenantId,
            EmailKind kind,
            UUID campaignId,
            UUID receiverId,
            String toAddress,
            String fromKey,
            String fromName,
            String replyTo,
            String subject,
            UUID templateId,
            Integer templateRevision,
            String requestId,
            String bodyHtml,
            String bodyText,
            Instant now) {
        return new Email(
                id, tenantId, kind, campaignId, receiverId, toAddress, fromKey, fromName, replyTo, subject,
                templateId, templateRevision, EmailStatus.QUEUED, null, requestId, bodyHtml, bodyText,
                null, null, now, now);
    }

    public Email failed(String reason, Instant now) {
        return new Email(
                id, tenantId, kind, campaignId, receiverId, toAddress, fromKey, fromName, replyTo, subject,
                templateId, templateRevision, EmailStatus.FAILED, reason, requestId, bodyHtml, bodyText,
                bodyPurgedAt, deliveryUpdatedAt, createdAt, now);
    }

    public Email skipped(String reason, Instant now) {
        return new Email(
                id, tenantId, kind, campaignId, receiverId, toAddress, fromKey, fromName, replyTo, subject,
                templateId, templateRevision, EmailStatus.SKIPPED, reason, requestId, null, null,
                bodyPurgedAt, deliveryUpdatedAt, createdAt, now);
    }

    public boolean bodyPurged() {
        return bodyPurgedAt != null;
    }
}
