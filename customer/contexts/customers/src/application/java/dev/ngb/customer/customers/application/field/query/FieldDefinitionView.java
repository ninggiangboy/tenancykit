package dev.ngb.customer.customers.application.field.query;

import dev.ngb.customer.customers.domain.field.FieldType;
import java.time.Instant;
import java.util.List;

/** One field definition as a read returns it. The type and the key are the immutable part a caller reads back. */
public record FieldDefinitionView(
        String key,
        String label,
        FieldType type,
        List<String> choices,
        boolean archived,
        Instant createdAt) {}
