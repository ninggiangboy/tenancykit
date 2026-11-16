package dev.ngb.crm.customers.application.consent.service;

import dev.ngb.crm.customers.application.consent.exception.InvalidConsentException;
import dev.ngb.crm.customers.domain.consent.ConsentStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What a consent topic and a consent record must be before they are stored. The rules live in one place, so the topic
 * endpoints and the record endpoint cannot disagree about them.
 *
 * <p>A topic's key follows the same shape as a custom field's key, because both are the Tenant's identifiers for
 * something it defines. A record may not claim to have happened further in the future than the Tenant's clock drift
 * allows: an instant that is far ahead would sort above every honest record in the history.
 */
public final class ConsentRules {

    /** The longest key a Tenant may choose, counting its first character. */
    public static final int MAX_KEY = 40;

    public static final int MAX_LABEL = 200;

    /** The longest source or evidence reference a record holds. */
    public static final int MAX_TEXT = 500;

    /** The longest caller reference that makes a record replayable. */
    public static final int MAX_CONSENT_REF = 200;

    private static final Pattern KEY = Pattern.compile("^[a-z][a-z0-9_]{0,39}$");

    private ConsentRules() {}

    /** The key: lower snake case, starting with a letter, unique per tenant and immutable. */
    public static String key(String raw) {
        if (raw == null) {
            throw new InvalidConsentException("A key is required.");
        }
        if (!KEY.matcher(raw).matches()) {
            throw new InvalidConsentException("A key is 1 to " + MAX_KEY + " lower-case letters, digits or underscores"
                    + " and starts with a letter.");
        }
        return raw;
    }

    public static String label(String raw) {
        if (raw == null) {
            throw new InvalidConsentException("A label is required.");
        }
        String label = raw.strip();
        if (label.isEmpty() || label.length() > MAX_LABEL) {
            throw new InvalidConsentException("A label is 1 to " + MAX_LABEL + " characters.");
        }
        return label;
    }

    public static ConsentStatus status(String raw) {
        if (raw == null) {
            throw new InvalidConsentException("A status is required, GRANTED or REVOKED.");
        }
        try {
            return ConsentStatus.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new InvalidConsentException("A status is GRANTED or REVOKED.");
        }
    }

    /** When the consent changed. It may be in the past without limit and at most {@code maxAhead} in the future. */
    public static Instant occurredAt(Instant raw, Instant now, Duration maxAhead) {
        if (raw == null) {
            throw new InvalidConsentException("An occurredAt instant is required.");
        }
        if (raw.isAfter(now.plus(maxAhead))) {
            throw new InvalidConsentException("An occurredAt instant may be at most " + maxAhead.toMinutes()
                    + " minutes in the future.");
        }
        return raw;
    }

    /** The Tenant's free text about where a consent was collected, or null when it sent none. */
    public static String source(String raw) {
        return optionalText(raw, MAX_TEXT, "A source");
    }

    /** The Tenant's reference to the evidence of a consent, or null when it sent none. */
    public static String evidenceRef(String raw) {
        return optionalText(raw, MAX_TEXT, "An evidence reference");
    }

    /** The caller's own reference for a record; with it the call is replayable, without it every call appends. */
    public static String consentRef(String raw) {
        return optionalText(raw, MAX_CONSENT_REF, "A consent reference");
    }

    private static String optionalText(String raw, int max, String what) {
        if (raw == null) {
            return null;
        }
        String text = raw.strip();
        if (text.isEmpty()) {
            return null;
        }
        if (text.length() > max) {
            throw new InvalidConsentException(what + " is at most " + max + " characters.");
        }
        return text;
    }
}
