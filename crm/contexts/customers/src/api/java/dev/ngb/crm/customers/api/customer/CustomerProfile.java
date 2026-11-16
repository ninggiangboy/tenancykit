package dev.ngb.crm.customers.api.customer;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One customer as another context reads it whole: its attributes, its field values, its tags and what it currently
 * agrees to.
 *
 * <p>It is a read of one customer and nothing else: no order, no activity and no count, because those belong to the
 * contexts that own them. A context that shows a customer's profile composes this with the slices it reads from those
 * contexts.
 *
 * @param id crm's own id for the customer
 * @param externalRef the Tenant's reference, unique per tenant and immutable
 * @param displayName the name the Tenant sent, or null
 * @param phone the phone as the Tenant sent it, or null
 * @param email the lower-cased address, or null
 * @param customFields the field values keyed by the Tenant's own field keys
 * @param tags the tags the customer carries, in order
 * @param status {@code ACTIVE}, or {@code ERASED} once the person's data was erased
 * @param consents the current state of every topic the customer has a record for
 * @param createdAt when the customer was created
 * @param updatedAt when it last changed
 * @param version the version a conditional change is pinned to
 */
public record CustomerProfile(
        UUID id,
        String externalRef,
        String displayName,
        String phone,
        String email,
        Map<String, Object> customFields,
        List<String> tags,
        String status,
        List<ConsentState> consents,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    public CustomerProfile {
        customFields = customFields == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(customFields));
        tags = List.copyOf(tags);
        consents = List.copyOf(consents);
    }
}
