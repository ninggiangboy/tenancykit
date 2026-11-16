package dev.ngb.survey.authoring.domain.document;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The small amount of tree handling a version document needs in plain Java.
 *
 * <p>The document is JSON: an object whose members are objects, arrays and scalars. The domain never sees a Jackson
 * type, so a structural walk is a cast per level; this class is the one place that cast happens, and every method
 * answers null for a member of the wrong shape so a caller reports a validation error instead of throwing.
 */
public final class Json {

    private Json() {}

    /** The object at this member, or null when it is absent or another kind of value. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : null;
    }

    /** The array at this member, or null when it is absent or another kind of value. */
    @SuppressWarnings("unchecked")
    public static List<Object> array(Object value) {
        return value instanceof List<?> ? (List<Object>) value : null;
    }

    /** The text at this member, or null when it is absent or another kind of value. */
    public static String text(Object value) {
        return value instanceof String string ? string : null;
    }

    /**
     * A deep copy, preserving insertion order. A caller hands the stored document to a caller that may keep it, so a
     * mutation here must never reach a map a client still holds.
     */
    public static Object copy(Object value) {
        if (value instanceof Map<?, ?> members) {
            Map<String, Object> copy = new LinkedHashMap<>();
            members.forEach((key, member) -> copy.put(String.valueOf(key), copy(member)));
            return copy;
        }
        if (value instanceof List<?> members) {
            List<Object> copy = new ArrayList<>(members.size());
            members.forEach(member -> copy.add(copy(member)));
            return copy;
        }
        return value;
    }

    /** The copy of an object member as a typed map. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> copyOf(Map<String, Object> document) {
        return (Map<String, Object>) copy(document);
    }
}
