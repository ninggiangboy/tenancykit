package dev.ngb.crm.customers.adapter.field.json;

import dev.ngb.crm.customers.application.field.port.FieldValues;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * A customer's field values as the JSON object they are stored as.
 *
 * <p>The writer emits the keys in order, so the same values always give the same bytes: a stored body and a sent body
 * that say the same thing compare equal, which is what makes a repeated request a no change. A number is read back as
 * a decimal, so the value the definition accepted is the value the next read compares against.
 */
public class JacksonFieldValues implements FieldValues {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};

    @Override
    public String toJson(Map<String, Object> values) {
        return JSON.writeValueAsString(values == null ? Map.of() : new TreeMap<>(values));
    }

    @Override
    public Map<String, Object> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        Map<String, Object> parsed = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : JSON.<Map<String, Object>>readValue(json, OBJECT).entrySet()) {
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            parsed.put(entry.getKey(), value instanceof Number number ? decimal(number) : value);
        }
        return Map.copyOf(parsed);
    }

    @Override
    public int byteLength(Map<String, Object> values) {
        return toJson(values).getBytes(StandardCharsets.UTF_8).length;
    }

    private static BigDecimal decimal(Number number) {
        BigDecimal decimal = new BigDecimal(number.toString());
        return decimal.signum() == 0 ? BigDecimal.ZERO : decimal.stripTrailingZeros();
    }
}
