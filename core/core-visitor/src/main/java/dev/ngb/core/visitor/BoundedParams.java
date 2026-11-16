package dev.ngb.core.visitor;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Keeps only the bounded, non-identifying facts a request may contribute, from a map a caller cannot trust.
 *
 * <p>UTM values are a Tenant's own campaign labels, so they are useful; a whole query string is not, and neither is a
 * value long enough to smuggle something. Only the five {@code utm_*} keys survive, each trimmed to {@value
 * #MAX_LENGTH} characters with control characters removed. An unknown key, a blank value or a non-text value is
 * dropped rather than rejected, because a hostile parameter must never fail a redirect.
 */
public final class BoundedParams {

    /** One UTM value's limit; a real label is far shorter. */
    public static final int MAX_LENGTH = 100;

    /** The only query parameters a link may carry into storage. */
    public static final Set<String> UTM_KEYS = Set.of("utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content");

    private BoundedParams() {}

    /**
     * @param params a query or UTM map, possibly with unknown keys, blanks or non-text values
     * @return the five UTM keys that survived, in a stable order, each bounded and cleaned
     */
    public static Map<String, String> utm(Map<String, ?> params) {
        if (params == null || params.isEmpty()) {
            return Map.of();
        }
        Map<String, String> kept = new LinkedHashMap<>();
        for (String key : new String[] {"utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content"}) {
            Object value = params.get(key);
            if (!(value instanceof String text)) {
                continue;
            }
            String cleaned = clean(text);
            if (!cleaned.isEmpty()) {
                kept.put(key, cleaned);
            }
        }
        return Map.copyOf(kept);
    }

    /**
     * One text value, trimmed, with control characters removed and capped.
     *
     * @return the cleaned value, or empty when nothing is left
     */
    public static String clean(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder cleaned = new StringBuilder(Math.min(value.length(), MAX_LENGTH));
        for (int index = 0; index < value.length() && cleaned.length() < MAX_LENGTH; index++) {
            char c = value.charAt(index);
            if (Character.isISOControl(c)) {
                continue;
            }
            cleaned.append(c);
        }
        return cleaned.toString().strip();
    }

    /** Whether a parameter name is one the app keeps. */
    public static boolean isUtmKey(String key) {
        return key != null && UTM_KEYS.contains(key.toLowerCase(Locale.ROOT));
    }
}
