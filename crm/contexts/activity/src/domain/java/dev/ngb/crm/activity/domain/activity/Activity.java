package dev.ngb.crm.activity.domain.activity;

import dev.ngb.core.id.TenantId;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One thing a customer did, as the Tenant's own system reported it: a free {@code type} the writer chose, when it
 * happened, an optional source and the writer's own payload.
 *
 * <p>An activity is appended and never edited, so the aggregate has no behaviour beyond what it was recorded with. A
 * correction is another activity. {@code activityRef} is the caller's own reference and is what a retried call is
 * matched on, which is why it is part of the record rather than of the call.
 *
 * <p>The customer is crm's own id; the Tenant's reference for it belongs to the customers context and is resolved
 * there, so an activity never stores a reference that an erasure could make stale.
 */
public record Activity(
        ActivityId id,
        TenantId tenantId,
        UUID customerId,
        String type,
        Instant occurredAt,
        String source,
        Map<String, Object> payload,
        String activityRef,
        Instant createdAt) {

    public Activity {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(createdAt, "createdAt");
        payload = payload == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }

    /** An activity that did not exist before: crm mints its id and stamps when it was recorded. */
    public static Activity recorded(
            ActivityId id,
            TenantId tenantId,
            UUID customerId,
            String type,
            Instant occurredAt,
            String source,
            Map<String, Object> payload,
            String activityRef,
            Instant now) {
        return new Activity(id, tenantId, customerId, type, occurredAt, source, payload, activityRef, now);
    }

    /** The activity a repository reads back from storage. */
    public static Activity restore(
            ActivityId id,
            TenantId tenantId,
            UUID customerId,
            String type,
            Instant occurredAt,
            String source,
            Map<String, Object> payload,
            String activityRef,
            Instant createdAt) {
        return new Activity(id, tenantId, customerId, type, occurredAt, source, payload, activityRef, createdAt);
    }
}
