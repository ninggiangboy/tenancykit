package dev.ngb.customer.customers.application.customer.query;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a caller may narrow the customer list and the export by beyond the list contract's own filters: a search term,
 * the tags a customer must carry, its field values, its current consent state and a window of when it was created.
 *
 * <p>Every member is optional and an absent one narrows nothing. The values of the field and consent maps are the raw
 * query-string values; they are read against the Tenant's own definitions where the query is built, so a key the
 * Tenant never defined is told about rather than silently ignored.
 *
 * @param q the search term: the reference exactly, a phone-digit prefix, an email prefix, or part of a name
 * @param tags the tags every match must carry
 * @param fieldEquals equality on a field value, keyed by the Tenant's field key
 * @param fieldAtLeast a lower bound on a {@code NUMBER} or {@code DATE} field value, keyed by the field key
 * @param fieldAtMost an upper bound on a {@code NUMBER} or {@code DATE} field value, keyed by the field key
 * @param consents the required current state per topic: {@code granted}, {@code revoked} or {@code none}
 * @param createdFrom the earliest creation instant, or null
 * @param createdTo the latest creation instant, or null
 */
public record CustomerSearchCriteria(
        String q,
        List<String> tags,
        Map<String, String> fieldEquals,
        Map<String, String> fieldAtLeast,
        Map<String, String> fieldAtMost,
        Map<String, String> consents,
        Instant createdFrom,
        Instant createdTo) {

    public CustomerSearchCriteria {
        tags = tags == null ? List.of() : List.copyOf(tags);
        fieldEquals = copyOf(fieldEquals);
        fieldAtLeast = copyOf(fieldAtLeast);
        fieldAtMost = copyOf(fieldAtMost);
        consents = copyOf(consents);
    }

    /** No narrowing at all: the whole tenant, in the listing's own order. */
    public static CustomerSearchCriteria none() {
        return new CustomerSearchCriteria(null, List.of(), Map.of(), Map.of(), Map.of(), Map.of(), null, null);
    }

    private static Map<String, String> copyOf(Map<String, String> values) {
        return values == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
