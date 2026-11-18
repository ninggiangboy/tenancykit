package dev.ngb.mailer.shared;

import java.util.Objects;

/**
 * One recipient address. It is a value object rather than a string because every later layer may hand it to a mail
 * client, and a header is built by concatenation: a line break, an angle bracket or a comma in the address changes
 * who receives the message or what the client displays. Validating once at construction means no later caller can
 * be given a value that breaks the header.
 */
public record Recipient(String address) {

    private static final int MAX_LENGTH = 320;
    private static final int MAX_LOCAL_PART = 64;
    private static final int MAX_DOMAIN = 255;

    /** Characters that end or redirect a header field, or are illegal in a mailbox. */
    private static final String FORBIDDEN = " <>,;:\"()[]\\";

    public Recipient {
        address = validate(address);
    }

    public static Recipient of(String address) {
        return new Recipient(address);
    }

    /** The part before the {@code @}. */
    public String localPart() {
        return address.substring(0, address.indexOf('@'));
    }

    /** The part after the {@code @}. */
    public String domain() {
        return address.substring(address.indexOf('@') + 1);
    }

    @Override
    public String toString() {
        return address;
    }

    private static String validate(String value) {
        Objects.requireNonNull(value, "address");
        if (value.isEmpty() || !value.equals(value.strip())) {
            throw new IllegalArgumentException("an address must not be blank or padded");
        }
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("an address must be at most " + MAX_LENGTH + " characters");
        }
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character < 0x20 || character == 0x7f || FORBIDDEN.indexOf(character) >= 0) {
                throw new IllegalArgumentException(
                        "an address must not contain control characters, whitespace or the characters that break a header");
            }
        }
        int at = value.indexOf('@');
        if (at <= 0 || at != value.lastIndexOf('@') || at == value.length() - 1) {
            throw new IllegalArgumentException("an address must be local@domain");
        }
        if (at > MAX_LOCAL_PART) {
            throw new IllegalArgumentException("the local part of an address must be at most " + MAX_LOCAL_PART + " characters");
        }
        String domain = value.substring(at + 1);
        if (domain.length() > MAX_DOMAIN
                || !domain.contains(".")
                || domain.startsWith(".")
                || domain.endsWith(".")
                || domain.contains("..")) {
            throw new IllegalArgumentException("the domain part of an address must be a dotted host name");
        }
        return value;
    }
}
