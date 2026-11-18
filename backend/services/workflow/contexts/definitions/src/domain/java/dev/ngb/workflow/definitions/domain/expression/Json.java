package dev.ngb.workflow.definitions.domain.expression;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The small amount of tree handling a definition document needs in plain Java.
 *
 * <p>A document is a JSON tree: an object whose members are objects, arrays and scalars. The domain never sees a
 * Jackson type, so a structural walk is a cast per level; this class is the one place that cast happens, and every
 * method answers null for a member of the wrong shape so a caller reports a problem instead of throwing.
 */
public final class Json {

    /** The longest path a reference may name. */
    private static final int MAX_PATH = 256;

    /** The longest one segment of a path may be. */
    private static final int MAX_SEGMENT = 64;

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

    /** Whether this is a boolean member and, when it is, its value. */
    public static boolean isTrue(Object value) {
        return Boolean.TRUE.equals(value);
    }

    /**
     * A value of a document at a closed dotted path.
     *
     * <p>An empty path names the whole value. A member that is absent, or a list index that does not exist, is
     * {@link Value#NIL}; resolution never throws on the shape of the data.
     */
    public static Value at(Object root, String path) {
        if (path == null || path.isEmpty()) {
            return Value.of(root);
        }
        Object current = raw(root, path);
        return current instanceof Missing ? Value.NIL : Value.of(current);
    }

    /**
     * The same path resolved to the document as it stands, so a node that needs a list or an object (a for-each's
     * items, a sub-workflow's input, a call body) gets the data rather than a scalar view of it. An absent member is
     * {@link Missing}, which callers read as {@code null}.
     */
    public static Object raw(Object root, String path) {
        if (path == null || path.isEmpty()) {
            return root;
        }
        Object current = root;
        for (String segment : path.split("\\.")) {
            if (current instanceof Map<?, ?> members) {
                current = members.get(segment);
            } else if (current instanceof List<?> members && isIndex(segment)) {
                int index = Integer.parseInt(segment);
                current = index < members.size() ? members.get(index) : null;
            } else {
                return Missing.INSTANCE;
            }
            if (current == null) {
                return Missing.INSTANCE;
            }
        }
        return current;
    }

    /** A path that named nothing, distinct from a member that is present and null. */
    public record Missing() {
        public static final Missing INSTANCE = new Missing();
    }

    /** Whether a raw resolution named nothing, which a caller reads as null. */
    public static boolean missing(Object value) {
        return value instanceof Missing;
    }

    /**
     * The closed dotted subset the contract allows: letters, digits and underscores, separated by dots, at most 256
     * characters, each segment at most 64. The empty path names the whole value and is valid.
     */
    public static boolean validPath(String path) {
        if (path == null || path.length() > MAX_PATH) {
            return false;
        }
        if (path.isEmpty()) {
            return true;
        }
        for (String segment : path.split("\\.", -1)) {
            if (segment.isEmpty() || segment.length() > MAX_SEGMENT || !isName(segment)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isName(String segment) {
        for (int index = 0; index < segment.length(); index++) {
            char character = segment.charAt(index);
            boolean allowed = (character >= 'a' && character <= 'z')
                    || (character >= 'A' && character <= 'Z')
                    || (character >= '0' && character <= '9')
                    || character == '_';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    private static boolean isIndex(String segment) {
        if (segment.length() > 9) {
            return false;
        }
        for (int index = 0; index < segment.length(); index++) {
            if (!Character.isDigit(segment.charAt(index))) {
                return false;
            }
        }
        return true;
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
}
