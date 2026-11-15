package dev.ngb.email.audience.domain.receiver;

import dev.ngb.core.error.BadRequestException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The typed payload a template may read and a segment may filter.
 *
 * <p>It is deliberately flat: a scalar or a small array of scalars, twenty keys at most and 4 KiB in total. A nested
 * structure would make the segment language and the strict renderer harder to bound, and no tenant has needed one.
 * Keys are lower-case snake case so a template name is a valid identifier.
 */
public final class ReceiverAttributes {

    public static final int MAX_KEYS = 20;
    public static final int MAX_ARRAY = 100;
    public static final int MAX_BYTES = 4 * 1024;

    private static final Pattern KEY = Pattern.compile("^[a-z][a-z0-9_]{0,31}$");

    private ReceiverAttributes() {}

    /** @return a mutable copy of the validated attributes, ready for the JSONB column */
    public static Map<String, Object> validate(Map<String, Object> attributes) {
        if (attributes == null) {
            return Map.of();
        }
        if (attributes.size() > MAX_KEYS) {
            throw new BadRequestException("a receiver has at most " + MAX_KEYS + " attributes");
        }
        Map<String, Object> checked = new LinkedHashMap<>();
        int bytes = 0;
        for (Map.Entry<String, Object> entry : attributes.entrySet()) {
            String key = entry.getKey();
            if (key == null || !KEY.matcher(key).matches()) {
                throw new BadRequestException("an attribute key is lower-case snake case, at most 32 characters");
            }
            Object value = scalarOrArray(key, entry.getValue());
            checked.put(key, value);
            bytes += key.getBytes(StandardCharsets.UTF_8).length + 8;
        }
        if (bytes > MAX_BYTES) {
            throw new BadRequestException("a receiver's attributes are at most " + MAX_BYTES + " bytes");
        }
        return checked;
    }

    private static Object scalarOrArray(String key, Object value) {
        if (value instanceof List<?> list) {
            if (list.size() > MAX_ARRAY) {
                throw new BadRequestException("the attribute " + key + " has at most " + MAX_ARRAY + " values");
            }
            for (Object item : list) {
                requireScalar(key, item);
            }
            return List.copyOf(list);
        }
        requireScalar(key, value);
        return value;
    }

    private static void requireScalar(String key, Object value) {
        if (value == null) {
            throw new BadRequestException("the attribute " + key + " may not be null");
        }
        if (!(value instanceof String || value instanceof Number || value instanceof Boolean)) {
            throw new BadRequestException("the attribute " + key + " must be a string, number, boolean or a list of those");
        }
    }
}
