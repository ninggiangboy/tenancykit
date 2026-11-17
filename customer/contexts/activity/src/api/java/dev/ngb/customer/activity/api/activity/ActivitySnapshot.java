package dev.ngb.customer.activity.api.activity;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One activity as another context reads it: the writer's type, when it happened, its source, the payload it carried
 * and the caller's own reference when it sent one.
 *
 * <p>It carries no customer attribute. A context that shows a customer's timeline needs to show what happened, not to
 * read the person it happened to.
 *
 * @param id customer's own id for the activity
 * @param customerId customer's own id for the customer the activity belongs to
 * @param type the free, lower-case type the writer chose
 * @param occurredAt when it happened, as the writer reported it
 * @param source the system that reported it, when it named one
 * @param payload the writer's own object
 * @param activityRef the caller's own reference, when it sent one
 * @param createdAt when customer appended it
 */
public record ActivitySnapshot(
        UUID id,
        UUID customerId,
        String type,
        Instant occurredAt,
        String source,
        Map<String, Object> payload,
        String activityRef,
        Instant createdAt) {

    public ActivitySnapshot {
        payload = payload == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }
}
