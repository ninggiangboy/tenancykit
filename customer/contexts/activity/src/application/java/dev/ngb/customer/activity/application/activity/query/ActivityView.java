package dev.ngb.customer.activity.application.activity.query;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One activity as a read returns it: the writer's type, when it happened, its source and the payload it carried.
 *
 * <p>{@code activityRef} is the caller's own reference when it sent one, so a reader can match what it wrote to what
 * it reads back.
 */
public record ActivityView(
        UUID id,
        UUID customerId,
        String type,
        Instant occurredAt,
        String source,
        Map<String, Object> payload,
        String activityRef,
        Instant createdAt) {

    public ActivityView {
        payload = payload == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }
}
