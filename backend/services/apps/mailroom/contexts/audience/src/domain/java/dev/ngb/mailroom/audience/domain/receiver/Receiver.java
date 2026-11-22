package dev.ngb.mailroom.audience.domain.receiver;

import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** One of a Tenant's own customers: an address, an optional name and the attributes a template and a segment read. */
public record Receiver(
        UUID id,
        TenantId tenantId,
        String email,
        String emailNormalized,
        String firstName,
        String lastName,
        Map<String, Object> attributes,
        ReceiverStatus status,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    private static final int MAX_NAME = 200;

    public Receiver {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(email, "email");
        Objects.requireNonNull(emailNormalized, "emailNormalized");
        Objects.requireNonNull(attributes, "attributes");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        attributes = Map.copyOf(attributes);
        if (version < 1) {
            throw new IllegalArgumentException("a receiver starts at version 1");
        }
    }

    public static Receiver create(
            UUID id, TenantId tenantId, String email, String firstName, String lastName,
            Map<String, Object> attributes, Instant now) {
        String normalized = NormalizedAddress.normalize(email);
        return new Receiver(
                id, tenantId, email.trim(), normalized, clip(firstName), clip(lastName),
                ReceiverAttributes.validate(attributes), ReceiverStatus.SUBSCRIBED, 1, now, now);
    }

    /** A later upsert changes names and attributes but never silently re-subscribes an address. */
    public Receiver merge(String firstName, String lastName, Map<String, Object> attributes, Instant now) {
        return new Receiver(
                id, tenantId, email, emailNormalized, clip(firstName), clip(lastName),
                ReceiverAttributes.validate(attributes), status, version + 1, createdAt, now);
    }

    public Receiver unsubscribe(Instant now) {
        return new Receiver(
                id, tenantId, email, emailNormalized, firstName, lastName, attributes,
                ReceiverStatus.UNSUBSCRIBED, version + 1, createdAt, now);
    }

    public Receiver subscribe(Instant now) {
        return new Receiver(
                id, tenantId, email, emailNormalized, firstName, lastName, attributes,
                ReceiverStatus.SUBSCRIBED, version + 1, createdAt, now);
    }

    private static String clip(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() > MAX_NAME ? trimmed.substring(0, MAX_NAME) : trimmed;
    }
}
