package dev.ngb.crm.customers.application.customer.query;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One customer as the export writes it: the attributes the Tenant itself sent, its tags, and one column per active
 * field key.
 *
 * @param externalRef the Tenant's reference, the first column
 * @param displayName the name, or null
 * @param phone the phone as sent, or null
 * @param email the address, or null
 * @param tags the tags, in order, written pipe-separated
 * @param customFields the field values keyed by the Tenant's field key
 * @param createdAt when the customer was created
 */
public record CustomerExportRow(
        String externalRef,
        String displayName,
        String phone,
        String email,
        java.util.List<String> tags,
        Map<String, Object> customFields,
        Instant createdAt) {

    public CustomerExportRow {
        tags = tags == null ? java.util.List.of() : java.util.List.copyOf(tags);
        customFields = customFields == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(customFields));
    }
}
