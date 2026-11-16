package dev.ngb.crm.customers.application.customer.service;

import dev.ngb.crm.customers.application.field.exception.InvalidFieldException;
import dev.ngb.crm.customers.application.field.port.FieldDefinitionRepository;
import dev.ngb.crm.customers.application.field.port.FieldValues;
import dev.ngb.crm.customers.application.field.service.FieldRules;
import dev.ngb.crm.customers.domain.field.FieldDefinition;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The field values of a customer, checked against the definitions the tenant holds before they are stored.
 *
 * <p>A key the tenant does not define, or defines and archived, is refused rather than stored: a value nobody can name
 * would be written by a caller that believes it wrote something the tenant can read. A null clears the value instead,
 * because a customer may answer a question and later withdraw the answer.
 *
 * <p>The whole object is bounded, so a caller cannot make one customer's row unboundedly large by writing many values
 * at once.
 */
public class CustomerFields {

    private final FieldDefinitionRepository definitions;
    private final FieldValues values;
    private final int maxBytes;

    public CustomerFields(FieldDefinitionRepository definitions, FieldValues values, int maxBytes) {
        this.definitions = definitions;
        this.values = values;
        this.maxBytes = maxBytes;
    }

    /** @return the whole set a replacement states, checked against the active definitions */
    public Map<String, Object> replacing(Map<String, Object> sent) {
        return apply(Map.of(), sent);
    }

    /** @return the stored set with the keys the caller sent merged into it; a key it sent as null is cleared */
    public Map<String, Object> merging(Map<String, Object> stored, Map<String, Object> sent) {
        return apply(stored, sent);
    }

    private Map<String, Object> apply(Map<String, Object> base, Map<String, Object> sent) {
        if (sent == null || sent.isEmpty()) {
            return Map.copyOf(base);
        }
        Map<String, FieldDefinition> active = new HashMap<>();
        for (FieldDefinition definition : definitions.active(sent.keySet())) {
            active.put(definition.key(), definition);
        }
        Map<String, Object> result = new LinkedHashMap<>(base);
        for (Map.Entry<String, Object> entry : sent.entrySet()) {
            FieldDefinition definition = active.get(entry.getKey());
            if (definition == null) {
                throw new InvalidFieldException(
                        "A custom field with that key is not defined here or is archived.");
            }
            if (entry.getValue() == null) {
                result.remove(entry.getKey());
                continue;
            }
            result.put(entry.getKey(), FieldRules.value(definition, entry.getValue()));
        }
        if (values.byteLength(result) > maxBytes) {
            throw new InvalidFieldException(
                    "The field values of a customer are at most " + maxBytes + " bytes.");
        }
        return Map.copyOf(result);
    }
}
