package dev.ngb.crm.customers.application.customer.query;

import dev.ngb.crm.customers.domain.customer.CustomerStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One customer as a read returns it. The version is the ETag, so a caller can patch what it just read without a
 * second call; the phone digits are internal and never leave the adapter.
 */
public record CustomerView(
        UUID id,
        String externalRef,
        String displayName,
        String phone,
        String email,
        Map<String, Object> customFields,
        List<String> tags,
        CustomerStatus status,
        Instant createdAt,
        Instant updatedAt,
        long version) {}
