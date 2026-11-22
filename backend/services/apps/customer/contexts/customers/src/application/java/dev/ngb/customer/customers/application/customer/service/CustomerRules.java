package dev.ngb.customer.customers.application.customer.service;

import dev.ngb.customer.customers.application.customer.exception.InvalidCustomerException;
import java.util.Locale;

/**
 * What a customer's attributes must be before they are stored. The rules are the contract's, in one place, so the
 * single upsert, the batch and the patch cannot disagree about what is acceptable.
 *
 * <p>A null means "the Tenant did not send this", and an attribute the Tenant does not have is stored as null. A value
 * that is present but blank is refused rather than silently cleared, so a caller that meant to clear a field sends
 * null and a caller that sent an empty string is told about it.
 */
public final class CustomerRules {

    /** The longest reference a Tenant may choose. */
    public static final int MAX_EXTERNAL_REF = 128;

    public static final int MAX_DISPLAY_NAME = 200;

    public static final int MAX_PHONE = 32;

    public static final int MAX_EMAIL = 254;

    private CustomerRules() {}

    /** The Tenant's reference, trimmed: it is the natural key of the customer and the path of every call about it. */
    public static String externalRef(String raw) {
        if (raw == null) {
            throw new InvalidCustomerException("A customer reference is required.");
        }
        String ref = raw.strip();
        if (ref.isEmpty() || ref.length() > MAX_EXTERNAL_REF) {
            throw new InvalidCustomerException(
                    "A customer reference is 1 to " + MAX_EXTERNAL_REF + " characters.");
        }
        return ref;
    }

    public static String displayName(String raw) {
        if (raw == null) {
            return null;
        }
        String name = raw.strip();
        if (name.isEmpty() || name.length() > MAX_DISPLAY_NAME) {
            throw new InvalidCustomerException("A display name is 1 to " + MAX_DISPLAY_NAME + " characters.");
        }
        return name;
    }

    /** Stored as the Tenant sent it, because its formatting is the Tenant's business; only the length is bounded. */
    public static String phone(String raw) {
        if (raw == null) {
            return null;
        }
        if (raw.isBlank() || raw.length() > MAX_PHONE) {
            throw new InvalidCustomerException("A phone number is 1 to " + MAX_PHONE + " characters.");
        }
        return raw;
    }

    /**
     * The digits of {@code phone}, so a lookup ignores spaces, dashes and a country-code prefix's punctuation.
     *
     * @return the digits, or null when the number holds none at all
     */
    public static String phoneDigits(String phone) {
        if (phone == null) {
            return null;
        }
        StringBuilder digits = new StringBuilder(phone.length());
        for (int i = 0; i < phone.length(); i++) {
            char c = phone.charAt(i);
            if (c >= '0' && c <= '9') {
                digits.append(c);
            }
        }
        return digits.isEmpty() ? null : digits.toString();
    }

    /**
     * Stored lower-cased and validated loosely: customer is not an email verifier, it only keeps a value that could be an
     * address at all, so one {@code @}, a local part, a domain and a dot in that domain.
     */
    public static String email(String raw) {
        if (raw == null) {
            return null;
        }
        String address = raw.strip().toLowerCase(Locale.ROOT);
        if (address.isEmpty() || address.length() > MAX_EMAIL || !looksLikeAnAddress(address)) {
            throw new InvalidCustomerException(
                    "An email address is at most " + MAX_EMAIL + " characters and must look like one address.");
        }
        return address;
    }

    private static boolean looksLikeAnAddress(String address) {
        int at = address.indexOf('@');
        if (at <= 0 || at != address.lastIndexOf('@') || at == address.length() - 1) {
            return false;
        }
        String domain = address.substring(at + 1);
        int dot = domain.indexOf('.');
        if (dot <= 0 || dot == domain.length() - 1) {
            return false;
        }
        for (int i = 0; i < address.length(); i++) {
            if (Character.isWhitespace(address.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
