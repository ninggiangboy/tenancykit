package dev.ngb.mailer.shared;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The configuration key a producer names instead of a sender address. The address must be verified in the AWS
 * account that sends, so it belongs to this service's configuration; the display name beside it belongs to the
 * producer's branding. Keeping the field a key is what stops a producer from spoofing an arbitrary sender, and the
 * pattern is what stops it from smuggling an address, a path or a header into the field.
 */
public record FromKey(String value) {

    private static final Pattern PATTERN = Pattern.compile("^[a-z0-9][a-z0-9._-]*$");
    private static final int MAX_LENGTH = 64;

    public FromKey {
        Objects.requireNonNull(value, "value");
        if (value.isEmpty() || value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("a from-key must be 1-" + MAX_LENGTH + " characters");
        }
        if (!PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "a from-key must start with a lower-case letter or digit and hold only a-z, 0-9, '.', '_' or '-'");
        }
    }

    public static FromKey of(String value) {
        return new FromKey(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
