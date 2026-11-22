package dev.ngb.customer.customers.application.consent.query;

import dev.ngb.customer.customers.domain.consent.ConsentStatus;
import java.time.Instant;

/**
 * One consent record as a read returns it. {@code source} and {@code evidenceRef} are cleared when the customer's
 * personal data is erased, and {@code consentRef} is the caller's own reference when it sent one.
 */
public record ConsentRecordView(
        String topicKey,
        ConsentStatus status,
        Instant occurredAt,
        Instant recordedAt,
        String source,
        String evidenceRef,
        String consentRef) {}
