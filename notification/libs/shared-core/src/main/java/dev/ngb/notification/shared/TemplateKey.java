package dev.ngb.notification.shared;

import java.util.regex.Pattern;

/**
 * A template's name: the directory the classpath default lives in and the key the shared override row carries.
 *
 * <p>It is typed rather than free text because the key becomes a resource path and a database key: a value with a
 * slash, a dot-dot or an upper-case letter would reach outside the template tree or stop matching a default.
 */
public record TemplateKey(String value) {

    /** Lower-case, digits and the three separators a dotted key needs; no leading separator. */
    private static final Pattern PATTERN = Pattern.compile("^[a-z0-9][a-z0-9._-]*$");

    private static final int MAX_LENGTH = 128;

    public TemplateKey {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("a template key must name the template");
        }
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("a template key is at most " + MAX_LENGTH + " characters");
        }
        if (!PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("a template key matches " + PATTERN.pattern());
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
