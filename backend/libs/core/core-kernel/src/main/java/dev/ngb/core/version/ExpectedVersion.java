package dev.ngb.core.version;

import dev.ngb.core.error.PreconditionFailedException;
import java.util.HashSet;
import java.util.Set;

/**
 * The versions an {@code If-Match} header accepts, compared against an aggregate's {@code version}.
 *
 * <p>Comparison is strong, as RFC 9110 requires: a weak tag never matches, and a list passes when any of its entries
 * matches. {@code *} and a missing header accept whatever the resource holds. Anything else that is not a strong
 * entity tag is malformed and answers 400 through the {@link IllegalArgumentException} the error handlers already
 * map.
 */
public final class ExpectedVersion {

    private static final ExpectedVersion ANY = new ExpectedVersion(true, Set.of());

    private final boolean any;
    private final Set<Long> accepted;

    private ExpectedVersion(boolean any, Set<Long> accepted) {
        this.any = any;
        this.accepted = accepted;
    }

    /** Accepts whatever the resource holds: no header was sent, or the caller sent {@code *}. */
    public static ExpectedVersion any() {
        return ANY;
    }

    public static ExpectedVersion fromIfMatch(String header) {
        if (header == null || header.isBlank()) {
            return ANY;
        }
        String value = header.strip();
        if (value.equals("*")) {
            return ANY;
        }
        Set<Long> accepted = new HashSet<>();
        for (String raw : value.split(",", -1)) {
            String entry = raw.strip();
            if (isWeakTag(entry)) {
                // A weak tag is well formed but never a strong match, so it cannot accept anything on its own.
                continue;
            }
            accepted.add(strongTagVersion(entry));
        }
        return new ExpectedVersion(false, Set.copyOf(accepted));
    }

    /**
     * @throws PreconditionFailedException carrying the current version when the header names other versions
     */
    public void check(long current) {
        if (!any && !accepted.contains(current)) {
            throw new PreconditionFailedException(current);
        }
    }

    /**
     * For an aggregate that does not exist yet: only a missing header or {@code *} may create it.
     *
     * @throws PreconditionFailedException with no current version, because there is no row to compare against
     */
    public void checkAbsent() {
        if (!any) {
            throw new PreconditionFailedException(null);
        }
    }

    /** A strong entity tag holding a version as a decimal, ready for an {@code ETag} response header. */
    public static String etag(long version) {
        return "\"" + version + "\"";
    }

    private static boolean isWeakTag(String entry) {
        if (!entry.startsWith("W/")) {
            return false;
        }
        strongTagVersion(entry.substring(2));
        return true;
    }

    private static long strongTagVersion(String entry) {
        if (entry.length() < 3 || entry.charAt(0) != '"' || entry.charAt(entry.length() - 1) != '"') {
            throw new IllegalArgumentException("If-Match must hold a quoted version, such as \"3\"");
        }
        String digits = entry.substring(1, entry.length() - 1);
        for (int i = 0; i < digits.length(); i++) {
            char c = digits.charAt(i);
            if (c < '0' || c > '9') {
                throw new IllegalArgumentException("If-Match must hold a quoted version, such as \"3\"");
            }
        }
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("If-Match holds a version that is out of range", e);
        }
    }
}
