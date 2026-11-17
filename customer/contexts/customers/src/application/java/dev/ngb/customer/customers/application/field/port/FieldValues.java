package dev.ngb.customer.customers.application.field.port;

import java.util.Map;

/**
 * How a customer's field values cross the boundary between plain Java and the JSON object they are stored as.
 *
 * <p>The application layer decides what a value is and how large the whole object may be; the serialisation itself is
 * an adapter detail. Both directions normalise a number to the same decimal, so a value read back equals the value
 * written and a repeated body is recognised as no change.
 */
public interface FieldValues {

    /** @return the canonical JSON object of {@code values}, keys in order, as it is stored */
    String toJson(Map<String, Object> values);

    /** @return the values a stored JSON object holds, numbers as decimals */
    Map<String, Object> fromJson(String json);

    /** @return the size in bytes of the canonical JSON object, which is the size a caller's body may not exceed */
    int byteLength(Map<String, Object> values);
}
