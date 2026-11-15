package dev.ngb.email.shared;

import java.util.regex.Pattern;

/**
 * A template's name: the key a tenant addresses it by and the value every revision hangs off.
 *
 * <p>It is typed rather than free text because the key is a database key and, in a rendered email, a value a client
 * sends back: a value with a slash, a dot-dot or an upper-case letter would make the API accept something it can never
 * look up again.
 */
public record TemplateKey(String value) {

    /** Lower-case, digits and the three separators a dotted key needs; no leading separator. */
    private static final Pattern PATTERN = Pattern.compile("^[a-z0-9][a-z0-9._-]*$");

    /** The command contract allows 64; the key travels to mailer as a from-key-shaped value and back. */
    private static final int MAX_LENGTH = 64;

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
